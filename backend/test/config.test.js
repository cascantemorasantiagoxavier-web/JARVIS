import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ConfigError, loadConfig, userAgent } from '../src/config.js';
import { createLogger, redact } from '../src/logger.js';

test('config: exige JARVIS_CLIENT_TOKEN', () => {
  assert.throws(() => loadConfig({}), ConfigError);
  assert.throws(() => loadConfig({ JARVIS_CLIENT_TOKEN: 'corto' }), /24 caracteres/);
});

test('config: valores por defecto y modelo incluido en la lista permitida', () => {
  const c = loadConfig({ JARVIS_CLIENT_TOKEN: 'x'.repeat(32), OPENAI_REALTIME_MODEL: 'gpt-realtime-2.1' });
  assert.equal(c.port, 8787);
  assert.equal(c.openai.apiKey, undefined);
  assert.equal(c.openai.realtime.voice, 'cedar');
  assert.ok(c.openai.realtime.allowedModels.includes('gpt-realtime-2.1'));
  assert.equal(c.openai.text.model, 'gpt-5.4-mini');
  assert.equal(c.osm.defaultCountry, 'cr');
});

test('config: rechaza valores fuera de rango', () => {
  assert.throws(() => loadConfig({ JARVIS_CLIENT_TOKEN: 'x'.repeat(32), REALTIME_SECRET_TTL_SECONDS: '5' }), ConfigError);
  assert.throws(() => loadConfig({ JARVIS_CLIENT_TOKEN: 'x'.repeat(32), PORT: 'abc' }), ConfigError);
});

test('config: transcripción "none" la desactiva', () => {
  const c = loadConfig({ JARVIS_CLIENT_TOKEN: 'x'.repeat(32), OPENAI_REALTIME_TRANSCRIPTION_MODEL: 'none' });
  assert.equal(c.openai.realtime.transcriptionModel, undefined);
});

test('config: User-Agent identificable con contacto', () => {
  const c = loadConfig({ JARVIS_CLIENT_TOKEN: 'x'.repeat(32), OSM_CONTACT: 'yo@example.org' });
  assert.match(userAgent(c), /^JARVIS-Assistant\/[\d.]+ \(yo@example\.org\)$/);
});

test('logger: redacta claves, tokens y contenido del usuario', () => {
  const lines = [];
  const log = createLogger('debug', (l) => lines.push(l));
  log.info('x', { authorization: 'Bearer abc', detail: 'falló con sk-abcdefghijklmnop', query: 'supermercados', status: 401 });
  const entry = JSON.parse(lines[0]);
  assert.equal(entry.authorization, '[REDACTED]');
  assert.equal(entry.query, '[REDACTED]');
  assert.equal(entry.status, 401);
  assert.ok(!lines[0].includes('sk-abcdefghijklmnop'));
  assert.deepEqual(redact({ nested: { apiKey: 'k' } }), { nested: { apiKey: '[REDACTED]' } });
});
