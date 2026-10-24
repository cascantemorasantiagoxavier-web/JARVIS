// Ensamblado de la aplicación Express. Todas las dependencias externas se inyectan,
// lo que permite probar el servidor completo sin red.
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';
import rateLimit from 'express-rate-limit';
import helmet from 'helmet';
import { userAgent } from './config.js';
import { requireClientToken } from './http/auth.js';
import { errorMiddleware, Errors } from './http/errors.js';
import { createHttpClient } from './http/httpClient.js';
import { createSafePageTransport } from './http/safeFetch.js';
import { apiRouter, healthRouter, MEDIA_ROUTES } from './routes/index.js';
import { BusinessSearchService } from './services/business/businessService.js';
import { createThrottle, NominatimClient, OverpassClient } from './services/business/osmClients.js';
import { CompatClient } from './services/ai/compat.js';
import { GeminiClient } from './services/ai/gemini.js';
import { GeminiLiveService } from './services/ai/live.js';
import { ChatService } from './services/openai/chat.js';
import { MediaService } from './services/openai/media.js';
import { GooglePlacesClient } from './services/business/googlePlaces.js';
import { ComtradeService } from './services/trade/comtrade.js';
import { OpusClipService } from './services/video/opusclip.js';
import { RealtimeService } from './services/openai/realtime.js';
import { buildProviders, WebSearchService } from './services/search/webSearchService.js';
import { UsageTracker } from './services/usage.js';

export function createServices(config, logger, overrides = {}) {
  const http =
    overrides.http ??
    createHttpClient({ fetchImpl: overrides.fetchImpl, timeoutMs: config.outboundTimeoutMs, userAgent: userAgent(config), logger });
  const usage = overrides.usage ?? new UsageTracker();
  const realtime = new RealtimeService({ config, http, logger, usage });
  const gemini = new GeminiClient({ config, http, sleep: overrides.sleep });
  const llama = new CompatClient({ config, http });
  const chat = new ChatService({ config, http, logger, usage, gemini, llama });
  const media = new MediaService({ config, http, logger, usage, gemini, llama });
  const live = new GeminiLiveService({ config, gemini, usage, logger });
  const trade = new ComtradeService({ config, http, usage });
  const clips = new OpusClipService({ config, http, usage });
  const googlePlaces = config.google.mapsKey ? new GooglePlacesClient({ config, http, usage }) : null;
  const webSearch = new WebSearchService({ providers: overrides.providers ?? buildProviders(config, http), logger });
  const pageFetcher = overrides.pageFetcher ?? createSafePageTransport({ userAgent: userAgent(config) });
  const throttle = overrides.throttle;
  const business = new BusinessSearchService({
    nominatim: new NominatimClient({ http, baseUrl: config.osm.nominatimUrl, throttle: throttle ?? createThrottle(1100) }),
    overpass: new OverpassClient({ http, url: config.osm.overpassUrl, throttle: throttle ?? createThrottle(1000) }),
    pageFetcher,
    webSearch,
    googlePlaces,
    logger,
    config,
  });
  return { http, usage, realtime, live, gemini, chat, media, trade, clips, webSearch, pageFetcher, business };
}

/** Página web de JARVIS (HTML/CSS/JS sin dependencias) que usa este mismo servidor. */
export const PUBLIC_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'public');

function geminiWs(config) {
  const u = new URL(config.gemini.baseUrl);
  return `${u.protocol === 'http:' ? 'ws:' : 'wss:'}//${u.host}`;
}

export function createApp(config, logger, overrides = {}) {
  const services = createServices(config, logger, overrides);
  const app = express();
  app.disable('x-powered-by');
  if (config.trustProxy) app.set('trust proxy', 1);
  app.use(
    helmet({
      contentSecurityPolicy: {
        directives: {
          // La web solo habla con este servidor, con Open-Meteo (clima, sin clave) y, para la voz de
          // Gemini, con su WebSocket (con un token efímero; la clave nunca llega al navegador).
          'connect-src': ["'self'", 'https://api.open-meteo.com', 'https://geocoding-api.open-meteo.com', geminiWs(config)],
          // Vistas previas de clips (almacenamiento de OpusClip) y audio de la voz (WebRTC).
          'media-src': ["'self'", 'https:', 'blob:'],
          'img-src': ["'self'", 'data:'],
        },
      },
      // El micrófono lo pide la propia página al tocar "Hablar".
      crossOriginEmbedderPolicy: false,
    }),
  );
  // Archivos de la web: antes del límite de peticiones (cargar la página no gasta cupo). Sin caché
  // larga (se revalida con ETag) para que tras un despliegue no se mezclen módulos de dos versiones.
  app.use(express.static(PUBLIC_DIR, { index: 'index.html', maxAge: 0, fallthrough: true }));
  const smallJson = express.json({ limit: '512kb' });
  app.use((req, res, next) => (MEDIA_ROUTES.test(req.path) ? next() : smallJson(req, res, next)));
  app.use(
    rateLimit({
      windowMs: 60_000,
      limit: config.rateLimitPerMinute,
      standardHeaders: 'draft-8',
      legacyHeaders: false,
      handler: (_req, _res, next) => next(Errors.rateLimited('Demasiadas peticiones; espera un momento.', 60)),
    }),
  );
  app.use(healthRouter({ config, ...services }));
  app.use('/api', requireClientToken(config.clientToken), apiRouter({ config, ...services }));
  app.use((_req, _res, next) => next(Errors.notFound('Ruta no encontrada.')));
  app.use(errorMiddleware(logger));
  return { app, services };
}
