// Clientes de Nominatim y Overpass respetando sus políticas de uso:
// User-Agent identificable, como máximo 1 petición/segundo a Nominatim,
// una consulta Overpass a la vez, y caché de resultados.
import { AppError, Errors } from '../../http/errors.js';
import { TtlCache } from '../cache.js';

/** Cola serie con separación mínima entre peticiones. */
export function createThrottle(minIntervalMs, now = () => Date.now(), sleep = (ms) => new Promise((r) => setTimeout(r, ms))) {
  let chain = Promise.resolve();
  let last = -Infinity;
  return (fn) => {
    const run = chain.then(async () => {
      const wait = last + minIntervalMs - now();
      if (wait > 0) await sleep(wait);
      last = now();
      return fn();
    });
    chain = run.catch(() => {});
    return run;
  };
}

export class NominatimClient {
  constructor({ http, baseUrl, throttle, cache = new TtlCache(500, 86_400_000) }) {
    this.http = http;
    this.baseUrl = baseUrl;
    this.throttle = throttle;
    this.cache = cache;
  }

  async search(q, { countryCode, limit = 5 } = {}) {
    const key = `s:${countryCode}:${limit}:${q.toLowerCase()}`;
    const hit = this.cache.get(key);
    if (hit) return hit;
    const url = new URL(`${this.baseUrl}/search`);
    url.searchParams.set('q', q);
    url.searchParams.set('format', 'jsonv2');
    url.searchParams.set('addressdetails', '1');
    url.searchParams.set('limit', String(limit));
    url.searchParams.set('accept-language', 'es');
    if (countryCode) url.searchParams.set('countrycodes', countryCode);
    const res = await this.throttle(() =>
      this.http.request('Nominatim', url.toString(), { headers: { accept: 'application/json' } }),
    );
    const body = res.json();
    if (!Array.isArray(body)) throw Errors.upstream('Nominatim', new Error('respuesta inesperada'));
    this.cache.set(key, body);
    return body;
  }
}

/** Elige el mejor resultado de Nominatim para usar como zona de búsqueda. */
export function pickArea(results) {
  if (!results?.length) return null;
  const rank = (r) => {
    let score = 0;
    if (r.osm_type === 'relation') score += 10;
    if (r.category === 'boundary' || r.class === 'boundary') score += 5;
    if (r.type === 'administrative') score += 3;
    if (['city', 'town', 'village', 'suburb', 'county', 'state'].includes(r.addresstype)) score += 2;
    return score + Number(r.importance ?? 0);
  };
  const best = [...results].sort((a, b) => rank(b) - rank(a))[0];
  const lat = Number(best.lat);
  const lon = Number(best.lon);
  const areaId =
    best.osm_type === 'relation' ? 3_600_000_000 + Number(best.osm_id) : best.osm_type === 'way' ? 2_400_000_000 + Number(best.osm_id) : null;
  return {
    name: best.name || best.display_name?.split(',')[0],
    displayName: best.display_name,
    osmType: best.osm_type,
    osmId: Number(best.osm_id),
    areaId,
    center: Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null,
    addressType: best.addresstype ?? best.type,
  };
}

export class OverpassClient {
  constructor({ http, url, throttle, timeoutSeconds = 50 }) {
    this.http = http;
    this.url = url;
    this.throttle = throttle;
    this.timeoutSeconds = timeoutSeconds;
  }

  async run(query) {
    const res = await this.throttle(() =>
      this.http.request('Overpass', this.url, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' },
        body: new URLSearchParams({ data: query }).toString(),
        timeout: (this.timeoutSeconds + 10) * 1000,
        maxBytes: 15_000_000,
        mapError: (status) => {
          if (status === 429) return new AppError(429, 'upstream_quota', 'Overpass está saturado o limitó la petición; inténtalo en un minuto.', { retryAfterSeconds: 60 });
          if (status === 504) return Errors.timeout('Overpass');
          if (status === 400) return new AppError(502, 'overpass_query', 'Overpass rechazó la consulta.');
          return undefined;
        },
      }),
    );
    const body = res.json();
    if (body.remark && /runtime error|timed out|out of memory/i.test(body.remark)) {
      throw new AppError(504, 'upstream_timeout', 'Overpass no completó la consulta (zona demasiado grande u ocupada).');
    }
    return body.elements ?? [];
  }
}

// ----- Construcción de consultas Overpass QL -----

function scope({ areaId, around }) {
  if (around) return `(around:${Math.round(around.radiusM)},${around.lat},${around.lon})`;
  return `(area.zona)`;
}

/** Fase 1: lista de POIs (sin límites administrativos), ligera y cacheable. */
export function buildPoiQuery({ filters, areaId, around, timeoutSeconds = 50, maxElements = 400 }) {
  const head = `[out:json][timeout:${timeoutSeconds}];`;
  const areaStmt = around ? '' : `area(id:${areaId})->.zona;`;
  const sc = scope({ areaId, around });
  const unions = filters.map((f) => `nwr${f}${sc};`).join('');
  return `${head}${areaStmt}(${unions})->.pois;.pois out center tags ${maxElements};`;
}

/**
 * Fase 2: para una página de resultados, obtiene provincia/cantón/distrito
 * (admin_level 4/6/8 en Costa Rica) que contienen cada elemento. La salida es
 * secuencial: el elemento seguido de sus áreas.
 */
export function buildAdminQuery(ids, timeoutSeconds = 50) {
  const byType = { node: [], way: [], relation: [] };
  for (const id of ids) {
    const m = /^osm:(node|way|relation):(\d+)$/.exec(id);
    if (m) byType[m[1]].push(m[2]);
  }
  const sel = Object.entries(byType)
    .filter(([, v]) => v.length)
    .map(([t, v]) => `${t}(id:${v.join(',')});`)
    .join('');
  if (!sel) return null;
  return (
    `[out:json][timeout:${timeoutSeconds}];(${sel})->.sel;` +
    `foreach.sel->.e(.e out ids;(.e;.e >;)->.en;.en is_in->.ins;` +
    `area.ins["boundary"="administrative"]["admin_level"~"^(4|6|8)$"];out tags;);`
  );
}

export function buildByIdQuery(id, timeoutSeconds = 25) {
  const m = /^osm:(node|way|relation):(\d+)$/.exec(id);
  if (!m) return null;
  return `[out:json][timeout:${timeoutSeconds}];${m[1]}(id:${m[2]});out center tags;`;
}

/** Agrupa la salida secuencial de buildAdminQuery: id → {4,6,8}. */
export function parseAdminOutput(elements) {
  const map = new Map();
  let current = null;
  for (const el of elements) {
    if (el.type === 'area') {
      if (!current) continue;
      const level = el.tags?.admin_level;
      const name = el.tags?.['name:es'] ?? el.tags?.name;
      if (level && name && !current[level]) current[level] = name;
    } else {
      current = {};
      map.set(`osm:${el.type}:${el.id}`, current);
    }
  }
  return map;
}
