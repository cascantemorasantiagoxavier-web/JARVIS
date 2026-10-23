// Configuración centralizada. Todo valor externo (claves, URLs, modelos, límites)
// entra por variables de entorno y se valida aquí; el resto del código recibe
// el objeto `config` ya tipado y nunca lee process.env directamente.
import { z } from 'zod';

const bool = (def) =>
  z
    .enum(['true', 'false', '1', '0', ''])
    .optional()
    .transform((v) => (v === undefined || v === '' ? def : v === 'true' || v === '1'));

const int = (def, min, max) =>
  z
    .string()
    .optional()
    .transform((v) => (v === undefined || v === '' ? def : Number(v)))
    .pipe(z.number().int().min(min).max(max));

const optionalString = z
  .string()
  .optional()
  .transform((v) => (v && v.trim() !== '' ? v.trim() : undefined));

const EnvSchema = z.object({
  NODE_ENV: z.string().optional().default('development'),
  PORT: int(8787, 1, 65535),
  HOST: z.string().optional().default('0.0.0.0'),
  TRUST_PROXY: bool(false),
  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).optional().default('info'),

  // Token compartido entre la app y este backend. NO es la clave de OpenAI.
  JARVIS_CLIENT_TOKEN: z
    .string({ error: 'JARVIS_CLIENT_TOKEN es obligatorio (genera uno con `npm run gen-token`).' })
    .min(24, 'JARVIS_CLIENT_TOKEN debe tener al menos 24 caracteres.'),

  OPENAI_API_KEY: optionalString,
  OPENAI_BASE_URL: z.string().optional().default('https://api.openai.com/v1'),
  OPENAI_REALTIME_MODEL: z.string().optional().default('gpt-realtime-2.1-mini'),
  OPENAI_REALTIME_ALLOWED_MODELS: z
    .string()
    .optional()
    .default('gpt-realtime-2.1-mini,gpt-realtime-2.1,gpt-realtime-mini,gpt-realtime'),
  OPENAI_REALTIME_VOICE: z.string().optional().default('cedar'),
  OPENAI_REALTIME_TRANSCRIPTION_MODEL: z.string().optional().default('gpt-4o-mini-transcribe'),
  REALTIME_SECRET_TTL_SECONDS: int(60, 10, 7200),
  REALTIME_MAX_SESSIONS_PER_HOUR: int(20, 1, 10000),
  REALTIME_MAX_SESSIONS_PER_DAY: int(60, 1, 100000),
  OPENAI_TEXT_MODEL: z.string().optional().default('gpt-5.4-mini'),
  OPENAI_TEXT_REASONING_EFFORT: z
    .enum(['none', 'minimal', 'low', 'medium', 'high', 'off'])
    .optional()
    .default('low'),
  CHAT_MAX_REQUESTS_PER_DAY: int(500, 1, 1000000),
  // Visión: por defecto el mismo modelo de texto (debe aceptar imágenes).
  OPENAI_VISION_MODEL: optionalString,
  OPENAI_TRANSCRIBE_MODEL: z.string().optional().default('gpt-4o-mini-transcribe'),
  VISION_MAX_REQUESTS_PER_DAY: int(100, 1, 100000),
  TRANSCRIBE_MAX_REQUESTS_PER_DAY: int(100, 1, 100000),

  SEARCH_PROVIDERS: z.string().optional().default('auto'),
  SEARXNG_URL: optionalString,
  BRAVE_API_KEY: optionalString,
  TAVILY_API_KEY: optionalString,
  SEARCH_ENABLE_OPENAI: bool(false),
  WIKIPEDIA_LANG: z.string().optional().default('es'),

  OVERPASS_URL: z.string().optional().default('https://overpass-api.de/api/interpreter'),
  NOMINATIM_URL: z.string().optional().default('https://nominatim.openstreetmap.org'),
  OSM_CONTACT: optionalString,
  BUSINESS_DEFAULT_COUNTRY: z.string().optional().default('cr'),
  BUSINESS_CACHE_TTL_SECONDS: int(3600, 0, 86400 * 7),

  RATE_LIMIT_PER_MINUTE: int(60, 1, 100000),
  OUTBOUND_TIMEOUT_MS: int(20000, 1000, 120000),
});

