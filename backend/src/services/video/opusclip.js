// Clips de video con OpusClip y publicación en las redes conectadas a OpusClip (YouTube incluido).
// Contrato tomado del conector `@mindstone/mcp-server-opus-video-clip` 0.2.1 (npm), que usa la API REST
// de OpusClip; la documentación oficial (help.opus.pro) no fue accesible desde el entorno (ver ADR-017):
//   Base https://api.opus.pro · Authorization: Bearer <clave de app.opus.pro/settings/integration-tokens>
//   POST /api/clip-projects {videoUrl, curationPref, importPref, renderPref} → {id, stage}
//   GET  /api/clip-projects/{id} → {id, stage: PENDING…COMPLETE | STALLED, error}
//   GET  /api/exportable-clips?q=findByProjectId&projectId= → clips {id "{projectId}.{curationId}", title,
//        uriForPreview, uriForExport}
//   GET  /api/social-accounts?q=mine → {data: [{postAccountId, subAccountId, platform, extUserName}]}
//   POST /api/post-tasks | /api/publish-schedules {projectId, clipId (curationId), postAccountId,
//        postDetail {title, custom {description, privacy}}, publishAt?}
// YouTube lo conecta el usuario dentro de OpusClip: JARVIS nunca ve credenciales de Google.
import { AppError, Errors } from '../../http/errors.js';

const DONE = 'COMPLETE';
const FAILED = new Set(['STALLED', 'FAILED', 'ERROR']);

export class OpusClipService {
  constructor({ config, http, usage }) {
    this.cfg = config.clips;
    this.http = http;
    this.usage = usage;
  }

  get enabled() {
    return Boolean(this.cfg.opusKey);
  }

