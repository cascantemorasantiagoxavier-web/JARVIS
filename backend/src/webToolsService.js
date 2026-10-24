const dns = require('dns');
const net = require('net');

// Resolved at call time so tests can stub global.fetch.
const fetch = (...args) => (global.fetch || require('node-fetch'))(...args);

const BROWSER_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const SEARCH_TIMEOUT_MS = 8000;
const EXTRACT_TIMEOUT_MS = 8000;
const MAX_EXTRACT_BYTES = 2 * 1024 * 1024;
const MAX_REDIRECTS = 3;

let lookupHost = (hostname) => dns.promises.lookup(hostname, { all: true });

function buildError(message, code, status) {
    const error = new Error(message);
    error.code = code;
    if (status) error.status = status;
    return error;
}

function decodeEntities(text) {
    return text
        .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
        .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(parseInt(dec, 10)))
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&nbsp;/g, ' ')
        .replace(/&amp;/g, '&');
}

function stripTags(html) {
    return decodeEntities(html.replace(/<[^>]*>/g, '')).replace(/\s+/g, ' ').trim();
}

// DuckDuckGo wraps organic results in //duckduckgo.com/l/?uddg=<target>; ads go through /y.js.
function resolveDuckDuckGoUrl(rawHref) {
    let parsed;
    try {
        parsed = new URL(rawHref.startsWith('//') ? `https:${rawHref}` : rawHref, 'https://duckduckgo.com');
    } catch (_) {
        return null;
    }
    if (parsed.hostname.endsWith('duckduckgo.com')) {
        const target = parsed.searchParams.get('uddg');
        if (!target) return null;
        try {
            parsed = new URL(target);
        } catch (_) {
            return null;
        }
    }
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.toString() : null;
}

function parseDuckDuckGoHtml(html, limit) {
    const anchors = [...html.matchAll(/<a\b([^>]*\bclass="[^"]*\bresult__a\b[^"]*"[^>]*)>([\s\S]*?)<\/a>/g)];
    const results = [];
    const seen = new Set();

    for (let i = 0; i < anchors.length && results.length < limit; i++) {
        const [, attrs, titleHtml] = anchors[i];
        const hrefMatch = /\bhref="([^"]+)"/.exec(attrs);
        const url = hrefMatch ? resolveDuckDuckGoUrl(decodeEntities(hrefMatch[1])) : null;
        if (!url || seen.has(url)) continue;
        seen.add(url);

        const blockEnd = i + 1 < anchors.length ? anchors[i + 1].index : html.length;
        const block = html.slice(anchors[i].index, blockEnd);
        const snippetMatch = /class="[^"]*\bresult__snippet\b[^"]*"[^>]*>([\s\S]*?)<\/(?:a|div|td)>/.exec(block);

        results.push({
            title: stripTags(titleHtml),
            url,
            snippet: snippetMatch ? stripTags(snippetMatch[1]) : '',
            source: new URL(url).hostname.replace(/^www\./, '')
        });
    }
    return results;
}

