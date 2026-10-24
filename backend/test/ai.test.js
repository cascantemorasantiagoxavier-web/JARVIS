// Gemini y Llama como cerebro: mismo formato de /api/chat, /api/vision/analyze y /api/audio/transcribe
// para la web y la app, respaldo entre proveedores y token de voz en vivo. Respuestas simuladas con la
// forma del SDK oficial @google/genai 2.31.0 y de la API Chat Completions.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConfig } from '../src/config.js';
import { geminiSchema, toGeminiContents } from '../src/services/ai/gemini.js';
import { fakeFetch, json, startServer, TOKEN } from './helpers.js';

const GEMINI_KEY = 'test-gemini-key-SECRET-never-leaks-0001';
const LLAMA_KEY = 'test-llama-key-SECRET-never-leaks-0002';
const onlyGemini = { OPENAI_API_KEY: '', GEMINI_API_KEY: GEMINI_KEY };
const geminiAndLlama = { ...onlyGemini, LLAMA_API_KEY: LLAMA_KEY };

const TOOLS = [
  {
    name: 'business_search',
    description: 'Busca negocios',
    parameters: {
      type: 'object',
      properties: { category: { type: 'string', minLength: 2, pattern: '^[a-z]+$' }, limit: { type: 'integer', minimum: 1 } },
      required: ['category'],
      additionalProperties: false,
    },
  },
];

const geminiReply = (parts, extra = {}) => json(200, { candidates: [{ content: { role: 'model', parts }, finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 50, candidatesTokenCount: 10, thoughtsTokenCount: 5 }, modelVersion: 'gemini-test-001', ...extra });
const isGenerate = (u) => /\/v1beta\/models\/[^/]+:generateContent$/.test(u);
const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(300)]).toString('base64');
const wav = Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WAVEfmt '), Buffer.alloc(300)]).toString('base64');

test('config: orden de proveedores, voz y errores', () => {
  const base = { JARVIS_CLIENT_TOKEN: TOKEN };
  assert.deepEqual(loadConfig({ ...base, GEMINI_API_KEY: 'g', LLAMA_API_KEY: 'l' }).ai.order, ['gemini', 'llama']);
  assert.deepEqual(loadConfig({ ...base, GEMINI_API_KEY: 'g', OPENAI_API_KEY: 'o', AI_PROVIDERS: 'openai,gemini' }).ai.order, ['openai', 'gemini']);
  assert.equal(loadConfig({ ...base, GEMINI_API_KEY: 'g' }).ai.voice, 'gemini');
  assert.equal(loadConfig({ ...base, OPENAI_API_KEY: 'o' }).ai.voice, 'openai');
  assert.equal(loadConfig({ ...base, LLAMA_API_KEY: 'l' }).ai.voice, null, 'Llama no tiene voz en vivo');
  assert.equal(loadConfig({ ...base, GEMINI_API_KEY: 'g', VOICE_PROVIDER: 'none' }).ai.voice, null);
  assert.throws(() => loadConfig({ ...base, AI_PROVIDERS: 'gemini,claudio' }), /proveedor desconocido "claudio"/);
  assert.equal(loadConfig({ ...base, GEMINI_MODEL: 'models/gemini-x' }).gemini.model, 'gemini-x');
});

test('gemini: esquemas de herramientas sin palabras que la API no admite', () => {
  const s = geminiSchema(TOOLS[0].parameters);
  assert.deepEqual(s, { type: 'object', properties: { category: { type: 'string' }, limit: { type: 'integer', minimum: 1 } }, required: ['category'] });
  assert.deepEqual(geminiSchema({ const: 'x', oneOf: [{ type: 'string' }] }), { enum: ['x'], anyOf: [{ type: 'string' }] });
});

