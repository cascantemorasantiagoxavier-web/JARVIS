// Proveedores de búsqueda web. Cada uno devuelve resultados normalizados o lanza AppError.
// Ninguno inventa resultados: si el proveedor no devuelve nada, se devuelve [].
import { mapOpenAIError } from '../openai/realtime.js';
import { makeResult, withSiteOperators } from './normalize.js';

export function searxngProvider({ http, baseUrl }) {
  return {
    id: 'searxng',
    label: 'SearXNG (autoalojado)',
    async search({ query, maxResults, domains, lang }, retrievedAt) {
      const url = new URL('/search', baseUrl);
      url.searchParams.set('q', withSiteOperators(query, domains));
      url.searchParams.set('format', 'json');
      url.searchParams.set('language', lang);
      url.searchParams.set('safesearch', '1');
      const res = await http.request('SearXNG', url.toString(), { headers: { accept: 'application/json' } });
      const body = res.json();
      return (body.results ?? [])
        .slice(0, maxResults)
        .map((r) =>
          makeResult(
            { title: r.title, url: r.url, snippet: r.content, publishedAt: r.publishedDate, provider: 'searxng' },
            retrievedAt,
          ),
        )
        .filter(Boolean);
    },
  };
}

export function braveProvider({ http, apiKey }) {
  return {
    id: 'brave',
    label: 'Brave Search API',
    async search({ query, maxResults, domains, lang, country }, retrievedAt) {
      const url = new URL('https://api.search.brave.com/res/v1/web/search');
      url.searchParams.set('q', withSiteOperators(query, domains));
      url.searchParams.set('count', String(Math.min(maxResults, 20)));
      url.searchParams.set('search_lang', lang);
      if (country) url.searchParams.set('country', country.toUpperCase());
      const res = await http.request('Brave Search', url.toString(), {
        headers: { accept: 'application/json', 'x-subscription-token': apiKey },
      });
      const body = res.json();
      return (body.web?.results ?? [])
        .slice(0, maxResults)
        .map((r) =>
          makeResult(
            {
              title: r.title,
              url: r.url,
              snippet: r.description,
              source: r.profile?.name ?? r.meta_url?.hostname,
              publishedAt: r.page_age,
              provider: 'brave',
            },
            retrievedAt,
          ),
        )
        .filter(Boolean);
    },
  };
}

export function tavilyProvider({ http, apiKey }) {
  return {
    id: 'tavily',
    label: 'Tavily',
    async search({ query, maxResults, domains }, retrievedAt) {
      const res = await http.request('Tavily', 'https://api.tavily.com/search', {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
        body: JSON.stringify({
          query,
          max_results: Math.min(maxResults, 20),
          search_depth: 'basic',
          include_domains: domains?.length ? domains : undefined,
        }),
      });
      const body = res.json();
      return (body.results ?? [])
        .slice(0, maxResults)
        .map((r) =>
          makeResult(
            { title: r.title, url: r.url, snippet: r.content, publishedAt: r.published_date, provider: 'tavily' },
            retrievedAt,
          ),
        )
        .filter(Boolean);
    },
  };
}

/**
 * Exa (https://exa.ai). Contrato del SDK oficial exa-js 2.26.0: POST /search con la clave en la cabecera
 * x-api-key; `type: "auto"`, extractos con `contents.highlights` y país del usuario en `userLocation`.
 */
export function exaProvider({ http, apiKey, country = 'CR' }) {
  return {
    id: 'exa',
    label: 'Exa',
    async search({ query, maxResults, domains }, retrievedAt) {
      const res = await http.request('Exa', 'https://api.exa.ai/search', {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-api-key': apiKey },
        body: JSON.stringify({
          query,
          type: 'auto',
          numResults: Math.min(maxResults, 20),
          includeDomains: domains?.length ? domains : undefined,
          userLocation: country,
          contents: { highlights: { maxCharacters: 600 } },
        }),
      });
      const body = res.json();
      return (body.results ?? [])
        .slice(0, maxResults)
        .map((r) =>
          makeResult(
            {
              title: r.title,
              url: r.url,
              snippet: Array.isArray(r.highlights) ? r.highlights.join(' … ') : r.summary ?? r.text,
              publishedAt: r.publishedDate,
              provider: 'exa',
            },
            retrievedAt,
          ),
        )
        .filter(Boolean);
    },
  };
}

/** Herramienta web_search de la API Responses (de pago: tokens + llamadas de búsqueda). */
export function openaiProvider({ http, apiKey, baseUrl, model }) {
  return {
    id: 'openai',
    label: 'OpenAI web_search (de pago)',
    async search({ query, maxResults, domains }, retrievedAt) {
      const tool = { type: 'web_search' };
      if (domains?.length) tool.filters = { allowed_domains: domains };
      const res = await http.request('OpenAI', `${baseUrl}/responses`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
        body: JSON.stringify({
          model,
          tools: [tool],
          tool_choice: 'required',
          include: ['web_search_call.action.sources'],
          store: false,
          input: `Busca en la web: ${query}. Responde con un resumen breve citando las fuentes.`,
        }),
        mapError: mapOpenAIError,
        timeout: 60000,
      });
      const body = res.json();
      const out = [];
      for (const item of body.output ?? []) {
        if (item.type === 'message') {
          for (const part of item.content ?? []) {
            const text = part.text ?? '';
            for (const a of part.annotations ?? []) {
              if (a.type !== 'url_citation') continue;
              const snippet = text.slice(Math.max(0, a.start_index - 220), a.start_index).trim();
              out.push(makeResult({ title: a.title, url: a.url, snippet, provider: 'openai' }, retrievedAt));
            }
          }
        }
        if (item.type === 'web_search_call') {
          for (const s of item.action?.sources ?? []) {
            if (s.url) out.push(makeResult({ title: s.title ?? s.url, url: s.url, provider: 'openai' }, retrievedAt));
          }
        }
      }
      return out.filter(Boolean).slice(0, maxResults);
    },
  };
}

/** Wikipedia: gratuita, sin clave. Cobertura enciclopédica, no noticias ni comercios. */
export function wikipediaProvider({ http, lang }) {
  return {
    id: 'wikipedia',
    label: `Wikipedia (${lang})`,
    async search({ query, maxResults }, retrievedAt) {
      const url = new URL(`https://${lang}.wikipedia.org/w/api.php`);
      url.searchParams.set('action', 'query');
      url.searchParams.set('list', 'search');
      url.searchParams.set('srsearch', query);
      url.searchParams.set('srlimit', String(Math.min(maxResults, 10)));
      url.searchParams.set('format', 'json');
      url.searchParams.set('utf8', '1');
      const res = await http.request('Wikipedia', url.toString(), { headers: { accept: 'application/json' } });
      const body = res.json();
      return (body.query?.search ?? [])
        .map((r) =>
          makeResult(
            {
              title: r.title,
              url: `https://${lang}.wikipedia.org/?curid=${r.pageid}`,
              snippet: r.snippet,
              source: `Wikipedia (${lang})`,
              publishedAt: r.timestamp,
              provider: 'wikipedia',
            },
            retrievedAt,
          ),
        )
        .filter(Boolean);
    },
  };
}
