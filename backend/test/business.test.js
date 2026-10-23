// Pruebas de búsqueda de negocios con respuestas SINTÉTICAS con la forma de
// Nominatim (jsonv2) y Overpass (out:json). Los nombres son ficticios a propósito.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHttpClient } from '../src/http/httpClient.js';
import { silentLogger } from '../src/logger.js';
import { BusinessSearchService, crossCheck } from '../src/services/business/businessService.js';
import { labelFromTags, resolveCategory } from '../src/services/business/categories.js';
import {
  buildAdminQuery,
  buildPoiQuery,
  createThrottle,
  NominatimClient,
  OverpassClient,
  parseAdminOutput,
  pickArea,
} from '../src/services/business/osmClients.js';
import { dedupeBusinesses, distanceMeters, normalizeElement } from '../src/services/business/normalize.js';
import { fakeFetch, json, makeConfig, noThrottle, startServer } from './helpers.js';

const AT = '2026-10-23T12:00:00.000Z';

test('categorías: español y términos de Costa Rica', () => {
  assert.equal(resolveCategory('supermercados').id, 'supermarket');
  assert.equal(resolveCategory('Busca ferreterías').id, 'hardware');
  assert.equal(resolveCategory('pulperías').id, 'convenience');
  assert.equal(resolveCategory('distribuidores de alimentos').id, 'wholesale');
  assert.equal(resolveCategory('importadores de productos electrónicos').id, 'importer', 'gana el término más específico');
  assert.equal(resolveCategory('importadores').coverage, 'low');
  const byName = resolveCategory('Walmart');
  assert.equal(byName.kind, 'name');
  assert.ok(byName.filters.every((f) => f.includes('"name"~"walmart",i')));
  assert.equal(resolveCategory('!!!'), null);
  assert.equal(labelFromTags({ shop: 'supermarket' }), 'Supermercado');
  assert.equal(labelFromTags({ shop: 'ice_cream' }), 'ice cream');
  assert.equal(labelFromTags({ shop: 'electronics', name: 'Importadora Monge' }), 'Tienda de electrónica');
  assert.equal(labelFromTags({ office: 'company', name: 'Importaciones del Valle' }), 'Importador / exportador');
  assert.equal(labelFromTags({ office: 'company', name: 'Servicios ABC' }), 'Empresa / oficina');
});

test('pickArea prefiere límites administrativos y calcula el id de área', () => {
  const area = pickArea([
    { osm_type: 'node', osm_id: 1, lat: '9.93', lon: '-84.08', name: 'San José', importance: 0.6 },
    { osm_type: 'relation', osm_id: 1104261, lat: '9.93', lon: '-84.08', name: 'San José', category: 'boundary', type: 'administrative', addresstype: 'county', importance: 0.5 },
  ]);
  assert.equal(area.osmType, 'relation');
  assert.equal(area.areaId, 3600000000 + 1104261);
  assert.equal(pickArea([]), null);
});