test('gemini: chat con herramientas de punta a punta (dos rondas, thoughtSignature intacta)', async () => {
  const bodies = [];
  const f = fakeFetch([
    [isGenerate, (u, init) => {
      bodies.push({ url: u, headers: init.headers, body: JSON.parse(init.body) });
      return bodies.length === 1
        ? geminiReply([{ functionCall: { name: 'business_search', args: { category: 'ferreterias', limit: 5 } }, thoughtSignature: 'SIG-123' }])
        : geminiReply([{ text: 'Encontré 3 ferreterías.' }]);
    }],
  ]);
  const srv = await startServer({ env: onlyGemini, overrides: { fetchImpl: f } });
  try {
    const first = await srv.call('POST', '/api/chat', { instructions: 'Eres JARVIS', input: [{ role: 'user', content: 'Busca ferreterías' }], tools: TOOLS });
    assert.equal(first.status, 200, first.text);
    assert.equal(first.body.provider, 'gemini');
    assert.equal(first.body.toolCalls.length, 1);
    const call = first.body.toolCalls[0];
    assert.equal(call.name, 'business_search');
    assert.deepEqual(JSON.parse(call.arguments), { category: 'ferreterias', limit: 5 });
    assert.deepEqual(first.body.usage, { inputTokens: 50, outputTokens: 15 });

    const req1 = bodies[0];
    assert.match(req1.url, /\/v1beta\/models\/gemini-flash-latest:generateContent$/);
    assert.equal(req1.headers['x-goog-api-key'], GEMINI_KEY);
    assert.doesNotMatch(req1.url, /key=/, 'la clave va en cabecera, no en la URL');
    assert.deepEqual(req1.body.systemInstruction, { parts: [{ text: 'Eres JARVIS' }] });
    assert.deepEqual(req1.body.contents, [{ role: 'user', parts: [{ text: 'Busca ferreterías' }] }]);
    const decl = req1.body.tools[0].functionDeclarations[0];
    assert.equal(decl.name, 'business_search');
    assert.equal(decl.parametersJsonSchema.additionalProperties, undefined);
    assert.deepEqual(req1.body.toolConfig, { functionCallingConfig: { mode: 'AUTO' } });

    // La app/web reenvía outputItems + el resultado, como hace con OpenAI.
    const input = [
      { role: 'user', content: 'Busca ferreterías' },
      ...first.body.outputItems,
      { type: 'function_call_output', call_id: call.callId, output: JSON.stringify({ ok: true, data: { total: 3 } }) },
    ];
    const second = await srv.call('POST', '/api/chat', { instructions: 'Eres JARVIS', input, tools: TOOLS });
    assert.equal(second.body.text, 'Encontré 3 ferreterías.');
    const contents = bodies[1].body.contents;
    assert.deepEqual(contents[1], { role: 'model', parts: [{ functionCall: { name: 'business_search', args: { category: 'ferreterias', limit: 5 } }, thoughtSignature: 'SIG-123' }] });
    assert.deepEqual(contents[2], { role: 'user', parts: [{ functionResponse: { name: 'business_search', response: { ok: true, data: { total: 3 } } } }] });
    assert.ok(!srv.responses.some((t) => t.includes(GEMINI_KEY)), 'la clave nunca vuelve al cliente');
  } finally {
    await srv.close();
  }
});

test('gemini: varias llamadas en paralelo se agrupan y el contenido original se reenvía una sola vez', () => {
  const items = [
    { role: 'user', content: 'hola' },
    { type: 'function_call', call_id: 'a', name: 'get_weather', arguments: '{}', provider_data: { gemini: { callId: 'x1', content: { role: 'model', parts: [{ functionCall: { id: 'x1', name: 'get_weather', args: {} } }, { functionCall: { id: 'x2', name: 'get_datetime', args: {} } }] } } } },
    { type: 'function_call', call_id: 'b', name: 'get_datetime', arguments: '{}', provider_data: { gemini: { callId: 'x2', skip: true } } },
    { type: 'function_call_output', call_id: 'a', output: '{"ok":true}' },
    { type: 'function_call_output', call_id: 'b', output: 'texto plano' },
  ];
  const c = toGeminiContents(items);
  assert.equal(c.length, 3);
  assert.equal(c[1].parts.length, 2);
  assert.deepEqual(c[2], {
    role: 'user',
    parts: [
      { functionResponse: { id: 'x1', name: 'get_weather', response: { ok: true } } },
      { functionResponse: { id: 'x2', name: 'get_datetime', response: { output: 'texto plano' } } },
    ],
  });
});

