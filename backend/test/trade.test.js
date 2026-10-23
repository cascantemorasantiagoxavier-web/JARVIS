// UN Comtrade con respuestas SINTÉTICAS con la forma del cliente oficial `comtradeapicall` (registros en
// `data` con reporterDesc, partnerCode, partnerDesc, cmdCode, cmdDesc, flowCode, flowDesc, period,
// primaryValue). Las cifras son inventadas para la prueba.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { summarize } from '../src/services/trade/comtrade.js';
import { fakeFetch, json, startServer } from './helpers.js';

const KEY = 'comtrade-test-key-0000';
const row = (period, partnerCode, partnerDesc, primaryValue) => ({
  typeCode: 'C', freqCode: 'A', period, reporterCode: 188, reporterDesc: 'Costa Rica', flowCode: 'X', flowDesc: 'Export',
  partnerCode, partnerDesc, cmdCode: '0901', cmdDesc: 'Coffee, whether or not roasted or decaffeinated', primaryValue, isReported: true,
});
const DATA = [
  row(2024, 0, 'World', 350_000_000.4), row(2024, 842, 'USA', 150_000_000), row(2024, 56, 'Belgium', 60_000_000), row(2024, 276, 'Germany', 40_000_000),
  row(2023, 0, 'World', 320_000_000), row(2023, 842, 'USA', 140_000_000),
];

test('comercio: consulta con clave en cabecera, parámetros oficiales y resumen por año y socio', async () => {
  const f = fakeFetch([[(u) => u.includes('comtradeapi.example'), () => json(200, { elements: undefined, count: DATA.length, data: DATA, error: '' })]]);
  const srv = await startServer({ env: { UN_COMTRADE_API_KEY: KEY, COMTRADE_BASE_URL: 'https://comtradeapi.example' }, overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/trade/stats', { hsCode: '0901', flow: 'export', years: [2023, 2024] });
    assert.equal(r.status, 200, r.text);
    const url = new URL(f.calls[0].url);
    assert.equal(url.pathname, '/data/v1/get/C/A/HS');
    assert.equal(url.searchParams.get('reporterCode'), '188');
    assert.equal(url.searchParams.get('period'), '2024,2023');
    assert.equal(url.searchParams.get('cmdCode'), '0901');
    assert.equal(url.searchParams.get('flowCode'), 'X');
    assert.equal(url.searchParams.get('partnerCode'), null, 'sin partnerCode: todos los socios');
    assert.equal(url.searchParams.get('subscription-key'), null, 'la clave nunca va en la URL');
    assert.equal(f.calls[0].init.headers['Ocp-Apim-Subscription-Key'], KEY);

    assert.deepEqual(r.body.totals.map((t) => [t.year, t.valueUsd, t.basis]), [[2024, 350000000, 'total mundial declarado'], [2023, 320000000, 'total mundial declarado']]);
    assert.deepEqual(r.body.topPartners.map((p) => p.partner), ['USA', 'Belgium', 'Germany'], 'sin la fila World');
    assert.equal(r.body.reporter, 'Costa Rica');
    assert.equal(r.body.source.name, 'UN Comtrade');
    assert.ok(!r.text.includes(KEY));

    // Segunda consulta igual: caché (no gasta cuota)
    const again = await srv.call('POST', '/api/trade/stats', { hsCode: '0901', flow: 'export', years: [2023, 2024] });
    assert.equal(again.body.cached, true);
    assert.equal(f.calls.length, 1);
  } finally {
    await srv.close();
  }
});

test('comercio: sin clave → 503; código SA inválido → 400; límite diario → 429; clave rechazada → 502', async () => {
  const off = await startServer();
  try {
    const r = await off.call('POST', '/api/trade/stats', { hsCode: '0901', flow: 'export' });
    assert.equal(r.status, 503);
    assert.equal(r.body.error.code, 'not_configured');
    assert.equal((await off.call('GET', '/health')).body.features.trade, false);
  } finally {
    await off.close();
  }
  const f = fakeFetch([[() => true, () => json(401, { statusCode: 401, message: 'Access denied due to invalid subscription key.' })]]);
  const srv = await startServer({ env: { UN_COMTRADE_API_KEY: KEY, COMTRADE_MAX_REQUESTS_PER_DAY: '1' }, overrides: { fetchImpl: f } });
  try {
    assert.equal((await srv.call('POST', '/api/trade/stats', { hsCode: '09-01', flow: 'export' })).status, 400);
    assert.equal((await srv.call('POST', '/api/trade/stats', { hsCode: '0901', flow: 'salida' })).status, 400);
    const bad = await srv.call('POST', '/api/trade/stats', { hsCode: '0901', flow: 'import', byPartner: false });
    assert.equal(bad.status, 502);
    assert.equal(bad.body.error.code, 'upstream_auth');
    assert.equal(new URL(f.calls[0].url).searchParams.get('partnerCode'), '0', 'solo total mundial');
    assert.equal(new URL(f.calls[0].url).searchParams.get('flowCode'), 'M');
    const limited = await srv.call('POST', '/api/trade/stats', { hsCode: '0902', flow: 'import' });
    assert.equal(limited.status, 429);
    assert.equal((await srv.call('GET', '/health')).body.features.trade, true);
  } finally {
    await srv.close();
  }
});

test('comercio: resumen sin fila World suma socios y avisa años sin datos', () => {
  const r = summarize([row(2024, 842, 'USA', 10.4), row(2024, 56, 'Belgium', 5)], { hsCode: '0901', flow: 'export', periods: [2024, 2023], reporterCode: 188 });
  assert.deepEqual(r.totals, [{ year: 2024, valueUsd: 15, basis: 'suma de socios' }]);
  assert.match(r.warnings[0], /2023/);
  const empty = summarize([], { hsCode: '9999', flow: 'import', periods: [2024], reporterCode: 188 });
  assert.equal(empty.totals.length, 0);
  assert.ok(empty.warnings.some((w) => /no tiene registros/.test(w)));
});
