import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { OPENAI_KEY, startServer } from './helpers.js';

let srv;
before(async () => {
  srv = await startServer({ overrides: { fetchImpl: async () => { throw new TypeError('sin red en pruebas'); } } });
});
after(() => srv.close());

test('GET /health responde sin autenticación y sin secretos', async () => {
  const r = await srv.call('GET', '/health', null, { token: null });
  assert.equal(r.status, 200);
  assert.equal(r.body.status, 'ok');
  assert.equal(r.body.features.realtime, true);
  assert.equal(r.body.features.business, true);
  assert.ok(!r.text.includes(OPENAI_KEY));
});

test('/api exige token del cliente', async () => {
  const none = await srv.call('POST', '/api/web/search', { query: 'hola' }, { token: null });
  assert.equal(none.status, 401);
  assert.equal(none.body.error.code, 'unauthorized');
  const wrong = await srv.call('POST', '/api/web/search', { query: 'hola' }, { token: 'otro-token-incorrecto-1234567890' });
  assert.equal(wrong.status, 401);
});

test('valida parámetros y devuelve 400 con código estable', async () => {
  const r = await srv.call('POST', '/api/business/search', { category: 'x' });
  assert.equal(r.status, 400);
  assert.equal(r.body.error.code, 'bad_request');
  const r2 = await srv.call('POST', '/api/business/search', { category: 'supermercados' });
  assert.equal(r2.status, 400, 'sin location ni near');
  const r3 = await srv.call('GET', '/api/business/../../etc');
  assert.equal(r3.status, 404);
  const r4 = await srv.call('GET', '/api/business/osm:node:abc');
  assert.equal(r4.status, 400);
});

test('JSON mal formado → 400 sin detalles internos', async () => {
  const r = await srv.call('POST', '/api/chat', null, { raw: '{"input": [' });
  assert.equal(r.status, 400);
  assert.equal(r.body.error.code, 'bad_request');
  assert.ok(!/SyntaxError|at JSON/.test(r.text));
});

test('cuerpo demasiado grande → 413', async () => {
  const r = await srv.call('POST', '/api/chat', null, { raw: JSON.stringify({ input: [{ role: 'user', content: 'x'.repeat(600_000) }] }) });
  assert.equal(r.status, 413);
});

test('ruta inexistente → 404 JSON', async () => {
  const r = await srv.call('GET', '/nada');
  assert.equal(r.status, 404);
  assert.equal(r.body.error.code, 'not_found');
});

test('proveedor inalcanzable → 502 claro, sin filtrar la clave', async () => {
  const r = await srv.call('POST', '/api/realtime/session', {});
  assert.equal(r.status, 502);
  assert.equal(r.body.error.code, 'upstream_unreachable');
  assert.ok(!r.text.includes(OPENAI_KEY));
});

test('cabeceras de seguridad presentes', async () => {
  const r = await srv.call('GET', '/health', null, { token: null });
  assert.equal(r.headers.get('x-powered-by'), null);
  assert.ok(r.headers.get('x-content-type-options'));
});

test('ninguna respuesta de esta suite contiene la clave de OpenAI', () => {
  for (const text of srv.responses) assert.ok(!text.includes(OPENAI_KEY));
});

test('límite de peticiones por minuto → 429', async () => {
  const limited = await startServer({ env: { RATE_LIMIT_PER_MINUTE: '3' } });
  try {
    const codes = [];
    for (let i = 0; i < 5; i++) codes.push((await limited.call('GET', '/health', null, { token: null })).status);
    assert.deepEqual(codes.slice(0, 3), [200, 200, 200]);
    assert.equal(codes[4], 429);
  } finally {
    await limited.close();
  }
});
