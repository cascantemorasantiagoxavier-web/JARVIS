// Pruebas contra servicios REALES. Requieren red y se activan con JARVIS_LIVE_TESTS=1
// (npm run test:live). Con OPENAI_API_KEY real en .env se prueba también Realtime.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServices } from '../../src/app.js';
import { loadConfig } from '../../src/config.js';
import { silentLogger } from '../../src/logger.js';

const enabled = process.env.JARVIS_LIVE_TESTS === '1';
const config = loadConfig({
  ...process.env,
  JARVIS_CLIENT_TOKEN: process.env.JARVIS_CLIENT_TOKEN || 'live-test-token-0123456789abcdef',
  OSM_CONTACT: process.env.OSM_CONTACT || 'jarvis-live-tests',
});
const services = createServices(config, silentLogger);

test('EN VIVO: "Busca supermercados en San José, Costa Rica" con OpenStreetMap', { skip: !enabled, timeout: 120000 }, async () => {
  const r = await services.business.search({ category: 'supermercados', location: 'San José', limit: 10 });
  console.log(`  total=${r.total} area=${r.area?.displayName} warnings=${r.warnings.join(' | ')}`);
  assert.ok(r.total > 0, 'la fuente real debe devolver supermercados');
  for (const b of r.results) {
    assert.ok(b.sources.osm.url.startsWith('https://www.openstreetmap.org/'));
    for (const k of ['phone', 'website', 'email', 'address']) {
      if (b[k].value) assert.ok(b[k].sources.length > 0, `${k} con valor debe tener fuente`);
      else assert.equal(b[k].status, 'unavailable');
    }
    console.log(`  - ${b.name.value ?? '(sin nombre)'} | ${b.category.value} | tel: ${b.phone.value ?? 'No disponible'} | ${b.canton.value ?? '?'}, ${b.province.value ?? '?'}`);
  }
});

test('EN VIVO: búsqueda web con el primer proveedor configurado', { skip: !enabled, timeout: 60000 }, async () => {
  const r = await services.webSearch.search({ query: 'requisitos importación Costa Rica Hacienda', maxResults: 5 });
  console.log(`  proveedor=${r.provider} resultados=${r.results.length}`);
  assert.ok(r.results.length > 0);
  for (const x of r.results) assert.match(x.url, /^https?:\/\//);
});

test('EN VIVO: OpenAI Realtime client secret', { skip: !enabled || !config.openai.apiKey, timeout: 30000 }, async () => {
  const s = await services.realtime.createClientSecret({});
  assert.match(s.clientSecret, /^ek_/);
  console.log(`  modelo=${s.model} voz=${s.voice} expira=${s.expiresAt}`);
});

test('EN VIVO: clave de OpenAI inválida → upstream_auth', { skip: !enabled, timeout: 30000 }, async () => {
  const bad = createServices(loadConfig({ JARVIS_CLIENT_TOKEN: 'x'.repeat(32), OPENAI_API_KEY: 'sk-invalid-key-for-test' }), silentLogger);
  await assert.rejects(bad.realtime.createClientSecret({}), (e) => e.code === 'upstream_auth');
});
