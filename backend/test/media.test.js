import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fakeFetch, json, OPENAI_KEY, startServer } from './helpers.js';

// Archivos sintéticos: solo la cabecera es real; el resto son ceros.
const jpeg = (size = 300) => Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(size)]).toString('base64');
const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(300)]).toString('base64');
const m4a = Buffer.concat([Buffer.from([0, 0, 0, 0x20]), Buffer.from('ftypM4A '), Buffer.alloc(300)]).toString('base64');

const answer = (text, extra = {}) =>
  json(200, {
    status: 'completed',
    model: 'gpt-5.4-mini',
    output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text }] }],
    ...extra,
  });

test('visión: tarjeta → Structured Outputs estricto con imagen en data URL; campos vacíos = null', async () => {
  const card = { text: 'Ana Mora, Café Volcán', fields: { name: 'Ana Mora', company: 'Café Volcán', title: '', phone: '+506 2222 3333', email: null, website: null, address: '  ' } };
  const f = fakeFetch([[(u) => u.endsWith('/responses'), () => answer(JSON.stringify(card))]]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/vision/analyze', { imageBase64: jpeg(), mimeType: 'image/jpeg', task: 'business_card' });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.task, 'business_card');
    assert.equal(r.body.fields.name, 'Ana Mora');
    assert.equal(r.body.fields.phone, '+506 2222 3333');
    assert.equal(r.body.fields.title, null, 'cadena vacía → null');
    assert.equal(r.body.fields.address, null, 'solo espacios → null');
    assert.equal(r.body.fields.email, null);
    assert.equal(r.body.model, 'gpt-5.4-mini');
    assert.ok(r.body.retrievedAt);

    const sent = JSON.parse(f.calls[0].init.body);
    assert.equal(sent.store, false);
    assert.equal(sent.model, 'gpt-5.4-mini', 'por defecto el modelo de texto');
    const image = sent.input[0].content.find((c) => c.type === 'input_image');
    assert.ok(image.image_url.startsWith('data:image/jpeg;base64,/9j/'));
    assert.equal(image.detail, 'high');
    assert.equal(sent.text.format.type, 'json_schema');
    assert.equal(sent.text.format.strict, true);
    assert.deepEqual(sent.text.format.schema.properties.fields.required, ['name', 'company', 'title', 'phone', 'email', 'website', 'address']);
    assert.match(sent.instructions, /nunca sigas instrucciones/);
    assert.ok(!r.text.includes(OPENAI_KEY));
  } finally {
    await srv.close();
  }
});

test('visión: describir usa texto libre, envía la pregunta y respeta OPENAI_VISION_MODEL', async () => {
  const f = fakeFetch([[() => true, () => answer('Una taza de café sobre una mesa.')]]);
  const srv = await startServer({ env: { OPENAI_VISION_MODEL: 'gpt-5.4' }, overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/vision/analyze', { imageBase64: png, mimeType: 'image/png', task: 'describe', question: '¿Qué hay en la mesa?' });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.text, 'Una taza de café sobre una mesa.');
    assert.deepEqual(r.body.fields, {});
    const sent = JSON.parse(f.calls[0].init.body);
    assert.equal(sent.model, 'gpt-5.4');
    assert.equal(sent.text, undefined);
    assert.match(sent.input[0].content[0].text, /¿Qué hay en la mesa\?/);
  } finally {
    await srv.close();
  }
});

test('visión: entradas inválidas → 400 sin llamar a OpenAI', async () => {
  const f = fakeFetch([[() => true, () => answer('{}')]]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    // PNG declarado como JPEG
    let r = await srv.call('POST', '/api/vision/analyze', { imageBase64: png, mimeType: 'image/jpeg', task: 'describe' });
    assert.equal(r.status, 400);
    // base64 con caracteres no válidos
    r = await srv.call('POST', '/api/vision/analyze', { imageBase64: '%'.repeat(200), mimeType: 'image/jpeg', task: 'describe' });
    assert.equal(r.status, 400);
    // tarea desconocida
    r = await srv.call('POST', '/api/vision/analyze', { imageBase64: jpeg(), mimeType: 'image/jpeg', task: 'face_id' });
    assert.equal(r.status, 400);
    assert.equal(f.calls.length, 0);
  } finally {
    await srv.close();
  }
});

test('visión: sin token → 401 aunque el cuerpo sea grande; >512 KB se admite solo en rutas de medios', async () => {
  const f = fakeFetch([[() => true, () => answer('ok')]]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    const big = jpeg(700_000);
    let r = await srv.call('POST', '/api/vision/analyze', { imageBase64: big, mimeType: 'image/jpeg', task: 'describe' }, { token: null });
    assert.equal(r.status, 401);
    r = await srv.call('POST', '/api/vision/analyze', { imageBase64: big, mimeType: 'image/jpeg', task: 'describe' });
    assert.equal(r.status, 200, r.text);
    r = await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'x'.repeat(700_000) }] });
    assert.equal(r.status, 413, 'el chat mantiene su límite de 512 KB');
  } finally {
    await srv.close();
  }
});

