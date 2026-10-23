// Servicio de búsqueda web con cadena de proveedores y degradación honesta.
import { AppError, Errors } from '../../http/errors.js';
import { dedupeByUrl } from './normalize.js';
import {
  braveProvider,
  openaiProvider,
  searxngProvider,
  tavilyProvider,
  wikipediaProvider,
} from './providers.js';

export function buildProviders(config, http) {
  const s = config.search;
  const available = {};
  if (s.searxngUrl) available.searxng = searxngProvider({ http, baseUrl: s.searxngUrl });
  if (s.tavilyApiKey) available.tavily = tavilyProvider({ http, apiKey: s.tavilyApiKey });
  if (s.braveApiKey) available.brave = braveProvider({ http, apiKey: s.braveApiKey });
  if (s.enableOpenAI && config.openai.apiKey) {
    available.openai = openaiProvider({
      http,
      apiKey: config.openai.apiKey,
      baseUrl: config.openai.baseUrl,
      model: config.openai.text.model,
    });
  }
  available.wikipedia = wikipediaProvider({ http, lang: s.wikipediaLang });

  const order =
    s.providers === 'auto'
      ? ['searxng', 'tavily', 'brave', 'openai', 'wikipedia']
      : s.providers.split(',').map((p) => p.trim());
  return order.filter((id) => available[id]).map((id) => available[id]);
}

export class WebSearchService {
  constructor({ providers, logger, now = () => new Date() }) {
    this.providers = providers;
    this.logger = logger;
    this.now = now;
  }

  describe() {
    return this.providers.map((p) => ({ id: p.id, label: p.label }));
  }

  /**
   * Prueba los proveedores en orden hasta obtener resultados. Devuelve siempre qué
   * proveedor respondió y las advertencias de los que fallaron, para no ocultar errores.
   */
  async search({ query, maxResults = 8, domains, lang = 'es', country = 'cr' }) {
    const retrievedAt = this.now().toISOString();
    const warnings = [];
    let lastError;
    for (const provider of this.providers) {
      try {
        const results = dedupeByUrl(await provider.search({ query, maxResults, domains, lang, country }, retrievedAt));
        if (results.length > 0) {
          return {
            query,
            provider: provider.id,
            providerLabel: provider.label,
            coverage: provider.id === 'wikipedia' ? 'encyclopedic' : 'web',
            retrievedAt,
            results,
            warnings,
          };
        }
        warnings.push(`${provider.label}: sin resultados.`);
      } catch (err) {
        lastError = err;
        const msg = err instanceof AppError ? err.message : 'error desconocido';
        warnings.push(`${provider.label}: ${msg}`);
        this.logger.warn('search_provider_failed', { provider: provider.id, code: err?.code });
      }
    }
    if (lastError && warnings.every((w) => !w.endsWith('sin resultados.'))) {
      throw new AppError(502, 'search_unavailable', 'Ningún proveedor de búsqueda respondió. ' + warnings.join(' '));
    }
    return {
      query,
      provider: null,
      providerLabel: null,
      coverage: 'none',
      retrievedAt,
      results: [],
      warnings,
    };
  }
}

export function requireProviders(service) {
  if (!service.providers.length) throw Errors.notConfigured('búsqueda web');
}
