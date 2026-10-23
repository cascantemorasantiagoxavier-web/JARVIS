// Clips con OpusClip y publicación en YouTube a través de OpusClip. Respuestas SINTÉTICAS con la forma
// que usa el conector @mindstone/mcp-server-opus-video-clip (no hay documentación oficial accesible).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeClip } from '../src/services/video/opusclip.js';
import { fakeFetch, json, OPENAI_KEY, startServer } from './helpers.js';

const KEY = 'opus-test-key-0000';
const env = { OPUSCLIP_API_KEY: KEY, OPUSCLIP_BASE_URL: 'https://opus.example' };
const CLIPS = [
  { id: 'P123.CU1', title: 'El mejor momento', durationMs: 42_000, uriForPreview: 'https://storage.example/p1.mp4', uriForExport: 'https://storage.example/e1.mp4' },
  { id: 'P123.CU2', title: 'Segundo clip', durationMs: 58_500, uriForPreview: 'https://storage.example/p2.mp4' },
];
const ACCOUNTS = { data: [{ postAccountId: 'acc-yt', platform: 'YOUTUBE', extUserName: 'Canal Xavi' }, { postAccountId: 'acc-tt', platform: 'TIKTOK', extUserName: 'xavi' }] };

function opus({ stage = 'COMPLETE', accounts = ACCOUNTS } = {}) {
  return fakeFetch([
    [(u, i) => u.endsWith('/api/clip-projects') && i.method === 'POST', () => json(200, { id: 'P123', stage: 'PENDING' })],
    [(u) => u.includes('/api/clip-projects/P123'), () => json(200, { id: 'P123', stage })],
    [(u) => u.includes('/api/exportable-clips'), () => json(200, CLIPS)],
    [(u) => u.includes('/api/social-accounts'), () => json(200, accounts)],
    [(u) => u.endsWith('/api/post-tasks'), () => json(200, { data: { postId: 'post-9' } })],
    [(u) => u.endsWith('/api/publish-schedules'), () => json(200, { data: { scheduleId: 'sch-1' } })],
  ]);
}

test('clips: crear proyecto con la clave en cabecera y vertical por defecto', async () => {
  const f = opus();
  const srv = await startServer({ env, overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/clips/projects', { videoUrl: 'https://www.youtube.com/watch?v=abc123', prompt: 'momentos graciosos', language: 'es' });
    assert.equal(r.status, 200, r.text);
    assert.deepEqual(r.body, { projectId: 'P123', stage: 'PENDING' });
    const call = f.calls[0];
    assert.equal(call.init.headers.authorization, `Bearer ${KEY}`);
    const body = JSON.parse(call.init.body);
    assert.equal(body.videoUrl, 'https://www.youtube.com/watch?v=abc123');
    assert.equal(body.curationPref.customPrompt, 'momentos graciosos');
    assert.equal(body.importPref.sourceLang, 'es');
    assert.equal(body.renderPref.layoutAspectRatio, 'portrait');
    assert.ok(!r.text.includes(KEY));
    assert.equal((await srv.call('GET', '/health')).body.features.clips, true);
  } finally {
    await srv.close();
  }
});

test('clips: estado en curso sin clips; terminado con clips numerados', async () => {
  let srv = await startServer({ env, overrides: { fetchImpl: opus({ stage: 'RENDER' }) } });
  try {
    const r = await srv.call('GET', '/api/clips/projects/P123');
    assert.equal(r.body.stage, 'RENDER');
    assert.equal(r.body.done, false);
    assert.deepEqual(r.body.clips, []);
  } finally {
    await srv.close();
  }
  srv = await startServer({ env, overrides: { fetchImpl: opus() } });
  try {
    const r = await srv.call('GET', '/api/clips/projects/P123');
    assert.equal(r.body.done, true);
    assert.deepEqual(r.body.clips.map((c) => [c.number, c.curationId, c.durationSec]), [[1, 'CU1', 42], [2, 'CU2', 59]]);
  } finally {
    await srv.close();
  }
});

test('clips: publicar en la única cuenta de YouTube, privado por defecto; programar', async () => {
  const f = opus();
  const srv = await startServer({ env, overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/clips/publish', { projectId: 'P123', curationId: 'CU2', title: 'Mi clip' });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.postId, 'post-9');
    assert.equal(r.body.privacy, 'private');
    assert.equal(r.body.account, 'Canal Xavi');
    const sent = JSON.parse(f.calls.find((c) => c.url.endsWith('/api/post-tasks')).init.body);
    assert.deepEqual(sent, { projectId: 'P123', clipId: 'CU2', postAccountId: 'acc-yt', postDetail: { title: 'Mi clip', mediaType: 'video', custom: { privacy: 'private' } } });

    const when = new Date(Date.now() + 3_600_000).toISOString();
    const s = await srv.call('POST', '/api/clips/publish', { projectId: 'P123', curationId: 'CU1', title: 'Luego', privacy: 'unlisted', publishAt: when });
    assert.equal(s.body.scheduleId, 'sch-1');
    assert.equal(JSON.parse(f.calls.find((c) => c.url.endsWith('/api/publish-schedules')).init.body).publishAt, when);
  } finally {
    await srv.close();
  }
});

test('clips: sin YouTube conectado → 409 claro; fecha pasada → 400; sin clave → 503; enlace inválido → 400', async () => {
  let srv = await startServer({ env, overrides: { fetchImpl: opus({ accounts: { data: [{ postAccountId: 'acc-tt', platform: 'TIKTOK' }] } }) } });
  try {
    const r = await srv.call('POST', '/api/clips/publish', { projectId: 'P123', curationId: 'CU1', title: 'x' });
    assert.equal(r.status, 409);
    assert.equal(r.body.error.code, 'no_youtube_account');
    const past = await srv.call('POST', '/api/clips/publish', { projectId: 'P123', curationId: 'CU1', title: 'x', publishAt: '2020-01-01T00:00:00.000Z' });
    assert.equal(past.status, 400);
    assert.equal((await srv.call('POST', '/api/clips/projects', { videoUrl: 'ftp://x.example/v.mp4' })).status, 400);
  } finally {
    await srv.close();
  }
  srv = await startServer();
  try {
    const r = await srv.call('POST', '/api/clips/projects', { videoUrl: 'https://youtu.be/abc' });
    assert.equal(r.status, 503);
    assert.equal((await srv.call('GET', '/health')).body.features.clips, false);
  } finally {
    await srv.close();
  }
});

test('clips: plan sin API (403) y límite diario de proyectos', async () => {
  const f = fakeFetch([[() => true, () => json(403, { message: 'forbidden' })]]);
  const srv = await startServer({ env: { ...env, OPUSCLIP_MAX_PROJECTS_PER_DAY: '1' }, overrides: { fetchImpl: f } });
  try {
    const r = await srv.call('POST', '/api/clips/projects', { videoUrl: 'https://youtu.be/abc' });
    assert.equal(r.status, 502);
    assert.match(r.body.error.message, /plan/);
    const again = await srv.call('POST', '/api/clips/projects', { videoUrl: 'https://youtu.be/abc' });
    assert.equal(again.status, 429);
    assert.ok(!r.text.includes(OPENAI_KEY));
  } finally {
    await srv.close();
  }
});

test('clips: normalización tolera campos ausentes', () => {
  assert.deepEqual(normalizeClip({ id: 'P9.CU7' }, 0, 'P9'), {
    number: 1, clipId: 'P9.CU7', curationId: 'CU7', projectId: 'P9', title: null, durationSec: null, score: null, previewUrl: null, exportUrl: null,
  });
});
