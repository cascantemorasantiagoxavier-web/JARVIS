// Normalización común de resultados de búsqueda web.
export function hostnameOf(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return null;
  }
}

export function stripHtml(s) {
  return String(s ?? '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&#x27;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizeDate(value) {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

/** Devuelve un resultado validado o null si no tiene título/URL http(s) reales. */
export function makeResult({ title, url, snippet, source, publishedAt, provider }, retrievedAt) {
  if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return null;
  const cleanTitle = stripHtml(title);
  if (!cleanTitle) return null;
  return {
    title: cleanTitle.slice(0, 300),
    url,
    snippet: stripHtml(snippet).slice(0, 600) || null,
    source: source || hostnameOf(url),
    publishedAt: normalizeDate(publishedAt),
    retrievedAt,
    provider,
  };
}

export function dedupeByUrl(results) {
  const seen = new Set();
  return results.filter((r) => {
    const key = r.url.replace(/[#?].*$/, '').replace(/\/$/, '').toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/** Añade operadores site: para proveedores sin filtro nativo de dominios. */
export function withSiteOperators(query, domains) {
  if (!domains?.length) return query;
  const sites = domains.map((d) => `site:${d}`).join(' OR ');
  return `${query} (${sites})`;
}
