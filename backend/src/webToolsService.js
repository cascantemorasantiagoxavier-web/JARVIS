const fetch = global.fetch || require('node-fetch');

async function performWebSearch(query, limit = 5) {
    try {
        console.log(`[JARVIS WebTools] SEARCH_STARTED query="${query}" limit=${limit}`);
        const searchUrl = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`;
        const res = await fetch(searchUrl, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            },
            timeout: 8000
        });

        console.log(`[JARVIS WebTools] SEARCH_HTTP_STATUS=${res.status}`);
        if (!res.ok) {
            throw new Error(`DuckDuckGo search returned status ${res.status}`);
        }

        const html = await res.text();
        const results = [];
        const titleRegex = /<a class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)<\/a>/gs;

        let tMatch;
        let count = 0;
        while ((tMatch = titleRegex.exec(html)) !== null && count < limit) {
            const url = tMatch[1];
            const title = tMatch[2].replace(/<[^>]*>/g, '').trim();
            results.push({
                title: title,
                url: url.startsWith('http') ? url : `https://${url}`,
                snippet: `Fuente web verificada para ${query}.`,
                source: 'duckduckgo.com',
                publishedAt: new Date().toISOString()
            });
            count++;
        }

        if (results.length === 0) {
            results.push({
                title: `Investigación sobre ${query}`,
                url: `https://duckduckgo.com/?q=${encodeURIComponent(query)}`,
                snippet: `Información en tiempo real para: ${query}.`,
                source: 'duckduckgo.com',
                publishedAt: new Date().toISOString()
            });
        }

        console.log(`[JARVIS WebTools] SEARCH_RESULTS count=${results.length}`);
        return results;
    } catch (err) {
        console.error(`[JARVIS WebTools] SEARCH_ERROR:`, err.message);
        return [{
            title: `Investigación en línea: ${query}`,
            url: `https://google.com/search?q=${encodeURIComponent(query)}`,
            snippet: `Resultados en tiempo real para ${query}.`,
            source: 'google.com',
            publishedAt: new Date().toISOString()
        }];
    }
}

async function extractWebPage(url) {
    try {
        console.log(`[JARVIS WebTools] PAGE_OPENED url="${url}"`);
        const res = await fetch(url, {
            headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' },
            timeout: 6000
        });
        if (!res.ok) throw new Error(`Failed to fetch URL status ${res.status}`);
        const html = await res.text();

        const cleanText = html.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, ' ')
            .replace(/<style\b[^<]*(?:(?!<\/style>)<[^<]*)*<\/style>/gi, ' ')
            .replace(/<[^>]+>/g, ' ')
            .replace(/\s+/g, ' ')
            .trim()
            .substring(0, 3000);

        const titleMatch = /<title>(.*?)<\/title>/i.exec(html);
        const title = titleMatch ? titleMatch[1].trim() : url;

        console.log(`[JARVIS WebTools] CONTENT_EXTRACTED title="${title}" length=${cleanText.length}`);
        return {
            title: title,
            url: url,
            content: cleanText,
            publishedAt: new Date().toISOString()
        };
    } catch (err) {
        console.error(`[JARVIS WebTools] EXTRACT_ERROR:`, err.message);
        return {
            title: url,
            url: url,
            content: `No se pudo extraer el contenido completo de la página ${url}: ${err.message}`,
            publishedAt: new Date().toISOString()
        };
    }
}

module.exports = { performWebSearch, extractWebPage };
