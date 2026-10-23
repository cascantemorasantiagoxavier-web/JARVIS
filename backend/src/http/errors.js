// Errores de aplicación con códigos estables. Al cliente solo se devuelve
// { error: { code, message } }; los detalles internos quedan en el log.
export class AppError extends Error {
  constructor(status, code, message, { cause, retryAfterSeconds } = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds;
    if (cause) this.cause = cause;
  }
}

export const Errors = {
  badRequest: (message, cause) => new AppError(400, 'bad_request', message, { cause }),
  unauthorized: () => new AppError(401, 'unauthorized', 'Credencial del cliente inválida o ausente.'),
  notFound: (message = 'Recurso no encontrado.') => new AppError(404, 'not_found', message),
  notConfigured: (feature) =>
    new AppError(503, 'not_configured', `La función "${feature}" no está configurada en el servidor.`),
  rateLimited: (message, retryAfterSeconds) =>
    new AppError(429, 'rate_limited', message, { retryAfterSeconds }),
  upstreamAuth: (provider) =>
    new AppError(502, 'upstream_auth', `El proveedor ${provider} rechazó la credencial del servidor.`),
  upstreamQuota: (provider, retryAfterSeconds) =>
    new AppError(429, 'upstream_quota', `El proveedor ${provider} informó límite de uso o cuota agotada.`, {
      retryAfterSeconds,
    }),
  upstream: (provider, cause) =>
    new AppError(502, 'upstream_error', `El proveedor ${provider} no respondió correctamente.`, { cause }),
  timeout: (provider) => new AppError(504, 'upstream_timeout', `Tiempo de espera agotado con ${provider}.`),
  unreachable: (provider, cause) =>
    new AppError(502, 'upstream_unreachable', `No se pudo conectar con ${provider}.`, { cause }),
};

/** Traduce un status HTTP de un proveedor externo a un AppError. */
export function fromUpstreamStatus(provider, status, retryAfterHeader) {
  const retry = Number.parseInt(retryAfterHeader ?? '', 10);
  if (status === 401 || status === 403) return Errors.upstreamAuth(provider);
  if (status === 429) return Errors.upstreamQuota(provider, Number.isFinite(retry) ? retry : undefined);
  if (status === 404) return Errors.notFound(`${provider} no encontró el recurso.`);
  if (status === 400 || status === 422) return Errors.upstream(provider, new Error(`HTTP ${status}`));
  return Errors.upstream(provider, new Error(`HTTP ${status}`));
}

export function errorMiddleware(logger) {
  // eslint-disable-next-line no-unused-vars
  return (err, req, res, _next) => {
    if (err?.type === 'entity.too.large') {
      err = new AppError(413, 'payload_too_large', 'La petición es demasiado grande.');
    } else if (err?.type === 'entity.parse.failed') {
      err = Errors.badRequest('El cuerpo JSON no es válido.');
    }
    const appErr = err instanceof AppError ? err : new AppError(500, 'internal', 'Error interno del servidor.');
    const level = appErr.status >= 500 ? 'error' : 'warn';
    logger[level]('request_failed', {
      route: req.route?.path ?? req.path,
      method: req.method,
      status: appErr.status,
      code: appErr.code,
      cause: err instanceof AppError ? err.cause?.message : err?.message,
    });
    if (appErr.retryAfterSeconds) res.set('Retry-After', String(appErr.retryAfterSeconds));
    res.status(appErr.status).json({ error: { code: appErr.code, message: appErr.message } });
  };
}
