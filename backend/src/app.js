// Ensamblado de la aplicación Express. Todas las dependencias externas se inyectan,
// lo que permite probar el servidor completo sin red.
import express from 'express';
import rateLimit from 'express-rate-limit';
import helmet from 'helmet';
import { userAgent } from './config.js';
import { requireClientToken } from './http/auth.js';
import { errorMiddleware, Errors } from './http/errors.js';
import { createHttpClient } from './http/httpClient.js';
import { createSafePageTransport } from './http/safeFetch.js';
import { apiRouter, healthRouter } from './routes/index.js';
import { BusinessSearchService } from './services/business/businessService.js';
import { createThrottle, NominatimClient, OverpassClient } from './services/business/osmClients.js';
import { ChatService } from './services/openai/chat.js';
import { RealtimeService } from './services/openai/realtime.js';
import { buildProviders, WebSearchService } from './services/search/webSearchService.js';
import { UsageTracker } from './services/usage.js';

export function createServices(config, logger, overrides = {}) {
  const http =
    overrides.http ??
    createHttpClient({ fetchImpl: overrides.fetchImpl, timeoutMs: config.outboundTimeoutMs, userAgent: userAgent(config), logger });
  const usage = overrides.usage ?? new UsageTracker();
  const realtime = new RealtimeService({ config, http, logger, usage });
  const chat = new ChatService({ config, http, logger, usage });
  const webSearch = new WebSearchService({ providers: overrides.providers ?? buildProviders(config, http), logger });
  const pageFetcher = overrides.pageFetcher ?? createSafePageTransport({ userAgent: userAgent(config) });
  const throttle = overrides.throttle;
  const business = new BusinessSearchService({
    nominatim: new NominatimClient({ http, baseUrl: config.osm.nominatimUrl, throttle: throttle ?? createThrottle(1100) }),
    overpass: new OverpassClient({ http, url: config.osm.overpassUrl, throttle: throttle ?? createThrottle(1000) }),
    pageFetcher,
    webSearch,
    logger,
    config,
  });
  return { http, usage, realtime, chat, webSearch, pageFetcher, business };
}

export function createApp(config, logger, overrides = {}) {
  const services = createServices(config, logger, overrides);
  const app = express();
  app.disable('x-powered-by');
  if (config.trustProxy) app.set('trust proxy', 1);
  app.use(helmet());
  app.use(express.json({ limit: '512kb' }));
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
