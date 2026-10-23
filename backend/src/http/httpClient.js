// Cliente HTTP saliente con timeout, límite de tamaño y mapeo de errores.
// `fetchImpl` es inyectable para que las pruebas no dependan de la red.
import { AppError, Errors, fromUpstreamStatus } from './errors.js';

async function readLimited(res, maxBytes) {
  if (!res.body) return '';
  const reader = res.body.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel().catch(() => {});
      throw new AppError(502, 'upstream_too_large', 'La respuesta del proveedor excede el tamaño permitido.');
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks.map((c) => Buffer.from(c))).toString('utf8');
}

export function createHttpClient({ fetchImpl = globalThis.fetch, timeoutMs = 20000, userAgent, logger }) {
  /**
   * @returns {Promise<{status:number, headers:Headers, text:string, json:()=>any}>}
   */
  async function request(provider, url, opts = {}) {
    const {
      method = 'GET',
      headers = {},
      body,
      timeout = timeoutMs,
      maxBytes = 3_000_000,
      mapError,
      okStatuses,
    } = opts;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    const started = Date.now();
    let res;
    try {
      res = await fetchImpl(url, {
        method,
        headers: { 'user-agent': userAgent, ...headers },
        body,
        signal: controller.signal,
      });
    } catch (err) {
      clearTimeout(timer);
      logger?.warn('upstream_network_error', { provider, ms: Date.now() - started, cause: err?.message });
      if (controller.signal.aborted) throw Errors.timeout(provider);
      throw Errors.unreachable(provider, err);
    }
    let text;
    try {
      text = await readLimited(res, maxBytes);
    } catch (err) {
      if (controller.signal.aborted) throw Errors.timeout(provider);
      throw err instanceof AppError ? err : Errors.upstream(provider, err);
    } finally {
      clearTimeout(timer);
    }
    const ok = okStatuses ? okStatuses.includes(res.status) : res.ok;
    logger?.debug('upstream_response', { provider, status: res.status, ms: Date.now() - started });
    if (!ok) {
      const mapped = mapError?.(res.status, text, res.headers);
      logger?.warn('upstream_error_status', { provider, status: res.status, detail: safeDetail(text) });
      throw mapped ?? fromUpstreamStatus(provider, res.status, res.headers.get('retry-after'));
    }
    return {
      status: res.status,
      headers: res.headers,
      text,
      json() {
        try {
          return JSON.parse(text);
        } catch (err) {
          throw Errors.upstream(provider, err);
        }
      },
    };
  }
  return { request };
}

/** Extrae un mensaje corto del error del proveedor para el log (nunca para el cliente). */
function safeDetail(text) {
  try {
    const j = JSON.parse(text);
    const e = j.error ?? j;
    return [e.type, e.code, typeof e.message === 'string' ? e.message.slice(0, 200) : undefined]
      .filter(Boolean)
      .join(' | ');
  } catch {
    return String(text ?? '').slice(0, 120);
  }
}
