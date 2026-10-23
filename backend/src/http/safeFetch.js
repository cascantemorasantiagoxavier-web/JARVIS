// Descarga de páginas web arbitrarias con protección SSRF.
// - Solo http/https en puertos 80/443.
// - La IP se valida en el momento de conectar (opción `lookup`), lo que también
//   bloquea DNS rebinding hacia direcciones privadas.
// - Redirecciones manuales (máx. 3) revalidando cada salto.
// - Límite de tamaño y timeout.
// El contenido devuelto es DATO NO CONFIABLE: nunca se interpreta como instrucciones.
import dns from 'node:dns';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { AppError, Errors } from './errors.js';

export function isPrivateAddress(ip) {
  if (net.isIPv4(ip)) {
    const [a, b] = ip.split('.').map(Number);
    return (
      a === 0 ||
      a === 10 ||
      a === 127 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 192 && b === 0) ||
      (a === 198 && (b === 18 || b === 19)) ||
      a >= 224
    );
  }
  if (net.isIPv6(ip)) {
    const lower = ip.toLowerCase();
    const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(lower);
    if (mapped) return isPrivateAddress(mapped[1]);
    return (
      lower === '::' ||
      lower === '::1' ||
      lower.startsWith('fc') ||
      lower.startsWith('fd') ||
      lower.startsWith('fe8') ||
      lower.startsWith('fe9') ||
      lower.startsWith('fea') ||
      lower.startsWith('feb') ||
      lower.startsWith('ff') ||
      lower.startsWith('64:ff9b:') ||
      lower.startsWith('2001:db8')
    );
  }
  return true;
}

export function assertPublicHttpUrl(raw) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw Errors.badRequest('URL inválida.');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw Errors.badRequest('Solo se permiten URLs http(s).');
  if (url.username || url.password) throw Errors.badRequest('La URL no puede incluir credenciales.');
  const port = url.port ? Number(url.port) : url.protocol === 'https:' ? 443 : 80;
  if (port !== 80 && port !== 443) throw Errors.badRequest('Solo se permiten los puertos 80 y 443.');
  const host = url.hostname.replace(/^\[|\]$/g, '');
  if (net.isIP(host) && isPrivateAddress(host)) throw Errors.badRequest('Dirección de destino no permitida.');
  if (/^(localhost|.*\.local|.*\.internal|.*\.localhost)$/i.test(host)) {
    throw Errors.badRequest('Dirección de destino no permitida.');
  }
  return url;
}

function guardedLookup(hostname, options, callback) {
  dns.lookup(hostname, { ...options, all: true }, (err, addresses) => {
    if (err) return callback(err);
    const list = Array.isArray(addresses) ? addresses : [{ address: addresses, family: options.family }];
    const allowed = list.filter((a) => !isPrivateAddress(a.address));
    if (allowed.length === 0) return callback(new Error('blocked_private_address'));
    if (options.all) return callback(null, allowed);
    callback(null, allowed[0].address, allowed[0].family);
  });
}

function requestOnce(url, { timeoutMs, maxBytes, userAgent }) {
  return new Promise((resolve, reject) => {
    const lib = url.protocol === 'https:' ? https : http;
    const req = lib.request(
      url,
      {
        method: 'GET',
        lookup: guardedLookup,
        headers: {
          'user-agent': userAgent,
          accept: 'text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5',
          'accept-language': 'es-CR,es;q=0.9,en;q=0.6',
        },
        timeout: timeoutMs,
      },
      (res) => {
        const status = res.statusCode ?? 0;
        if (status >= 300 && status < 400 && res.headers.location) {
          res.resume();
          return resolve({ redirect: new URL(res.headers.location, url).toString(), status });
        }
        const type = String(res.headers['content-type'] ?? '');
        if (!/text\/html|text\/plain|application\/xhtml/i.test(type)) {
          res.resume();
          return reject(new AppError(415, 'unsupported_content', 'El recurso no es una página de texto.'));
        }
        const chunks = [];
        let total = 0;
        res.on('data', (c) => {
          total += c.length;
          if (total > maxBytes) {
            req.destroy();
            return;
          }
          chunks.push(c);
        });
        res.on('end', () =>
          resolve({ status, contentType: type, body: Buffer.concat(chunks).toString('utf8'), truncated: total > maxBytes }),
        );
        res.on('error', reject);
      },
    );
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (err) => {
      if (err.message === 'blocked_private_address') {
        reject(Errors.badRequest('Dirección de destino no permitida.'));
      } else if (err.message === 'timeout') {
        reject(Errors.timeout('el sitio web'));
      } else {
        reject(Errors.unreachable('el sitio web', err));
      }
    });
    req.end();
  });
}

/** Transporte por defecto: red real con protección SSRF. */
export function createSafePageTransport({ userAgent, timeoutMs = 12000, maxBytes = 1_500_000 }) {
  return async function fetchPage(rawUrl) {
    let current = assertPublicHttpUrl(rawUrl);
    for (let hop = 0; hop <= 3; hop++) {
      const r = await requestOnce(current, { timeoutMs, maxBytes, userAgent });
      if (!r.redirect) {
        if (r.status >= 400) throw new AppError(502, 'page_http_error', `El sitio respondió HTTP ${r.status}.`);
        return { finalUrl: current.toString(), ...r };
      }
      current = assertPublicHttpUrl(r.redirect);
    }
    throw new AppError(502, 'too_many_redirects', 'Demasiadas redirecciones.');
  };
}
