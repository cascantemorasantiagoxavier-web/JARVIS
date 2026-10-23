import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseResponse } from '../src/services/openai/chat.js';
import { fakeFetch, json, OPENAI_KEY, startServer } from './helpers.js';

const TOOLS = [
  {
    name: 'business_search',
    description: 'Busca negocios',
    parameters: { type: 'object', properties: { category: { type: 'string' } }, required: ['category'] },
  },
];

test('chat: envía herramientas como function tools y devuelve tool calls', async () => {
  const f = fakeFetch([
    [
      (u) => u.endsWith('/responses'),
      () =>
        json(200, {
          status: 'completed',
          model: 'gpt-5.4-mini',
          output: [
            { type: 'reasoning', id: 'rs_1', encrypted_content: 'xxx' },
            { type: 'function_call', id: 'fc_1', call_id: 'call_1', name: 'business_search', arguments: '{"category":"supermercados"}' },
          ],
          usage: { input_tokens: 120, output_tokens: 30 },
        }),
    ],
  ]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/chat', {
      instructions: 'Eres JARVIS',
      input: [{ role: 'user', content: 'Busca supermercados en San José' }],
      tools: TOOLS,
      mode: 'BALANCED',
    });
    assert.equal(r.status, 200);
    assert.equal(r.body.toolCalls.length, 1);
    assert.equal(r.body.toolCalls[0].callId, 'call_1');
    assert.equal(r.body.outputItems.length, 2, 'reasoning + function_call para reenviar');
    const sent = JSON.parse(f.calls[0].init.body);
    assert.equal(sent.store, false);
    assert.equal(sent.model, 'gpt-5.4-mini');
    assert.equal(sent.tools[0].type, 'function');
    assert.equal(sent.reasoning.effort, 'low');
    assert.ok(!r.text.includes(OPENAI_KEY));
  } finally {
    await srv.close();
  }
});

test('chat: modo DEEP sube el esfuerzo de razonamiento y el límite de salida', async () => {
  const f = fakeFetch([[() => true, () => json(200, { output: [] })]]);
  const srv = await startServer({ overrides: { fetchImpl: f } });
  try {
    await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'investiga' }], mode: 'DEEP' });
    const sent = JSON.parse(f.calls[0].init.body);
    assert.equal(sent.reasoning.effort, 'medium');
    assert.equal(sent.max_output_tokens, 4000);
  } finally {
    await srv.close();
  }
});

test('chat: esfuerzo "off" no envía parámetro reasoning', async () => {
  const f = fakeFetch([[() => true, () => json(200, { output: [] })]]);
  const srv = await startServer({ env: { OPENAI_TEXT_REASONING_EFFORT: 'off' }, overrides: { fetchImpl: f } });
  try {
    await srv.call('POST', '/api/chat', { input: [{ role: 'user', content: 'hola' }] });
    assert.equal(JSON.parse(f.calls[0].init.body).reasoning, undefined);
  } finally {
    await srv.close();
  }
});

test('chat: sin clave → 503; límite diario → 429', async () => {
  const noKey = await startServer({ env: { OPENAI_API_KEY: '' } });
  try {
    const r = await noKey.call('POST', '/api/chat', { input: [{ role: 'user', content: 'hola' }] });
    assert.equal(r.status, 503);
  } finally {
    await noKey.close();
  }
  const f = fakeFetch([[() => true, () => json(200, { output: [] })]]);
  const capped = await startServer({ env: { CHAT_MAX_REQUESTS_PER_DAY: '1' }, overrides: { fetchImpl: f } });
  try {
    assert.equal((await capped.call('POST', '/api/chat', { input: [{ role: 'user', content: 'a' }] })).status, 200);
    assert.equal((await capped.call('POST', '/api/chat', { input: [{ role: 'user', content: 'b' }] })).status, 429);
  } finally {
    await capped.close();
  }
});

test('chat: valida nombres de herramientas', async () => {
  const srv = await startServer();
  try {
    const r = await srv.call('POST', '/api/chat', {
      input: [{ role: 'user', content: 'x' }],
      tools: [{ name: 'rm -rf /', description: 'x', parameters: {} }],
    });
    assert.equal(r.status, 400);
  } finally {
    await srv.close();
  }
});

test('parseResponse: texto, rechazo y estado incompleto', () => {
  const p = parseResponse({
    status: 'incomplete',
    incomplete_details: { reason: 'max_output_tokens' },
    output: [{ type: 'message', content: [{ type: 'output_text', text: 'Hola, jefe.' }, { type: 'refusal', refusal: 'No puedo.' }] }],
  });
  assert.equal(p.text, 'Hola, jefe.\nNo puedo.');
  assert.equal(p.incompleteReason, 'max_output_tokens');
  assert.deepEqual(p.toolCalls, []);
  assert.equal(parseResponse(null).text, '');
});