test('gemini: bloqueo por seguridad → mensaje claro; 429 → cuota; 403 → clave/API', async () => {
  let mode = 'blocked';
  const f = fakeFetch([
    [isGenerate, () => {
      if (mode === 'blocked') return json(200, { promptFeedback: { blockReason: 'SAFETY' } });
      if (mode === '429') return json(429, { error: { code: 429, status: 'RESOURCE_EXHAUSTED', message: 'Quota exceeded' } });
      return json(403, { error: { code: 403, status: 'PERMISSION_DENIED', message: 'Generative Language API has not been used' } });
    }],
  ]);
  const srv = await startServer({ env: onlyGemini, overrides: { fetchImpl: f } });
  try {
    const blocked = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'x' }] });
    assert.match(blocked.body.text, /filtros de seguridad/);
    mode = '429';
    const quota = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'x' }] });
    assert.equal(quota.status, 429);
    assert.equal(quota.body.error.code, 'upstream_quota');
    mode = '403';
    const forbidden = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'x' }] });
    assert.equal(forbidden.body.error.code, 'upstream_forbidden');
    assert.match(forbidden.body.error.message, /Google AI Studio/);
  } finally {
    await srv.close();
  }
});

test('respaldo: si Gemini se queda sin cuota responde Llama (modelo elegido de la lista del proveedor)', async () => {
  const llamaBodies = [];
  const f = fakeFetch([
    [isGenerate, () => json(429, { error: { status: 'RESOURCE_EXHAUSTED' } })],
    [(u) => u === 'https://api.groq.com/openai/v1/models', () => json(200, { data: [{ id: 'llama-3.1-8b-instant' }, { id: 'llama-3.3-70b-versatile' }, { id: 'whisper-large-v3' }, { id: 'meta-llama/llama-guard-4-12b' }] })],
    [(u) => u.endsWith('/chat/completions'), (_u, init) => {
      llamaBodies.push({ headers: init.headers, body: JSON.parse(init.body) });
      return llamaBodies.length === 1
        ? json(200, { model: 'llama-3.3-70b-versatile', choices: [{ finish_reason: 'tool_calls', message: { role: 'assistant', content: null, tool_calls: [{ id: 'call_9', type: 'function', function: { name: 'business_search', arguments: '{"category":"sodas"}' } }] } }], usage: { prompt_tokens: 30, completion_tokens: 8 } })
        : json(200, { model: 'llama-3.3-70b-versatile', choices: [{ finish_reason: 'stop', message: { role: 'assistant', content: 'Hay 2 sodas.' } }] });
    }],
  ]);
  const srv = await startServer({ env: geminiAndLlama, overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/chat', { instructions: 'Eres JARVIS', input: [{ role: 'user', content: 'sodas' }], tools: TOOLS });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.provider, 'llama');
    assert.deepEqual(r.body.toolCalls, [{ callId: 'call_9', name: 'business_search', arguments: '{"category":"sodas"}' }]);
    const req = llamaBodies[0];
    assert.equal(req.headers.authorization, `Bearer ${LLAMA_KEY}`);
    assert.equal(req.body.model, 'llama-3.3-70b-versatile', 'elige el Llama grande, no guard ni whisper');
    assert.deepEqual(req.body.messages[0], { role: 'system', content: 'Eres JARVIS' });
    assert.equal(req.body.tools[0].type, 'function');
    assert.equal(req.body.tools[0].function.name, 'business_search');

    const r2 = await srv.call('POST', '/api/chat', {
      instructions: 'Eres JARVIS',
      input: [{ role: 'user', content: 'sodas' }, ...r.body.outputItems, { type: 'function_call_output', call_id: 'call_9', output: '{"ok":true}' }],
      tools: TOOLS,
    });
    assert.equal(r2.body.text, 'Hay 2 sodas.');
    assert.deepEqual(llamaBodies[1].body.messages.slice(2), [
      { role: 'assistant', content: null, tool_calls: [{ id: 'call_9', type: 'function', function: { name: 'business_search', arguments: '{"category":"sodas"}' } }] },
      { role: 'tool', tool_call_id: 'call_9', content: '{"ok":true}' },
    ]);
    const h = await srv.call('GET', '/health', undefined, { token: null });
    assert.deepEqual(h.body.features.ai, { chat: 'gemini', fallback: ['llama'], vision: 'gemini', transcription: 'gemini' });
  } finally {
    await srv.close();
  }
});

test('sin ninguna clave de IA → 503 que menciona Gemini', async () => {
  const srv = await startServer({ env: { OPENAI_API_KEY: '' } });
  try {
    const r = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'hola' }] });
    assert.equal(r.status, 503);
    assert.match(r.body.error.message, /GEMINI_API_KEY/);
    const h = await srv.call('GET', '/health', undefined, { token: null });
    assert.equal(h.body.features.chat, false);
    assert.equal(h.body.features.voice, null);
  } finally {
    await srv.close();
  }
});