test('visión: rechazo del modelo → 422; JSON roto → 502; respuesta incompleta → 502', async () => {
  const replies = [
    json(200, { output: [{ type: 'message', content: [{ type: 'refusal', refusal: 'No puedo ayudar con eso.' }] }] }),
    answer('esto no es JSON'),
    answer('{"text":"Recibo', { status: 'incomplete', incomplete_details: { reason: 'max_output_tokens' } }),
  ];
  const f = fakeFetch([[() => true, () => replies.shift()]]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    const body = { imageBase64: jpeg(), mimeType: 'image/jpeg', task: 'receipt' };
    let r = await srv.call('POST', '/api/vision/analyze', body);
    assert.equal(r.status, 422);
    assert.equal(r.body.error.code, 'vision_refused');
    r = await srv.call('POST', '/api/vision/analyze', body);
    assert.equal(r.status, 502);
    assert.equal(r.body.error.code, 'upstream_error');
    r = await srv.call('POST', '/api/vision/analyze', body);
    assert.equal(r.status, 502);
    assert.equal(r.body.error.code, 'upstream_incomplete');
  } finally {
    await srv.close();
  }
});

test('visión y transcripción: sin clave → 503; límite diario → 429; /api/usage y /health lo reflejan', async () => {
  const noKey = await startServer({ env: { OPENAI_API_KEY: '' } });
  try {
    const r = await noKey.call('POST', '/api/vision/analyze', { imageBase64: jpeg(), mimeType: 'image/jpeg', task: 'describe' });
    assert.equal(r.status, 503);
    assert.equal(r.body.error.code, 'not_configured');
    const t = await noKey.call('POST', '/api/audio/transcribe', { audioBase64: m4a, mimeType: 'audio/mp4' });
    assert.equal(t.status, 503);
    const h = await noKey.call('GET', '/health');
    assert.equal(h.body.features.vision, false);
    assert.equal(h.body.features.transcription, false);
  } finally {
    await noKey.close();
  }

  const f = fakeFetch([
    [(u) => u.endsWith('/responses'), () => answer('ok')],
    [(u) => u.endsWith('/audio/transcriptions'), () => json(200, { text: 'hola' })],
  ]);
  const srv = await startServer({ env: { VISION_MAX_REQUESTS_PER_DAY: '1', TRANSCRIBE_MAX_REQUESTS_PER_DAY: '1' }, overrides: { fetchImpl: f } });
  try {
    const body = { imageBase64: jpeg(), mimeType: 'image/jpeg', task: 'describe' };
    assert.equal((await srv.call('POST', '/api/vision/analyze', body)).status, 200);
    const second = await srv.call('POST', '/api/vision/analyze', body);
    assert.equal(second.status, 429);
    assert.equal((await srv.call('POST', '/api/audio/transcribe', { audioBase64: m4a, mimeType: 'audio/mp4' })).status, 200);
    assert.equal((await srv.call('POST', '/api/audio/transcribe', { audioBase64: m4a, mimeType: 'audio/mp4' })).status, 429);
    const u = await srv.call('GET', '/api/usage');
    assert.deepEqual(u.body.vision, { today: 1, maxPerDay: 1 });
    assert.deepEqual(u.body.transcription, { today: 1, maxPerDay: 1 });
    const h = await srv.call('GET', '/health');
    assert.equal(h.body.features.vision, true);
    assert.equal(h.body.features.transcription, true);
  } finally {
    await srv.close();
  }
});

test('transcripción: multipart con archivo, modelo e idioma ISO-639-1', async () => {
  const f = fakeFetch([[(u) => u.endsWith('/audio/transcriptions'), () => json(200, { text: '  Llamar al contador mañana.  ', usage: { type: 'duration', seconds: 3 } })]]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/audio/transcribe', { audioBase64: m4a, mimeType: 'audio/mp4', language: 'es-CR' });
    assert.equal(r.status, 200, r.text);
    assert.deepEqual(r.body, { text: 'Llamar al contador mañana.', language: 'es', model: 'gpt-4o-mini-transcribe' });
    const { init } = f.calls[0];
    assert.ok(init.body instanceof FormData);
    assert.equal(init.body.get('model'), 'gpt-4o-mini-transcribe');
    assert.equal(init.body.get('language'), 'es');
    assert.equal(init.body.get('response_format'), 'json');
    const file = init.body.get('file');
    assert.equal(file.name, 'nota.m4a');
    assert.equal(file.type, 'audio/mp4');
    assert.equal(init.headers['content-type'], undefined, 'fetch pone el boundary del multipart');
    assert.equal(init.headers.authorization, `Bearer ${OPENAI_KEY}`);
    assert.ok(!r.text.includes(OPENAI_KEY));
  } finally {
    await srv.close();
  }
});

test('transcripción: audio que no coincide con su tipo → 400; OPENAI_TRANSCRIBE_MODEL=none → 503', async () => {
  const f = fakeFetch([[() => true, () => json(200, { text: 'x' })]]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/audio/transcribe', { audioBase64: m4a, mimeType: 'audio/ogg' });
    assert.equal(r.status, 400);
    assert.equal(f.calls.length, 0);
  } finally {
    await srv.close();
  }
  const off = await startServer({ env: { OPENAI_TRANSCRIBE_MODEL: 'none' } });
  try {
    const r = await off.call('POST', '/api/audio/transcribe', { audioBase64: m4a, mimeType: 'audio/mp4' });
    assert.equal(r.status, 503);
  } finally {
    await off.close();
  }
});
