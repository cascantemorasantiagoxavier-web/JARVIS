import { Errors } from './errors.js';

/** Valida req.body (o req.params) con un esquema zod y deja el resultado en req.valid. */
export function validate(schema, source = 'body') {
  return (req, _res, next) => {
    const result = schema.safeParse(req[source] ?? {});
    if (!result.success) {
      const detail = result.error.issues
        .slice(0, 5)
        .map((i) => `${i.path.join('.') || source}: ${i.message}`)
        .join('; ');
      return next(Errors.badRequest(`Parámetros inválidos: ${detail}`));
    }
    req.valid = { ...(req.valid ?? {}), [source]: result.data };
    next();
  };
}
