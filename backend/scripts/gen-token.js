// Genera un token aleatorio para JARVIS_CLIENT_TOKEN (no es la clave de OpenAI).
import { randomBytes } from 'node:crypto';

console.log(randomBytes(32).toString('base64url'));
