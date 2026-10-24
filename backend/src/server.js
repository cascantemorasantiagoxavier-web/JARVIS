import { createApp } from './app.js';
import { ConfigError, loadConfig } from './config.js';
import { createLogger } from './logger.js';

// .env local (en Render las variables vienen del panel). Sin depender de flags de Node recientes.
try {
  process.loadEnvFile?.('.env');
} catch {
  /* sin .env: se usan las variables del entorno */
}

const [major, minor] = process.versions.node.split('.').map(Number);
if (major < 22 || (major === 22 && minor < 9)) {
  console.error(`JARVIS necesita Node.js 22.9 o superior (este servidor tiene ${process.versions.node}). En Render: variable NODE_VERSION=22.`);
  process.exit(1);
}

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