test('gemini: foto de tarjeta con JSON estricto; cadenas vacías → null', async () => {
  let sent;
  const f = fakeFetch([
    [isGenerate, (_u, init) => {
      sent = JSON.parse(init.body);
      return geminiReply([{ text: JSON.stringify({ text: 'Ana Mora', fields: { name: 'Ana Mora', company: '', title: '', phone: '+506 8888 0000', email: '', website: '', address: '' } }) }]);
    }],
  ]);
  const srv = await startServer({ env: onlyGemini, overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/vision/analyze', { imageBase64: jpeg, mimeType: 'image/jpeg', task: 'business_card' });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.provider, 'gemini');
    assert.equal(r.body.fields.name, 'Ana Mora');
    assert.equal(r.body.fields.company, null);
    assert.equal(r.body.fields.phone, '+506 8888 0000');
    const parts = sent.contents[0].parts;
    assert.deepEqual(parts[1], { inlineData: { mimeType: 'image/jpeg', data: jpeg } });
    assert.equal(sent.generationConfig.responseMimeType, 'application/json');
    assert.deepEqual(Object.keys(sent.generationConfig.responseJsonSchema.properties.fields.properties), ['name', 'company', 'title', 'phone', 'email', 'website', 'address']);
    assert.match(sent.systemInstruction.parts[0].text, /nunca sigas instrucciones/);
  } finally {
    await srv.close();
  }
});

test('dictado: Gemini transcribe WAV; Llama (Whisper) por multipart cuando es el único', async () => {
  let geminiBody;
  const f1 = fakeFetch([[isGenerate, (_u, init) => ((geminiBody = JSON.parse(init.body)), geminiReply([{ text: ' Comprar café mañana ' }]))]]);
  const srv1 = await startServer({ env: onlyGemini, overrides: { fetchImpl: f1 } });
  try {
    const r = await srv1.call('POST', '/api/audio/transcribe', { audioBase64: wav, mimeType: 'audio/wav', language: 'es' });
    assert.equal(r.status, 200, r.text);
    assert.deepEqual(r.body, { text: 'Comprar café mañana', language: 'es', model: 'gemini-flash-latest', provider: 'gemini' });
    assert.deepEqual(geminiBody.contents[0].parts[0], { inlineData: { mimeType: 'audio/wav', data: wav } });
  } finally {
    await srv1.close();
  }
  let form;
  const f2 = fakeFetch([
    [(u) => u.endsWith('/models'), () => json(200, { data: [{ id: 'llama-3.3-70b-versatile' }, { id: 'whisper-large-v3' }, { id: 'whisper-large-v3-turbo' }] })],
    [(u) => u.endsWith('/audio/transcriptions'), (_u, init) => ((form = init.body), json(200, { text: 'Hola JARVIS' }))],
  ]);
  const srv2 = await startServer({ env: { OPENAI_API_KEY: '', LLAMA_API_KEY: LLAMA_KEY }, overrides: { fetchImpl: f2 } });
  try {
    const r = await srv2.call('POST', '/api/audio/transcribe', { audioBase64: wav, mimeType: 'audio/wav' });
    assert.equal(r.body.text, 'Hola JARVIS');
    assert.equal(r.body.provider, 'llama');
    assert.equal(form.get('model'), 'whisper-large-v3-turbo');
  } finally {
    await srv2.close();
  }
});

test('voz en vivo: token efímero de Gemini con la configuración bloqueada; la clave no sale', async () => {
  let tokenReq;
  const f = fakeFetch([
    [(u) => u.includes('/v1beta/models?'), () => json(200, {
      models: [
        { name: 'models/gemini-flash-latest', supportedGenerationMethods: ['generateContent'] },
        { name: 'models/gemini-live-2.5-flash-preview', supportedGenerationMethods: ['bidiGenerateContent'] },
        { name: 'models/gemini-3.1-flash-native-audio-preview', supportedGenerationMethods: ['bidiGenerateContent', 'countTokens'] },
        { name: 'models/gemini-3.1-flash-lite-native-audio', supportedGenerationMethods: ['bidiGenerateContent'] },
      ],
    })],
    [(u) => u.endsWith('/v1alpha/auth_tokens'), (_u, init) => ((tokenReq = { headers: init.headers, body: JSON.parse(init.body) }), json(200, { name: 'auth_tokens/abc123' }))],
  ]);
  const srv = await startServer({ env: onlyGemini, overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/live/token', { instructions: 'Eres JARVIS por voz', voice: 'Kore', tools: TOOLS });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.token, 'auth_tokens/abc123');
    assert.equal(r.body.model, 'gemini-3.1-flash-native-audio-preview', 'elige un modelo de audio nativo, no lite');
    assert.equal(r.body.wsUrl, 'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained');
    assert.equal(tokenReq.headers['x-goog-api-key'], GEMINI_KEY);
    const b = tokenReq.body;
    assert.equal(b.uses, 1);
    assert.ok(Date.parse(b.expireTime) > Date.parse(b.newSessionExpireTime));
    const setup = b.bidiGenerateContentSetup;
    assert.equal(setup.model, 'models/gemini-3.1-flash-native-audio-preview');
    assert.deepEqual(setup.generationConfig.responseModalities, ['AUDIO']);
    assert.equal(setup.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, 'Kore');
    assert.equal(setup.systemInstruction.parts[0].text, 'Eres JARVIS por voz');
    assert.equal(setup.tools[0].functionDeclarations[0].name, 'business_search');
    assert.deepEqual(setup.inputAudioTranscription, {});
    assert.equal(b.fieldMask, undefined, 'sin fieldMask: toda la configuración queda bloqueada');
    assert.doesNotMatch(r.text, new RegExp(GEMINI_KEY));
    const h = await srv.call('GET', '/health', undefined, { token: null });
    assert.equal(h.body.features.voice, 'gemini');
    assert.equal(h.body.features.realtime, false, 'la voz de OpenAI no está configurada');
    const page = await fetch(`${srv.base}/`);
    assert.match(page.headers.get('content-security-policy'), /connect-src [^;]*wss:\/\/generativelanguage\.googleapis\.com/);
    assert.equal((await srv.call('POST', '/api/live/token', { voice: 'no valida!' })).status, 400);
  } finally {
    await srv.close();
  }
});

test('voz en vivo: sin clave de Gemini → 503; GEMINI_LIVE_MODEL fijo no consulta la lista', async () => {
  const srv = await startServer();
  try {
    assert.equal((await srv.call('POST', '/api/live/token', {})).status, 503);
  } finally {
    await srv.close();
  }
  const f = fakeFetch([[(u) => u.endsWith('/v1alpha/auth_tokens'), () => json(200, { name: 'auth_tokens/x' })]]);
  const srv2 = await startServer({ env: { ...onlyGemini, GEMINI_LIVE_MODEL: 'gemini-mi-modelo-live' }, overrides: { fetchImpl: f } });
  try {
    const r = await srv2.call('POST', '/api/live/token', {});
    assert.equal(r.body.model, 'gemini-mi-modelo-live');
    assert.equal(f.calls.length, 1);
  } finally {
    await srv2.close();
  }
});

test('gemini: 503 se reintenta; si el modelo sigue caído responde el de respaldo; el motivo de Google se muestra', async () => {
  const urls = [];
  let plan = [];
  const f = fakeFetch([
    [isGenerate, (u) => {
      urls.push(u.replace(/.*\/models\//, '').replace(':generateContent', ''));
      const next = plan.shift() ?? 'ok';
      if (next === 'ok') return geminiReply([{ text: 'Hola, jefe.' }]);
      return json(next, { error: { code: next, status: next === 503 ? 'UNAVAILABLE' : 'INVALID_ARGUMENT', message: next === 503 ? 'The model is overloaded. Please try again later.' : 'Invalid value at tools[0].function_declarations[3]' } });
    }],
  ]);
  const srv = await startServer({ env: onlyGemini, overrides: { fetchImpl: f, sleep: async () => {} } });
  try {
    plan = [503, 'ok'];
    let r = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'hola' }] });
    assert.equal(r.status, 200, r.text);
    assert.deepEqual(urls, ['gemini-flash-latest', 'gemini-flash-latest'], 'un reintento con el mismo modelo');

    urls.length = 0;
    plan = [503, 503, 'ok'];
    r = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'hola' }] });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.text, 'Hola, jefe.');
    assert.deepEqual(urls, ['gemini-flash-latest', 'gemini-flash-latest', 'gemini-flash-lite-latest'], 'pasa al modelo de respaldo');

    urls.length = 0;
    plan = [503, 503, 503, 503, 503, 503];
    r = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'hola' }] });
    assert.equal(r.status, 502);
    assert.equal(r.body.error.code, 'upstream_unavailable');
    assert.match(r.body.error.message, /saturado/);
    assert.match(r.body.error.message, /The model is overloaded/);
    assert.equal(urls.length, 6, '3 modelos × 2 intentos');

    urls.length = 0;
    plan = [400];
    r = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'hola' }] });
    assert.equal(r.body.error.code, 'upstream_rejected');
    assert.match(r.body.error.message, /Detalle de Google: «Invalid value at tools\[0\]/);
    assert.equal(urls.length, 1, 'un 400 no se reintenta');
    assert.doesNotMatch(r.text, /test-gemini-key/);
  } finally {
    await srv.close();
  }
});

