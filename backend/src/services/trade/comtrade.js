// Estadísticas oficiales de comercio exterior (UN Comtrade, producto gratuito "comtrade - v1").
// Contrato verificado con el cliente oficial de la ONU `comtradeapicall` 1.3.2 (PyPI):
//   GET https://comtradeapi.un.org/data/v1/get/{typeCode}/{freqCode}/{clCode}
//   parámetros reporterCode, partnerCode, period, cmdCode, flowCode (M/X), maxRecords, includeDesc,
//   breakdownMode; registros en `data` con reporterDesc, partnerCode, partnerDesc, cmdCode, cmdDesc,
//   flowCode, flowDesc, period y primaryValue (USD).
// La clave va en la cabecera Ocp-Apim-Subscription-Key (no en la URL, para que no quede en logs).
import { AppError, Errors } from '../../http/errors.js';
import { TtlCache } from '../cache.js';

export const COSTA_RICA = 188; // código M49 del país informante
const WORLD_CODE = 0;

export class ComtradeService {
  constructor({ config, http, usage, now = () => new Date() }) {
    this.cfg = config.trade;
    this.http = http;
    this.usage = usage;
    this.now = now;
    // Las cifras anuales publicadas cambian poco: un día de caché ahorra cuota.
    this.cache = new TtlCache(200, 86_400_000);
  }

  get enabled() {
    return Boolean(this.cfg.comtradeKey);
  }

  /**
   * @param {{hsCode:string, flow:'import'|'export', years?:number[], byPartner?:boolean, reporterCode?:number}} q
   */
  async stats({ hsCode, flow, years, byPartner = true, reporterCode = COSTA_RICA }) {
    if (!this.enabled) throw Errors.notConfigured('estadísticas de comercio exterior (UN_COMTRADE_API_KEY)');
    const lastYear = this.now().getUTCFullYear() - 1;
    const periods = (years?.length ? years : [lastYear, lastYear - 1, lastYear - 2]).slice().sort((a, b) => b - a);
    const flowCode = flow === 'import' ? 'M' : 'X';
    const key = JSON.stringify({ hsCode, flowCode, periods, byPartner, reporterCode });
    const cached = this.cache.get(key);
    if (cached) return { ...cached, cached: true };

    if (this.usage.tradeToday() >= this.cfg.maxPerDay) {
      throw Errors.rateLimited('Límite diario de consultas a UN Comtrade alcanzado (cuota del plan gratuito).', 3600);
    }
    const params = new URLSearchParams({
      reporterCode: String(reporterCode),
      period: periods.join(','),
      cmdCode: hsCode,
      flowCode,
      includeDesc: 'true',
      breakdownMode: 'classic',
      maxRecords: '2500',
    });
    // Sin partnerCode la API devuelve todos los socios; con 0 solo el total mundial.
    if (!byPartner) params.set('partnerCode', String(WORLD_CODE));
    this.usage.recordTrade();
    const res = await this.http.request('UN Comtrade', `${this.cfg.comtradeBaseUrl}/data/v1/get/C/A/HS?${params}`, {
      headers: { 'Ocp-Apim-Subscription-Key': this.cfg.comtradeKey, accept: 'application/json' },
      mapError: mapComtradeError,
      timeout: 60_000,
      maxBytes: 8_000_000,
    });
    const body = res.json();
    if (body?.error) throw new AppError(502, 'upstream_rejected', `UN Comtrade rechazó la consulta: ${String(body.error).slice(0, 200)}`);
    const rows = (Array.isArray(body?.data) ? body.data : []).filter((r) => r && typeof r.primaryValue === 'number');
    const result = summarize(rows, { hsCode, flow, periods, reporterCode });
    result.retrievedAt = this.now().toISOString();
    this.cache.set(key, result);
    return result;
  }
}

const isWorld = (r) => r.partnerCode === WORLD_CODE || /^world$/i.test(String(r.partnerDesc ?? ''));

/** Totales por año (fila "World" si existe; si no, suma de socios) y principales socios del último año. */
export function summarize(rows, { hsCode, flow, periods, reporterCode }) {
  const warnings = [];
  const years = [...new Set(rows.map((r) => Number(r.period)))].sort((a, b) => b - a);
  const totals = years.map((y) => {
    const ofYear = rows.filter((r) => Number(r.period) === y);
    const world = ofYear.find(isWorld);
    const value = world ? world.primaryValue : ofYear.reduce((s, r) => s + r.primaryValue, 0);
    return { year: y, valueUsd: Math.round(value), basis: world ? 'total mundial declarado' : 'suma de socios' };
  });
  const latest = years[0];
  const partners = rows
    .filter((r) => Number(r.period) === latest && !isWorld(r))
    .sort((a, b) => b.primaryValue - a.primaryValue)
    .slice(0, 10)
    .map((r) => ({ partner: r.partnerDesc ?? String(r.partnerCode), partnerCode: r.partnerCode, valueUsd: Math.round(r.primaryValue) }));
  const missing = periods.filter((p) => !years.includes(p));
  if (missing.length) warnings.push(`Sin datos publicados para ${missing.join(', ')} (puede que aún no estén informados).`);
  if (!rows.length) warnings.push('UN Comtrade no tiene registros para esa partida, flujo y período.');
  const first = rows[0] ?? {};
  return {
    query: { hsCode, flow, periods, reporterCode },
    reporter: first.reporterDesc ?? null,
    product: first.cmdDesc ?? null,
    flowLabel: first.flowDesc ?? (flow === 'import' ? 'Import' : 'Export'),
    totals,
    topPartners: partners,
    records: rows.length,
    warnings,
    source: {
      name: 'UN Comtrade',
      url: 'https://comtradeplus.un.org',
      note: 'Valores en dólares estadounidenses (USD) declarados por el país informante. Estadística oficial, no cotizaciones.',
    },
  };
}

function mapComtradeError(status, _text, headers) {
  if (status === 401 || status === 403) return Errors.upstreamAuth('UN Comtrade');
  if (status === 429) {
    const retry = Number.parseInt(headers?.get?.('retry-after') ?? '', 10);
    return Errors.upstreamQuota('UN Comtrade', Number.isFinite(retry) ? retry : undefined);
  }
  if (status === 414) return Errors.badRequest('La consulta a UN Comtrade es demasiado larga.');
  return undefined;
}