  async call(path, { method = 'GET', body } = {}) {
    if (!this.enabled) throw Errors.notConfigured('clips de video (OPUSCLIP_API_KEY)');
    const res = await this.http.request('OpusClip', `${this.cfg.opusBaseUrl}${path}`, {
      method,
      headers: { authorization: `Bearer ${this.cfg.opusKey}`, ...(body ? { 'content-type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      mapError: mapOpusError,
      timeout: 30_000,
    });
    return res.text.trim() ? res.json() : {};
  }

  /** Crea un proyecto de recorte a partir de un video público (YouTube, Drive, Vimeo, MP4…). */
  async createProject({ videoUrl, prompt, language, aspect = 'portrait', title }) {
    if (this.usage.clipsToday('projects') >= this.cfg.maxProjectsPerDay) {
      throw Errors.rateLimited('Límite diario de proyectos de clips alcanzado (control de créditos de OpusClip).', 3600);
    }
    const body = {
      videoUrl,
      curationPref: prompt ? { model: 'ClipAnything', customPrompt: prompt } : { model: 'ClipAnything' },
      importPref: { sourceLang: language || 'auto' },
      renderPref: { layoutAspectRatio: aspect },
      ...(title ? { uploadedVideoAttr: { title } } : {}),
    };
    this.usage.recordClips('projects');
    const r = await this.call('/api/clip-projects', { method: 'POST', body });
    const projectId = r.id ?? r.projectId ?? r.data?.id ?? null;
    if (!projectId) throw Errors.upstream('OpusClip', new Error('respuesta sin id de proyecto'));
    return { projectId, stage: r.stage ?? r.data?.stage ?? 'PENDING' };
  }

  /** Estado del proyecto y, si terminó, sus clips. */
  async project(projectId) {
    const p = await this.call(`/api/clip-projects/${encodeURIComponent(projectId)}`);
    const stage = String(p.stage ?? p.data?.stage ?? 'UNKNOWN').toUpperCase();
    const out = { projectId, stage, done: stage === DONE, failed: FAILED.has(stage) || Boolean(p.error), error: p.error ? String(p.error).slice(0, 300) : null, clips: [] };
    if (out.done) out.clips = await this.clips(projectId);
    return out;
  }

  async clips(projectId) {
    const q = new URLSearchParams({ q: 'findByProjectId', projectId, pageNum: '1', pageSize: '50' });
    const raw = await this.call(`/api/exportable-clips?${q}`);
    const list = Array.isArray(raw) ? raw : (raw.data ?? raw.list ?? []);
    return list.map((c, i) => normalizeClip(c, i, projectId));
  }

  async accounts() {
    const r = await this.call('/api/social-accounts?q=mine');
    return (r.data ?? []).map((a) => ({
      postAccountId: a.postAccountId ?? null,
      subAccountId: a.subAccountId ?? null,
      platform: String(a.platform ?? ''),
      name: a.extUserName ?? null,
    }));
  }

  /** Publica (o programa) un clip en una cuenta de YouTube conectada a OpusClip. */
  async publish({ projectId, curationId, postAccountId, title, description, privacy = 'private', publishAt }) {
    if (this.usage.clipsToday('posts') >= this.cfg.maxPostsPerDay) {
      throw Errors.rateLimited('Límite diario de publicaciones alcanzado.', 3600);
    }
    const accounts = await this.accounts();
    const youtube = accounts.filter((a) => /youtube/i.test(a.platform));
    const account = postAccountId ? accounts.find((a) => a.postAccountId === postAccountId) : youtube.length === 1 ? youtube[0] : null;
    if (!account) {
      if (!youtube.length) {
        throw new AppError(409, 'no_youtube_account', 'No hay ninguna cuenta de YouTube conectada en OpusClip. Conéctala en OpusClip (Social accounts) y vuelve a intentarlo.');
      }
      throw new AppError(409, 'choose_account', `Hay ${youtube.length} cuentas de YouTube conectadas; indica cuál: ${youtube.map((a) => a.name ?? a.postAccountId).join(', ')}.`);
    }
    if (!/youtube/i.test(account.platform)) throw Errors.badRequest('Esa cuenta no es de YouTube.');
    const body = {
      projectId,
      clipId: curationId,
      postAccountId: account.postAccountId,
      ...(account.subAccountId ? { subAccountId: account.subAccountId } : {}),
      postDetail: { title, mediaType: 'video', custom: { ...(description ? { description } : {}), privacy } },
      ...(publishAt ? { publishAt } : {}),
    };
    this.usage.recordClips('posts');
    const r = await this.call(publishAt ? '/api/publish-schedules' : '/api/post-tasks', { method: 'POST', body });
    return {
      scheduled: Boolean(publishAt),
      postId: r.data?.postId ?? null,
      scheduleId: r.data?.scheduleId ?? null,
      publishAt: publishAt ?? null,
      privacy,
      account: account.name ?? account.postAccountId,
      note: 'OpusClip sube el clip a YouTube en segundo plano; puede tardar unos minutos en aparecer en tu canal.',
    };
  }
}

function num(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

export function normalizeClip(c, index, projectId) {
  const id = String(c.id ?? c.clipId ?? '');
  const curationId = c.curationId ?? (id.includes('.') ? id.split('.').pop() : id) ?? null;
  const durationMs = num(c.durationMs) ?? (num(c.duration) != null ? c.duration * 1000 : null);
  return {
    number: index + 1,
    clipId: id || null,
    curationId,
    projectId,
    title: typeof c.title === 'string' ? c.title.slice(0, 200) : null,
    durationSec: durationMs != null ? Math.round(durationMs / 1000) : null,
    score: num(c.score) ?? num(c.viralityScore) ?? null,
    previewUrl: c.uriForPreview ?? null,
    exportUrl: c.uriForExport ?? null,
  };
}

function mapOpusError(status) {
  if (status === 401) return Errors.upstreamAuth('OpusClip');
  if (status === 403) {
    return new AppError(502, 'upstream_forbidden', 'OpusClip rechazó la operación: revisa que tu plan incluya la API (Pro, Max o Business) y que te queden créditos.');
  }
  if (status === 404) return Errors.notFound('OpusClip no encontró ese proyecto o clip.');
  if (status === 422) return Errors.badRequest('OpusClip rechazó los datos (¿el enlace del video es público y válido?).');
  if (status === 429) return Errors.upstreamQuota('OpusClip');
  return undefined;
}
