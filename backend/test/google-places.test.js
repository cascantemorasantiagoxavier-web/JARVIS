// Verificación de negocios con Google Places API (New). Respuestas SINTÉTICAS con la forma de los protos
// oficiales (places[].id, displayName.text, formattedAddress, location, internationalPhoneNumber,
// websiteUri, googleMapsUri, businessStatus). Nombres y teléfonos ficticios.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { distanceM, matchPlace, nameSimilarity, normalizeUrl } from '../src/services/business/googlePlaces.js';
import { fakeFetch, json, startServer } from './helpers.js';

const KEY = 'google-maps-test-key-0000';
const NOMINATIM = [{ osm_type: 'relation', osm_id: 1104261, lat: '9.93', lon: '-84.08', name: 'Heredia', display_name: 'Heredia, Costa Rica', category: 'boundary', type: 'administrative', addresstype: 'county', importance: 0.5 }];

function stack({ elements, places, status = 200 }) {
  return fakeFetch([
    [(u) => u.includes('places.example'), () => (status === 200 ? json(200, { places }) : json(status, { error: { code: status, message: 'Places API (New) has not been used in project 123 before or it is disabled.', status: 'PERMISSION_DENIED' } }))],
    [(u) => u.includes('/search?'), () => json(200, NOMINATIM)],
    [(u, init) => u.includes('interpreter') && decodeURIComponent(String(init.body)).includes('foreach'), () => json(200, { elements: [] })],
    [(u) => u.includes('interpreter'), () => json(200, { elements })],
  ]);
}

const env = { GOOGLE_MAPS_API_KEY: KEY, GOOGLE_PLACES_BASE_URL: 'https://places.example' };
const ferreteria = { type: 'node', id: 7, lat: 10.0, lon: -84.11, tags: { name: 'Ferretería El Sol', shop: 'hardware' } };
const googleHit = (over = {}) => ({
  id: 'ChIJ-prueba', displayName: { text: 'Ferretería El Sol S.A.', languageCode: 'es' }, formattedAddress: 'Heredia, Costa Rica',
  location: { latitude: 10.0003, longitude: -84.1101 }, internationalPhoneNumber: '+506 2260 1111', websiteUri: 'https://www.ferreelsol.example/',
  googleMapsUri: 'https://maps.google.com/?cid=1', businessStatus: 'OPERATIONAL', ...over,
});

test('Google Places: emparejamiento seguro aporta teléfono con fuente y aviso de uso', async () => {
  const f = stack({ elements: [ferreteria], places: [googleHit()] });
  const srv = await startServer({ env, overrides: { fetchImpl: f, pageFetcher: async () => { throw new Error('sin web'); } } });
  try {
    await srv.call('POST', '/api/business/search', { category: 'ferreterías', location: 'Heredia' });
    const v = await srv.call('POST', '/api/business/osm:node:7/verify');
    assert.equal(v.status, 200, v.text);
    const b = v.body.business;
    assert.equal(b.phone.value, '+506 2260-1111');
    assert.equal(b.phone.status, 'found');
    assert.deepEqual(b.phone.sources, ['google']);
    assert.equal(b.website.value, 'https://www.ferreelsol.example/');
    assert.equal(b.sources.google.name, 'Google Maps');
    assert.match(b.sources.google.license, /No se muestran en el mapa/);
    assert.equal(v.body.report.checked.at(-1).distanceM < 200, true);

    const call = f.calls.find((c) => c.url.includes('places.example'));
    assert.equal(new URL(call.url).pathname, '/v1/places:searchText');
    assert.equal(call.init.headers['X-Goog-Api-Key'], KEY);
    assert.match(call.init.headers['X-Goog-FieldMask'], /places\.internationalPhoneNumber/);
    const body = JSON.parse(call.init.body);
    assert.equal(body.regionCode, 'CR');
    assert.equal(body.locationBias.circle.center.latitude, 10.0);
    assert.ok(body.locationBias.circle.radius <= 50000);
    assert.ok(!v.text.includes(KEY));
    assert.equal((await srv.call('GET', '/health')).body.features.googlePlaces, true);
  } finally {
    await srv.close();
  }
});