test('gemini: funciones sin argumentos van sin esquema', async () => {
  const { functionDeclarations } = await import('../src/services/ai/gemini.js');
  const d = functionDeclarations([
    { name: 'get_datetime', description: 'Hora', parameters: { type: 'object', properties: {}, additionalProperties: false } },
    TOOLS[0],
  ]);
  assert.deepEqual(d[0], { name: 'get_datetime', description: 'Hora' });
  assert.ok(d[1].parametersJsonSchema);
});

test('gemini: el historial no puede empezar por una respuesta de JARVIS', () => {
  const c = toGeminiContents([
    { role: 'assistant', content: 'Hola, jefe.' },
    { role: 'user', content: '¿Qué hora es?' },
  ]);
  assert.deepEqual(c, [{ role: 'user', parts: [{ text: '¿Qué hora es?' }] }]);
});

test('diagnóstico /api/ai/check: chat, chat con las herramientas de la web y modelo de voz; errores con el motivo de Google', async () => {
  const bodies = [];
  let fail = false;
  const f = fakeFetch([
    [isGenerate, (_u, init) => {
      bodies.push(JSON.parse(init.body));
      if (fail) return json(400, { error: { code: 400, status: 'INVALID_ARGUMENT', message: 'API key expired. Please renew the API key.' } });
      return bodies.length === 1 ? geminiReply([{ text: 'OK' }]) : geminiReply([{ functionCall: { name: 'get_datetime', args: {} } }]);
    }],
    [(u) => u.includes('/v1beta/models?'), () => json(200, { models: [{ name: 'models/gemini-x-native-audio', supportedGenerationMethods: ['bidiGenerateContent'] }] })],
  ]);
  const srv = await startServer({ env: onlyGemini, overrides: { fetchImpl: f, sleep: async () => {} } });
  try {
    assert.equal((await srv.call('GET', '/api/ai/check', undefined, { token: null })).status, 401, 'requiere el token');
    const r = await srv.call('GET', '/api/ai/check');
    assert.equal(r.status, 200, r.text);
    const by = Object.fromEntries(r.body.checks.map((c) => [c.name, c]));
    assert.equal(by.chat.ok, true);
    assert.equal(by.chat.text, 'OK');
    assert.equal(by.chat.provider, 'gemini');
    assert.equal(by.chat_tools.ok, true);
    assert.deepEqual(by.chat_tools.toolCalls, ['get_datetime']);
    assert.equal(by.voice_model.model, 'gemini-x-native-audio');
    const decls = bodies[1].tools[0].functionDeclarations;
    assert.ok(decls.length >= 20, 'envía las herramientas reales de la web');
    assert.ok(decls.some((d) => d.name === 'business_search' && d.parametersJsonSchema));
    assert.deepEqual(Object.keys(decls.find((d) => d.name === 'get_datetime')), ['name', 'description']);
    fail = true;
    const bad = await srv.call('GET', '/api/ai/check');
    assert.equal(bad.status, 200);
    assert.equal(bad.body.checks[0].ok, false);
    assert.match(bad.body.checks[0].error, /Detalle de Google: «API key expired/);
    assert.doesNotMatch(bad.text, /test-gemini-key/);
  } finally {
    await srv.close();
  }
});