export class ConfigError extends Error {}

export function loadConfig(env = process.env) {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    const msg = parsed.error.issues.map((i) => `- ${i.path.join('.')}: ${i.message}`).join('\n');
    throw new ConfigError(`Configuración inválida:\n${msg}`);
  }
  const e = parsed.data;
  const allowedModels = e.OPENAI_REALTIME_ALLOWED_MODELS.split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  if (!allowedModels.includes(e.OPENAI_REALTIME_MODEL)) allowedModels.unshift(e.OPENAI_REALTIME_MODEL);

  return Object.freeze({
    env: e.NODE_ENV,
    port: e.PORT,
    host: e.HOST,
    trustProxy: e.TRUST_PROXY,
    logLevel: e.LOG_LEVEL,
    clientToken: e.JARVIS_CLIENT_TOKEN,
    version: '0.1.0',
    openai: Object.freeze({
      apiKey: e.OPENAI_API_KEY,
      baseUrl: e.OPENAI_BASE_URL.replace(/\/+$/, ''),
      realtime: Object.freeze({
        model: e.OPENAI_REALTIME_MODEL,
        allowedModels: Object.freeze(allowedModels),
        voice: e.OPENAI_REALTIME_VOICE,
        transcriptionModel:
          e.OPENAI_REALTIME_TRANSCRIPTION_MODEL === 'none' ? undefined : e.OPENAI_REALTIME_TRANSCRIPTION_MODEL,
        secretTtlSeconds: e.REALTIME_SECRET_TTL_SECONDS,
        maxSessionsPerHour: e.REALTIME_MAX_SESSIONS_PER_HOUR,
        maxSessionsPerDay: e.REALTIME_MAX_SESSIONS_PER_DAY,
      }),
      text: Object.freeze({
        model: e.OPENAI_TEXT_MODEL,
        reasoningEffort: e.OPENAI_TEXT_REASONING_EFFORT === 'off' ? undefined : e.OPENAI_TEXT_REASONING_EFFORT,
        maxRequestsPerDay: e.CHAT_MAX_REQUESTS_PER_DAY,
      }),
      media: Object.freeze({
        visionModel: e.OPENAI_VISION_MODEL ?? e.OPENAI_TEXT_MODEL,
        transcribeModel: e.OPENAI_TRANSCRIBE_MODEL === 'none' ? undefined : e.OPENAI_TRANSCRIBE_MODEL,
        maxVisionPerDay: e.VISION_MAX_REQUESTS_PER_DAY,
        maxTranscriptionsPerDay: e.TRANSCRIBE_MAX_REQUESTS_PER_DAY,
      }),
    }),
    search: Object.freeze({
      providers: e.SEARCH_PROVIDERS,
      searxngUrl: e.SEARXNG_URL,
      braveApiKey: e.BRAVE_API_KEY,
      tavilyApiKey: e.TAVILY_API_KEY,
      enableOpenAI: e.SEARCH_ENABLE_OPENAI,
      wikipediaLang: e.WIKIPEDIA_LANG,
    }),
    osm: Object.freeze({
      overpassUrl: e.OVERPASS_URL,
      nominatimUrl: e.NOMINATIM_URL.replace(/\/+$/, ''),
      contact: e.OSM_CONTACT,
      defaultCountry: e.BUSINESS_DEFAULT_COUNTRY.toLowerCase(),
      cacheTtlSeconds: e.BUSINESS_CACHE_TTL_SECONDS,
    }),
    rateLimitPerMinute: e.RATE_LIMIT_PER_MINUTE,
    outboundTimeoutMs: e.OUTBOUND_TIMEOUT_MS,
  });
}

/** User-Agent identificable, exigido por las políticas de Nominatim/Overpass. */
export function userAgent(config) {
  const contact = config.osm.contact ? ` (${config.osm.contact})` : '';
  return `JARVIS-Assistant/${config.version}${contact}`;
}
