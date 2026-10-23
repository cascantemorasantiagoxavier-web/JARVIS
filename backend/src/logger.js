// Logger JSON mínimo con redacción. No registra cuerpos de peticiones,
// mensajes del usuario, audio ni credenciales: solo metadatos operativos.
const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

const SENSITIVE_KEY = /(authorization|api[-_]?key|token|secret|password|cookie|sdp|instructions|input|message|content|query|text)/i;
const SECRET_VALUE = /\b(sk-[A-Za-z0-9_-]{8,}|ek_[A-Za-z0-9_-]{6,}|Bearer\s+[A-Za-z0-9._-]{8,})/g;

export function redact(value, depth = 0) {
  if (depth > 4) return '[…]';
  if (typeof value === 'string') return value.replace(SECRET_VALUE, '[REDACTED]');
  if (Array.isArray(value)) return value.slice(0, 20).map((v) => redact(v, depth + 1));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = SENSITIVE_KEY.test(k) ? '[REDACTED]' : redact(v, depth + 1);
    }
    return out;
  }
  return value;
}

export function createLogger(level = 'info', sink = (line) => process.stdout.write(line + '\n')) {
  const min = LEVELS[level] ?? LEVELS.info;
  const log = (lvl, msg, fields) => {
    if (LEVELS[lvl] < min) return;
    const entry = { t: new Date().toISOString(), lvl, msg, ...(fields ? redact(fields) : {}) };
    sink(JSON.stringify(entry));
  };
  return {
    debug: (m, f) => log('debug', m, f),
    info: (m, f) => log('info', m, f),
    warn: (m, f) => log('warn', m, f),
    error: (m, f) => log('error', m, f),
  };
}

export const silentLogger = { debug() {}, info() {}, warn() {}, error() {} };
