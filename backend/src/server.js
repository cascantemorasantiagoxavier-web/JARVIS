import { createApp } from './app.js';
import { ConfigError, loadConfig } from './config.js';
import { createLogger } from './logger.js';

let config;
try {
  config = loadConfig();
} catch (err) {
  if (err instanceof ConfigError) {
    console.error(err.message);
    process.exit(1);
  }
  throw err;
}

const logger = createLogger(config.logLevel);
const { app, services } = createApp(config, logger);
const server = app.listen(config.port, config.host, () => {
  logger.info('server_started', {
    port: config.port,
    realtime: services.realtime.enabled,
    chat: services.chat.enabled,
    searchProviders: services.webSearch.describe().map((p) => p.id),
  });
  if (!config.osm.contact) {
    logger.warn('osm_contact_missing', { hint: 'Define OSM_CONTACT (correo o URL) para identificarte ante Nominatim/Overpass.' });
  }
});
server.requestTimeout = 150_000;

const shutdown = (signal) => {
  logger.info('server_stopping', { signal });
  services.realtime.shutdown();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 10_000).unref();
};
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
