import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { silentLogger } from '../src/logger.js';

export const TOKEN = 'test-client-token-0123456789abcdef';
export const OPENAI_KEY = 'sk-test-SECRET-should-never-leak-1234567890';

export function makeConfig(env = {}) {
  return loadConfig({ JARVIS_CLIENT_TOKEN: TOKEN, OPENAI_API_KEY: OPENAI_KEY, LOG_LEVEL: 'error', ...env });
}

/**
 * fetch falso: `routes` es una lista de [predicado(url, init), manejador(url, init) → Response].
 * Registra cada llamada para poder inspeccionar URL, cabeceras y cuerpo.
 */
export function fakeFetch(routes) {
  const calls = [];
  const fn = async (url, init = {}) => {
    const u = String(url);
    calls.push({ url: u, init });
    for (const [match, handler] of routes) {
      if (match(u, init)) return handler(u, init);
    }
    throw new TypeError(`fetch failed (sin ruta simulada para ${u})`);
  };
  fn.calls = calls;
  return fn;
}

export const json = (status, body, headers = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

export const noThrottle = (fn) => fn();

export async function startServer({ env = {}, overrides = {} } = {}) {
  const config = makeConfig(env);
  const { app, services } = createApp(config, silentLogger, { throttle: noThrottle, ...overrides });
  const server = await new Promise((resolve) => {
    const s = app.listen(0, '127.0.0.1', () => resolve(s));
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  const responses = [];
  const call = async (method, path, body, { token = TOKEN, raw } = {}) => {
    const headers = { 'content-type': 'application/json' };
    if (token) headers.authorization = `Bearer ${token}`;
    const res = await fetch(base + path, { method, headers, body: raw ?? (body ? JSON.stringify(body) : undefined) });
    const text = await res.text();
    responses.push(text);
    let parsed = null;
    try {
      parsed = JSON.parse(text);
    } catch {
      /* no JSON */
    }
    return { status: res.status, body: parsed, text, headers: res.headers };
  };
  const close = () => {
    services.realtime.shutdown();
    return new Promise((r) => server.close(r));
  };
  return { base, call, close, services, config, responses };
}
