// Cliente del servidor de JARVIS (mismo origen). El token de la app viaja en Authorization;
// la clave de OpenAI y las demás nunca llegan al navegador.
import { settings } from './store.js';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request(method, path, body, { auth = true, timeoutMs = 90_000 } = {}) {
  const headers = { accept: 'application/json' };
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (auth) {
    const token = settings.get().token;
    if (!token) throw new ApiError(401, 'no_token', 'Falta el token del servidor: ponlo en Ajustes.');
    headers.authorization = `Bearer ${token}`;
  }
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: ctrl.signal });
  } catch (err) {
    throw new ApiError(0, ctrl.signal.aborted ? 'timeout' : 'offline', ctrl.signal.aborted ? 'El servidor tardó demasiado en responder.' : 'Sin conexión con el servidor de JARVIS.');
  } finally {
    clearTimeout(timer);
  }
  let data = null;
  const text = await res.text();
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!res.ok) {
    const e = data?.error ?? {};
    const msg = res.status === 401 ? 'El servidor rechazó el token: revísalo en Ajustes.' : e.message ?? `Error del servidor (${res.status}).`;
    throw new ApiError(res.status, e.code ?? `http_${res.status}`, msg);
  }
  return data;
}

export const api = {
  health: () => request('GET', '/health', undefined, { auth: false }),
  usage: () => request('GET', '/api/usage'),
  chat: (body) => request('POST', '/api/chat', body, { timeoutMs: 130_000 }),
  webSearch: (body) => request('POST', '/api/web/search', body),
  fetchPage: (body) => request('POST', '/api/web/fetch', body),
  businessSearch: (body) => request('POST', '/api/business/search', body),
  business: (id) => request('GET', `/api/business/${encodeURIComponent(id)}`),
  verifyBusiness: (id) => request('POST', `/api/business/${encodeURIComponent(id)}/verify`, {}),
  tradeStats: (body) => request('POST', '/api/trade/stats', body),
  vision: (body) => request('POST', '/api/vision/analyze', body, { timeoutMs: 100_000 }),
  transcribe: (body) => request('POST', '/api/audio/transcribe', body, { timeoutMs: 130_000 }),
  clipsCreate: (body) => request('POST', '/api/clips/projects', body),
  clipsStatus: (id) => request('GET', `/api/clips/projects/${encodeURIComponent(id)}`),
  clipsPublish: (body) => request('POST', '/api/clips/publish', body),
  realtimeCall: (body) => request('POST', '/api/realtime/call', body, { timeoutMs: 30_000 }),
  realtimeHangup: (callId) => request('POST', `/api/realtime/call/${encodeURIComponent(callId)}/hangup`, {}),
};

/** Archivos públicos de la propia web (contrato de herramientas, personalidad, globo). */
export async function asset(path, as = 'json') {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`No se pudo cargar ${path}`);
  return as === 'json' ? res.json() : res.text();
}