test('gemini: con GEMINI_FALLBACK_MODELS=auto elige Flash disponibles para la clave', async () => {
  const urls = [];
  const f = fakeFetch([
    [(u) => u.includes('/v1beta/models?'), () => json(200, {
      models: [
        { name: 'models/gemini-flash-latest', supportedGenerationMethods: ['generateContent'] },
        { name: 'models/gemini-3.5-flash', supportedGenerationMethods: ['generateContent'] },
        { name: 'models/gemini-3.6-flash-preview', supportedGenerationMethods: ['generateContent'] },
        { name: 'models/gemini-flash-lite-latest', supportedGenerationMethods: ['generateContent'] },
        { name: 'models/gemini-3.8-flash-tts', supportedGenerationMethods: ['generateContent'] },
        { name: 'models/gemini-3.5-pro', supportedGenerationMethods: ['generateContent'] },
      ],
    })],
    [isGenerate, (u) => {
      const m = u.replace(/.*\/models\//, '').replace(':generateContent', '');
      urls.push(m);
      return m === 'gemini-3.5-flash' ? geminiReply([{ text: 'Respaldo OK' }]) : json(503, { error: { status: 'UNAVAILABLE', message: 'overloaded' } });
    }],
  ]);
  const srv = await startServer({ env: onlyGemini, overrides: { fetchImpl: f, sleep: async () => {} } });
  try {
    const r = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'hola' }] });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.text, 'Respaldo OK');
    // Alias -latest primero, luego el Flash estable más nuevo; nunca preview, tts ni pro.
    assert.deepEqual(urls, ['gemini-flash-latest', 'gemini-flash-latest', 'gemini-flash-lite-latest', 'gemini-flash-lite-latest', 'gemini-3.5-flash']);
  } finally {
    await srv.close();
  }
});
