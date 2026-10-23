import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHttpClient } from '../src/http/httpClient.js';
import { silentLogger } from '../src/logger.js';
import { RealtimeService } from '../src/services/openai/realtime.js';
import { UsageTracker } from '../src/services/usage.js';
import { fakeFetch, json, makeConfig, OPENAI_KEY, startServer } from './helpers.js';

const SDP_OFFER = 'v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n';
const SDP_ANSWER = 'v=0\r\no=- 9 9 IN IP4 0.0.0.0\r\ns=answer\r\n';

function service(fetchImpl, env = {}, extra = {}) {
  const config = makeConfig(env);
  const http = createHttpClient({ fetchImpl, userAgent: 'test', logger: silentLogger });
  return new RealtimeService({ config, http, logger: silentLogger, usage: new UsageTracker(), ...extra });
}

test('client secret: llama a /v1/realtime/client_secrets con la sesión GA', async () => {
  const f = fakeFetch([
    [(u) => u.endsWith('/realtime/client_secrets'), () => json(200, { value: 'ek_test123', expires_at: 1900000000, session: {} })],
  ]);
  const rt = service(f);
  const out = await rt.createClientSecret({ voice: 'cedar', instructions: 'Eres JARVIS' });
  assert.equal(out.clientSecret, 'ek_test123');
  assert.equal(out.model, 'gpt-realtime-2.1-mini');
  assert.equal(out.callsUrl, 'https://api.openai.com/v1/realtime/calls');
  const call = f.calls[0];
  assert.equal(call.init.method, 'POST');
  assert.equal(call.init.headers.authorization, `Bearer ${OPENAI_KEY}`);
  const body = JSON.parse(call.init.body);
  assert.deepEqual(body.expires_after, { anchor: 'created_at', seconds: 60 });
  assert.equal(body.session.type, 'realtime');
  assert.equal(body.session.audio.output.voice, 'cedar');
  assert.equal(body.session.audio.input.turn_detection.type, 'semantic_vad');
  assert.equal(body.session.audio.input.turn_detection.interrupt_response, true);
  assert.equal(body.session.audio.input.transcription.model, 'gpt-4o-mini-transcribe');
  assert.equal(body.session.instructions, 'Eres JARVIS');
  assert.ok(!JSON.stringify(out).includes(OPENAI_KEY), 'la respuesta al teléfono no contiene la clave permanente');
});

test('modelo no permitido → 400', async () => {
  const rt = service(fakeFetch([]));
  await assert.rejects(rt.createClientSecret({ model: 'gpt-5.5' }), (e) => e.status === 400);
});

test('sin OPENAI_API_KEY → 503 not_configured', async () => {
  const rt = service(fakeFetch([]), { OPENAI_API_KEY: '' });
  await assert.rejects(rt.createClientSecret({}), (e) => e.status === 503 && e.code === 'not_configured');
});

test('errores de OpenAI: 401 → upstream_auth; 429 insufficient_quota → upstream_quota', async () => {
  const rt401 = service(fakeFetch([[() => true, () => json(401, { error: { message: 'Incorrect API key', type: 'invalid_request_error' } })]]));
  await assert.rejects(rt401.createClientSecret({}), (e) => e.status === 502 && e.code === 'upstream_auth');
  const rt429 = service(fakeFetch([[() => true, () => json(429, { error: { code: 'insufficient_quota', type: 'insufficient_quota' } })]]));
  await assert.rejects(rt429.createClientSecret({}), (e) => e.status === 429 && e.code === 'upstream_quota' && /saldo|cuota/.test(e.message));
});

test('flujo unificado: multipart sdp+session y callId desde Location', async () => {
  let seenForm;
  const f = fakeFetch([
    [
      (u) => u.endsWith('/realtime/calls'),
      async (_u, init) => {
        seenForm = init.body;
        return new Response(SDP_ANSWER, { status: 201, headers: { location: '/v1/realtime/calls/rtc_abc123', 'content-type': 'application/sdp' } });
      },
    ],
  ]);
  const timers = [];
  const rt = service(f, {}, { setTimer: (fn, ms) => { timers.push({ fn, ms }); return { unref() {} }; }, clearTimer: () => {} });
  const out = await rt.createCall({ sdp: SDP_OFFER, voice: 'marin', maxSessionMinutes: 5 });
  assert.equal(out.sdp, SDP_ANSWER);
  assert.equal(out.callId, 'rtc_abc123');
  assert.ok(seenForm instanceof FormData);
  assert.equal(await seenForm.get('sdp').text(), SDP_OFFER);
  assert.equal(seenForm.get('sdp').type, 'application/sdp');
  const session = JSON.parse(await seenForm.get('session').text());
  assert.equal(session.type, 'realtime');
  assert.equal(session.audio.output.voice, 'marin');
  assert.equal(f.calls[0].init.headers.accept, 'application/sdp');
  assert.equal(timers[0].ms, 5 * 60_000, 'vigilante armado con la duración máxima');
});

test('vigilante de costos cuelga la llamada al vencer', async () => {
  const f = fakeFetch([
    [(u) => u.endsWith('/realtime/calls'), () => new Response(SDP_ANSWER, { status: 201, headers: { location: '/v1/realtime/calls/rtc_w1' } })],
    [(u) => u.endsWith('/realtime/calls/rtc_w1/hangup'), () => new Response(null, { status: 200 })],
  ]);
  let fire;
  const rt = service(f, {}, { setTimer: (fn) => { fire = fn; return {}; }, clearTimer: () => {} });
  await rt.createCall({ sdp: SDP_OFFER });
  fire();
  await new Promise((r) => setImmediate(r));
  assert.ok(f.calls.some((c) => c.url.endsWith('/rtc_w1/hangup')));
  assert.equal(rt.activeCalls.size, 0);
});

test('oferta SDP inválida → 400 sin llamar a OpenAI', async () => {
  const f = fakeFetch([]);
  await assert.rejects(service(f).createCall({ sdp: 'hola' }), (e) => e.status === 400);
  assert.equal(f.calls.length, 0);
});

test('límites de sesiones por hora/día', async () => {
  const f = fakeFetch([[() => true, () => json(200, { value: 'ek_x', expires_at: 1 })]]);
  const rt = service(f, { REALTIME_MAX_SESSIONS_PER_HOUR: '2' });
  await rt.createClientSecret({});
  await rt.createClientSecret({});
  await assert.rejects(rt.createClientSecret({}), (e) => e.status === 429 && e.code === 'rate_limited');
  assert.equal(f.calls.length, 2);
});

test('HTTP: /api/realtime/session devuelve solo la credencial temporal', async () => {
  const f = fakeFetch([[(u) => u.includes('client_secrets'), () => json(200, { value: 'ek_live', expires_at: 2000000000 })]]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/realtime/session', { voice: 'cedar' });
    assert.equal(r.status, 200);
    assert.equal(r.body.clientSecret, 'ek_live');
    assert.ok(!r.text.includes(OPENAI_KEY));
    const usage = await srv.call('GET', '/api/usage');
    assert.equal(usage.body.realtime.today, 1);
  } finally {
    await srv.close();
  }
});