test('consultas Overpass: área, radio y límites administrativos', () => {
  const q = buildPoiQuery({ filters: ['["shop"="supermarket"]'], areaId: 3601104261 });
  assert.match(q, /^\[out:json\]\[timeout:\d+\];area\(id:3601104261\)->\.zona;/);
  assert.match(q, /nwr\["shop"="supermarket"\]\(area\.zona\);/);
  assert.match(q, /out center tags 400;$/);
  const near = buildPoiQuery({ filters: ['["amenity"="pharmacy"]'], around: { lat: 9.9, lon: -84.1, radiusM: 1500 } });
  assert.match(near, /nwr\["amenity"="pharmacy"\]\(around:1500,9\.9,-84\.1\);/);
  assert.ok(!near.includes('area(id'));
  const adm = buildAdminQuery(['osm:node:1', 'osm:way:2', 'malo']);
  assert.match(adm, /node\(id:1\);way\(id:2\);/);
  assert.match(adm, /admin_level"~"\^\(4\|6\|8\)\$"/);
  assert.equal(buildAdminQuery(['x']), null);
});

test('parseAdminOutput agrupa áreas tras cada elemento', () => {
  const map = parseAdminOutput([
    { type: 'node', id: 1 },
    { type: 'area', tags: { admin_level: '4', name: 'San José' } },
    { type: 'area', tags: { admin_level: '6', name: 'San José' } },
    { type: 'area', tags: { admin_level: '8', name: 'Carmen' } },
    { type: 'way', id: 2 },
    { type: 'area', tags: { admin_level: '4', name: 'Heredia' } },
  ]);
  assert.deepEqual(map.get('osm:node:1'), { 4: 'San José', 6: 'San José', 8: 'Carmen' });
  assert.deepEqual(map.get('osm:way:2'), { 4: 'Heredia' });
});

test('normalizeElement: no inventa datos y marca estados', () => {
  const node = normalizeElement({ type: 'node', id: 10, lat: 9.93, lon: -84.08, tags: { name: 'Súper Prueba', shop: 'supermarket', phone: '2222 3344;8888-9999', website: 'superprueba.cr' } }, { retrievedAt: AT });
  assert.equal(node.name.value, 'Súper Prueba');
  assert.equal(node.category.value, 'Supermercado');
  assert.equal(node.phone.value, '+506 2222-3344');
  assert.equal(node.phone.alternatives[0].value, '+506 8888-9999');
  assert.equal(node.website.value, 'https://superprueba.cr/');
  assert.equal(node.email.status, 'unavailable');
  assert.equal(node.email.value, null);
  assert.equal(node.address.status, 'unavailable');
  assert.equal(node.coordinates.status, 'found');
  assert.equal(node.links.osm, 'https://www.openstreetmap.org/node/10');
  assert.equal(node.sources.osm.license.includes('ODbL'), true);

  const way = normalizeElement({ type: 'way', id: 20, center: { lat: 9.9, lon: -84.0 }, tags: { shop: 'supermarket' } }, { retrievedAt: AT });
  assert.equal(way.coordinates.status, 'approximate');
  assert.equal(way.name.status, 'unavailable');

  const noCoords = normalizeElement({ type: 'relation', id: 30, tags: { name: 'X' } }, { retrievedAt: AT });
  assert.equal(noCoords.coordinates.status, 'unavailable');
  assert.equal(noCoords.links.geoUri, null, 'sin coordenadas no hay marcador ni enlace de ubicación');
});

test('deduplicación: fusiona nodo+edificio cercanos, conserva sucursales homónimas', () => {
  const a = normalizeElement({ type: 'node', id: 1, lat: 9.93, lon: -84.08, tags: { name: 'Mega Súper', shop: 'supermarket', phone: '22223344' } }, { retrievedAt: AT });
  const b = normalizeElement({ type: 'way', id: 2, center: { lat: 9.9302, lon: -84.0801 }, tags: { name: 'Mega Super', shop: 'supermarket', phone: '22225566', website: 'mega.cr' } }, { retrievedAt: AT });
  const far = normalizeElement({ type: 'node', id: 3, lat: 9.99, lon: -84.12, tags: { name: 'Mega Súper', shop: 'supermarket' } }, { retrievedAt: AT });
  assert.ok(distanceMeters(a.coordinates.value, b.coordinates.value) < 75);
  const out = dedupeBusinesses([a, b, far]);
  assert.equal(out.length, 2);
  const merged = out.find((x) => x.mergedIds);
  assert.equal(merged.phone.status, 'conflict', 'teléfonos distintos se marcan como contradictorios');
  assert.equal(merged.website.value, 'https://mega.cr/');
  assert.equal(merged.coordinates.status, 'found', 'se prefiere la coordenada exacta del nodo');
});

test('crossCheck: verificado, contradictorio y encontrado en segunda fuente', () => {
  const phone = { value: '+506 2222-3344', status: 'found', sources: ['osm'] };
  assert.equal(crossCheck(phone, ['2222-3344'], 'website', (v) => v.replace(/\D/g, '').slice(-8)).status, 'verified');
  assert.equal(crossCheck(phone, ['2999-0000'], 'website', (v) => v.replace(/\D/g, '').slice(-8)).status, 'conflict');
  const none = { value: null, status: 'unavailable', sources: [] };
  const found = crossCheck(none, ['info@x.cr'], 'website', (v) => v);
  assert.equal(found.status, 'found');
  assert.deepEqual(found.sources, ['website']);
  assert.equal(crossCheck(none, [], 'website', (v) => v), none);
});

function osmFetch({ elements, admin, nominatim }) {
  return fakeFetch([
    [(u) => u.includes('/search?'), () => json(200, nominatim)],
    [
      (u, init) => u.includes('interpreter') && decodeURIComponent(String(init.body)).includes('foreach'),
      () => json(200, { elements: admin }),
    ],
    [(u) => u.includes('interpreter'), () => json(200, { elements })],
  ]);
}

const NOMINATIM_SJ = [
  { osm_type: 'relation', osm_id: 1104261, lat: '9.93', lon: '-84.08', name: 'San José', display_name: 'San José, Costa Rica', category: 'boundary', type: 'administrative', addresstype: 'county', importance: 0.5 },
];

test('servicio: búsqueda paginada con límites administrativos y caché', async () => {
  const elements = Array.from({ length: 25 }, (_, i) => ({ type: 'node', id: 100 + i, lat: 9.93 + i * 0.01, lon: -84.08, tags: { name: `Súper ${String.fromCharCode(65 + i)}`, shop: 'supermarket' } }));
  const admin = [{ type: 'node', id: 100 }, { type: 'area', tags: { admin_level: '4', name: 'San José' } }, { type: 'area', tags: { admin_level: '6', name: 'San José' } }];
  const f = osmFetch({ elements, admin, nominatim: NOMINATIM_SJ });
  const http = createHttpClient({ fetchImpl: f, userAgent: 'JARVIS-Assistant/test', logger: silentLogger });
  const svc = new BusinessSearchService({
    nominatim: new NominatimClient({ http, baseUrl: 'https://nominatim.example', throttle: noThrottle }),
    overpass: new OverpassClient({ http, url: 'https://overpass.example/api/interpreter', throttle: noThrottle }),
    pageFetcher: async () => { throw new Error('no usado'); },
    webSearch: null,
    logger: silentLogger,
    config: makeConfig(),
    now: () => new Date(AT),
  });
  const p1 = await svc.search({ category: 'supermercados', location: 'San José', limit: 20 });
  assert.equal(p1.total, 25);
  assert.equal(p1.results.length, 20);
  assert.equal(p1.nextOffset, 20);
  assert.equal(p1.category.label, 'Supermercado');
  assert.equal(new URL(f.calls[0].url).searchParams.get('q'), 'San José, Costa Rica');
  const withAdmin = p1.results.find((b) => b.id === 'osm:node:100');
  assert.equal(withAdmin.province.value, 'San José');
  assert.equal(withAdmin.district.status, 'unavailable');
  const p2 = await svc.search({ category: 'supermercados', location: 'San José', limit: 20, offset: 20 });
  assert.equal(p2.results.length, 5);
  assert.equal(p2.nextOffset, null);
  const isPoiQuery = (c) => c.url.includes('interpreter') && !decodeURIComponent(String(c.init.body)).includes('foreach');
  assert.equal(f.calls.filter(isPoiQuery).length, 1, 'la segunda página sale de la caché, sin repetir la consulta a Overpass');
  assert.equal(f.calls.filter((c) => c.url.includes('/search?')).length, 1, 'Nominatim también se cachea');
  assert.equal((await svc.getById('osm:node:100')).name.value, 'Súper A');
});

test('servicio: ubicación inexistente → 404 claro', async () => {
  const f = osmFetch({ elements: [], admin: [], nominatim: [] });
  const http = createHttpClient({ fetchImpl: f, userAgent: 't', logger: silentLogger });
  const svc = new BusinessSearchService({
    nominatim: new NominatimClient({ http, baseUrl: 'https://n.example', throttle: noThrottle }),
    overpass: new OverpassClient({ http, url: 'https://o.example', throttle: noThrottle }),
    logger: silentLogger,
    config: makeConfig(),
  });
  await assert.rejects(svc.search({ category: 'ferreterías', location: 'Lugarquenoexiste' }), (e) => e.status === 404 && e.code === 'location_not_found');
});

test('servicio: Overpass saturado (429) → error explícito, sin resultados inventados', async () => {
  const f = fakeFetch([
    [(u) => u.includes('/search?'), () => json(200, NOMINATIM_SJ)],
    [(u) => u.includes('interpreter'), () => new Response('rate limited', { status: 429 })],
  ]);
  const http = createHttpClient({ fetchImpl: f, userAgent: 't', logger: silentLogger });
  const svc = new BusinessSearchService({
    nominatim: new NominatimClient({ http, baseUrl: 'https://n.example', throttle: noThrottle }),
    overpass: new OverpassClient({ http, url: 'https://o.example/interpreter', throttle: noThrottle }),
    logger: silentLogger,
    config: makeConfig(),
  });
  await assert.rejects(svc.search({ category: 'supermercados', location: 'San José' }), (e) => e.status === 429 && /Overpass/.test(e.message));
});

test('verificación con sitio web oficial (transporte simulado)', async () => {
  const elements = [{ type: 'node', id: 7, lat: 9.9, lon: -84.1, tags: { name: 'Ferretería Demo', shop: 'hardware', phone: '2222-3344', website: 'https://demo.example.cr' } }];
  const f = osmFetch({ elements, admin: [], nominatim: NOMINATIM_SJ });
  const srv = await startServer({
    overrides: {
      fetchImpl: f,
      pageFetcher: async (url) => ({ finalUrl: url, status: 200, body: '<p>Tel: 2222 3344</p><a href="mailto:ventas@demo.example.cr">x</a>' }),
    },
  });
  try {
    const s = await srv.call('POST', '/api/business/search', { category: 'ferreterías', location: 'Alajuela' });
    assert.equal(s.status, 200);
    assert.equal(s.body.results[0].phone.status, 'found');
    const v = await srv.call('POST', '/api/business/osm:node:7/verify');
    assert.equal(v.status, 200);
    assert.equal(v.body.business.phone.status, 'verified');
    assert.deepEqual(v.body.business.phone.sources, ['osm', 'website']);
    assert.equal(v.body.business.email.value, 'ventas@demo.example.cr');
    assert.equal(v.body.business.email.status, 'found');
  } finally {
    await srv.close();
  }
});

test('throttle respeta el intervalo mínimo (política de Nominatim: 1 req/s)', async () => {
  let t = 0;
  const waits = [];
  const throttle = createThrottle(1000, () => t, async (ms) => { waits.push(ms); t += ms; });
  await Promise.all([throttle(async () => 1), throttle(async () => 2), throttle(async () => 3)]);
  assert.deepEqual(waits, [1000, 1000]);
});

test('User-Agent identificable en peticiones a OSM', async () => {
  const f = osmFetch({ elements: [], admin: [], nominatim: NOMINATIM_SJ });
  const srv = await startServer({ env: { OSM_CONTACT: 'jefe@example.org' }, overrides: { fetchImpl: f } });
  try {
    await srv.call('POST', '/api/business/search', { category: 'farmacias', location: 'Heredia' });
    assert.match(f.calls[0].init.headers['user-agent'], /JARVIS-Assistant\/.* \(jefe@example\.org\)/);
  } finally {
    await srv.close();
  }
});
