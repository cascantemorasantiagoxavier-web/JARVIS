import { createHash, timingSafeEqual } from 'node:crypto';
import { Errors } from './errors.js';

const digest = (s) => createHash('sha256').update(s, 'utf8').digest();

/**
 * Autenticación de la app con un token compartido (Authorization: Bearer <token>).
 * Se compara en tiempo constante sobre hashes para no filtrar longitud.
 */
export function requireClientToken(expectedToken) {
  const expected = digest(expectedToken);
  return (req, _res, next) => {
    const header = req.get('authorization') ?? '';
    const match = /^Bearer\s+(.+)$/i.exec(header);
    if (!match || !timingSafeEqual(digest(match[1].trim()), expected)) {
      return next(Errors.unauthorized());
    }
    next();
  };
}