async function performWebSearch(query, limit = 5) {
    console.log(`[JARVIS WebTools] SEARCH_STARTED limit=${limit}`);
    const searchUrl = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`;
    let res;
    try {
        res = await fetch(searchUrl, {
            headers: { 'User-Agent': BROWSER_UA, 'Accept-Language': 'es-CR,es;q=0.9' },
            signal: AbortSignal.timeout(SEARCH_TIMEOUT_MS)
        });
    } catch (e) {
        throw buildError(`Search provider unreachable: ${e.message}`, 'SEARCH_UNAVAILABLE', 502);
    }

    console.log(`[JARVIS WebTools] SEARCH_HTTP_STATUS=${res.status}`);
    if (!res.ok) {
        throw buildError(`Search provider returned status ${res.status}`, 'SEARCH_UNAVAILABLE', 502);
    }

    const results = parseDuckDuckGoHtml(await res.text(), limit);
    console.log(`[JARVIS WebTools] SEARCH_RESULTS count=${results.length}`);
    return results;
}

function isPrivateAddress(address) {
    if (net.isIPv4(address)) {
        const [a, b] = address.split('.').map(Number);
        return a === 0 || a === 10 || a === 127 ||
            (a === 100 && b >= 64 && b <= 127) ||
            (a === 169 && b === 254) ||
            (a === 172 && b >= 16 && b <= 31) ||
            (a === 192 && b === 168) ||
            a >= 224;
    }
    const lower = address.toLowerCase();
    if (lower.startsWith('::ffff:')) return isPrivateAddress(lower.slice(7));
    return lower === '::' || lower === '::1' || lower.startsWith('fc') || lower.startsWith('fd') || lower.startsWith('fe80');
}

// Blocks requests to localhost, private networks and cloud metadata endpoints (SSRF).
async function assertPublicHttpUrl(rawUrl) {
    let parsed;
    try {
        parsed = new URL(rawUrl);
    } catch (_) {
        throw buildError('URL is not valid', 'VALIDATION_ERROR', 400);
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        throw buildError('Only http and https URLs are allowed', 'VALIDATION_ERROR', 400);
    }
    const hostname = parsed.hostname.replace(/^\[|\]$/g, '');
    if (hostname === 'localhost' || hostname.endsWith('.localhost') || hostname.endsWith('.internal')) {
        throw buildError('Private or local addresses are not allowed', 'URL_NOT_ALLOWED', 400);
    }

    let addresses;
    if (net.isIP(hostname)) {
        addresses = [hostname];
    } else {
        try {
            addresses = (await lookupHost(hostname)).map(entry => entry.address);
        } catch (e) {
            throw buildError(`Could not resolve host ${hostname}`, 'EXTRACT_FAILED', 502);
        }
    }
    if (addresses.length === 0 || addresses.some(isPrivateAddress)) {
        throw buildError('Private or local addresses are not allowed', 'URL_NOT_ALLOWED', 400);
    }
    return parsed;
}

async function extractWebPage(url) {
    let currentUrl = (await assertPublicHttpUrl(url)).toString();
    let res;

    for (let hop = 0; ; hop++) {
        try {
            res = await fetch(currentUrl, {
                headers: { 'User-Agent': BROWSER_UA },
                redirect: 'manual',
                signal: AbortSignal.timeout(EXTRACT_TIMEOUT_MS)
            });
        } catch (e) {
            throw buildError(`Could not fetch page: ${e.message}`, 'EXTRACT_FAILED', 502);
        }
        const location = res.headers.get('location');
        if (res.status >= 300 && res.status < 400 && location) {
            if (hop >= MAX_REDIRECTS) throw buildError('Too many redirects', 'EXTRACT_FAILED', 502);
            // Re-validate every hop so a redirect cannot point us at an internal host.
            currentUrl = (await assertPublicHttpUrl(new URL(location, currentUrl).toString())).toString();
            continue;
        }
        break;
    }

    console.log(`[JARVIS WebTools] PAGE_FETCHED status=${res.status}`);
    if (!res.ok) {
        throw buildError(`Page returned status ${res.status}`, 'EXTRACT_FAILED', 502);
    }
    const contentType = res.headers.get('content-type') || '';
    if (contentType && !/text\/html|text\/plain|application\/xhtml/i.test(contentType)) {
        throw buildError(`Unsupported content type: ${contentType}`, 'UNSUPPORTED_CONTENT', 415);
    }
    const declaredLength = Number(res.headers.get('content-length') || 0);
    if (declaredLength > MAX_EXTRACT_BYTES) {
        throw buildError('Page is too large to extract', 'EXTRACT_FAILED', 413);
    }

    const html = (await res.text()).slice(0, MAX_EXTRACT_BYTES);
    const content = decodeEntities(
        html.replace(/<!--[\s\S]*?-->/g, ' ')
            .replace(/<(script|style|noscript|template|svg)\b[\s\S]*?<\/\1>/gi, ' ')
            .replace(/<[^>]+>/g, ' ')
    ).replace(/\s+/g, ' ').trim().substring(0, 3000);

    const titleMatch = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html);
    const title = titleMatch ? stripTags(titleMatch[1]) : currentUrl;

    console.log(`[JARVIS WebTools] CONTENT_EXTRACTED length=${content.length}`);
    return {
        title,
        url: currentUrl,
        content,
        retrievedAt: new Date().toISOString()
    };
}

function setDnsLookupForTests(fn) {
    lookupHost = fn;
}

module.exports = {
    performWebSearch,
    extractWebPage,
    parseDuckDuckGoHtml,
    isPrivateAddress,
    setDnsLookupForTests
};