test('Google Places: nombre distinto o lejos → solo candidatos; teléfono distinto → conflicto; cerrado → aviso', async () => {
  const far = stack({ elements: [ferreteria], places: [googleHit({ location: { latitude: 10.02, longitude: -84.11 } }), googleHit({ displayName: { text: 'Panadería Luna' } })] });
  let srv = await startServer({ env, overrides: { fetchImpl: far, pageFetcher: async () => { throw new Error('sin web'); } } });
  try {
    await srv.call('POST', '/api/business/search', { category: 'ferreterías', location: 'Heredia' });
    const v = await srv.call('POST', '/api/business/osm:node:7/verify');
    assert.equal(v.body.business.phone.status, 'unavailable', 'no se toma un teléfono de otro negocio');
    assert.equal(v.body.business.sources.google, undefined);
    assert.equal(v.body.report.candidates.filter((c) => c.source === 'Google Maps').length, 2);
  } finally {
    await srv.close();
  }

  const withPhone = { ...ferreteria, tags: { ...ferreteria.tags, phone: '2260 2222' } };
  const conflict = stack({ elements: [withPhone], places: [googleHit({ businessStatus: 'CLOSED_PERMANENTLY' })] });
  srv = await startServer({ env, overrides: { fetchImpl: conflict, pageFetcher: async () => { throw new Error('sin web'); } } });
  try {
    await srv.call('POST', '/api/business/search', { category: 'ferreterías', location: 'Heredia' });
    const v = await srv.call('POST', '/api/business/osm:node:7/verify');
    assert.equal(v.body.business.phone.status, 'conflict');
    assert.equal(v.body.business.phone.value, '+506 2260-2222', 'se conserva el dato de la fuente original');
    assert.deepEqual(v.body.business.phone.alternatives.map((a) => [a.value, a.source]), [['+506 2260-1111', 'google']]);
    assert.ok(v.body.report.warnings.some((w) => /cerró definitivamente/.test(w)));
  } finally {
    await srv.close();
  }
});

test('Google Places: API no habilitada → aviso claro y la verificación sigue; límite diario; sin clave no se llama', async () => {
  const denied = stack({ elements: [ferreteria], places: [], status: 403 });
  let srv = await startServer({ env: { ...env, GOOGLE_PLACES_MAX_REQUESTS_PER_DAY: '1' }, overrides: { fetchImpl: denied, pageFetcher: async () => { throw new Error('sin web'); } } });
  try {
    await srv.call('POST', '/api/business/search', { category: 'ferreterías', location: 'Heredia' });
    const v = await srv.call('POST', '/api/business/osm:node:7/verify');
    assert.equal(v.status, 200);
    assert.ok(v.body.report.warnings.some((w) => /Places API \(New\)/.test(w)));
    const again = await srv.call('POST', '/api/business/osm:node:7/verify');
    assert.ok(again.body.report.warnings.some((w) => /Límite diario/.test(w)));
    assert.equal(denied.calls.filter((c) => c.url.includes('places.example')).length, 1);
  } finally {
    await srv.close();
  }
  const noKey = stack({ elements: [ferreteria], places: [googleHit()] });
  srv = await startServer({ overrides: { fetchImpl: noKey, pageFetcher: async () => { throw new Error('sin web'); } } });
  try {
    await srv.call('POST', '/api/business/search', { category: 'ferreterías', location: 'Heredia' });
    await srv.call('POST', '/api/business/osm:node:7/verify');
    assert.equal(noKey.calls.filter((c) => c.url.includes('places.example')).length, 0);
    assert.equal((await srv.call('GET', '/health')).body.features.googlePlaces, false);
  } finally {
    await srv.close();
  }
});

test('Google Places: utilidades de emparejamiento', () => {
  assert.ok(nameSimilarity('Ferretería El Sol', 'FERRETERIA EL SOL S.A.') >= 0.99);
  assert.ok(nameSimilarity('Ferretería El Sol', 'Panadería Luna') < 0.2);
  assert.ok(Math.abs(distanceM({ lat: 10, lon: -84 }, { lat: 10.001, lon: -84 }) - 111) < 2);
  assert.equal(matchPlace('Ferretería El Sol', null, [{ name: 'Ferretería El Sol', lat: 10, lon: -84 }]), null, 'sin coordenadas OSM no se empareja');
  assert.equal(normalizeUrl('https://www.Demo.example/inicio/'), 'demo.example/inicio');
});
