const request = require('supertest');
const app = require('../src/index');
const { parseDuckDuckGoHtml, isPrivateAddress, setDnsLookupForTests } = require('../src/webToolsService');

function jsonResponse(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status,
        headers: new Map([['content-type', 'application/json']]),
        text: async () => JSON.stringify(body)
    };
}

function htmlResponse(status, html, extraHeaders = {}) {
    return {
        ok: status >= 200 && status < 300,
        status,
        headers: new Map(Object.entries({ 'content-type': 'text/html; charset=utf-8', ...extraHeaders })),
        text: async () => html
    };
}

const DDG_FIXTURE = `
<div class="result results_links results_links_deep web-result">
  <h2 class="result__title">
    <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.hacienda.go.cr%2F&amp;rut=abc">Ministerio de <b>Hacienda</b> &amp; Tributación</a>
  </h2>
  <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.hacienda.go.cr%2F&amp;rut=abc">Sitio oficial del <b>Ministerio</b> de Hacienda.</a>
</div>
<div class="result results_links web-result">
  <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.hacienda.go.cr%2F&amp;rut=dup">Duplicado</a>
</div>
<div class="result result--ad">
  <a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_domain=example.com">Anuncio</a>
</div>
<div class="result results_links web-result">
  <a class="result__a" rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fpagina&amp;rut=xyz">Segundo resultado</a>
</div>`;

describe('Gemini gateway', () => {
    beforeEach(() => {
        process.env.GEMINI_API_KEY = 'unit_test_key';
        global.fetch = jest.fn();
    });

    afterEach(() => {
        delete process.env.GEMINI_API_KEY;
        delete global.fetch;
    });

    test('forwards messages, system prompt and model to Gemini with the key in a header', async () => {
        global.fetch.mockResolvedValueOnce(jsonResponse(200, {
            candidates: [{ content: { parts: [{ text: 'Listo, ' }, { text: 'jefe.' }] }, finishReason: 'STOP' }],
            usageMetadata: { totalTokenCount: 12 }
        }));

        const response = await request(app).post('/api/gemini/chat').send({
            model: 'gemini-flash-lite-latest',
            messages: [
                { role: 'system', content: 'Eres JARVIS.' },
                { role: 'user', content: 'Hola' },
                { role: 'assistant', content: 'Buenas.' },
                { role: 'user', content: '¿Estás listo?' }
            ]
        });

        expect(response.statusCode).toBe(200);
        expect(response.body.content).toBe('Listo, jefe.');
        expect(response.body.model).toBe('gemini-flash-lite-latest');

        const [url, options] = global.fetch.mock.calls[0];
        expect(url).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent');
        expect(url).not.toContain('unit_test_key');
        expect(options.headers['x-goog-api-key']).toBe('unit_test_key');

        const body = JSON.parse(options.body);
        expect(body.systemInstruction.parts[0].text).toBe('Eres JARVIS.');
        expect(body.contents).toEqual([
            { role: 'user', parts: [{ text: 'Hola' }] },
            { role: 'model', parts: [{ text: 'Buenas.' }] },
            { role: 'user', parts: [{ text: '¿Estás listo?' }] }
        ]);
    });

    test('rejects model names that could alter the upstream URL', async () => {
        const response = await request(app).post('/api/gemini/chat').send({
            model: '../../v1/files?x=',
            messages: [{ role: 'user', content: 'Hola' }]
        });
        expect(response.statusCode).toBe(400);
        expect(response.body.error.code).toBe('VALIDATION_ERROR');
        expect(global.fetch).not.toHaveBeenCalled();
    });

    test('rejects malformed messages', async () => {
        const response = await request(app).post('/api/gemini/chat').send({
            messages: [{ role: 'hacker', content: 42 }]
        });
        expect(response.statusCode).toBe(400);
        expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    test('propagates quota exhaustion as 429 after retrying', async () => {
        global.fetch.mockResolvedValue(jsonResponse(429, { error: { code: 429, message: 'Resource exhausted' } }));

        const response = await request(app).post('/api/gemini/chat').send({
            messages: [{ role: 'user', content: 'Hola' }]
        });

        expect(response.statusCode).toBe(429);
        expect(response.body.error.code).toBe('GEMINI_QUOTA_EXHAUSTED');
        expect(global.fetch).toHaveBeenCalledTimes(3);
    });

    test('reports an empty or blocked answer as an error instead of an empty success', async () => {
        global.fetch.mockResolvedValueOnce(jsonResponse(200, {
            candidates: [{ content: { parts: [] }, finishReason: 'SAFETY' }]
        }));

        const response = await request(app).post('/api/gemini/chat').send({
            messages: [{ role: 'user', content: 'Hola' }]
        });

        expect(response.statusCode).toBe(502);
        expect(response.body.error.code).toBe('GEMINI_EMPTY_RESPONSE');
        expect(response.body.error.message).toContain('SAFETY');
    });

    test('maps network failures to 502 NETWORK_ERROR', async () => {
        global.fetch.mockRejectedValue(new TypeError('fetch failed'));

        const response = await request(app).post('/api/gemini/chat').send({
            messages: [{ role: 'user', content: 'Hola' }]
        });

        expect(response.statusCode).toBe(502);
        expect(response.body.error.code).toBe('NETWORK_ERROR');
    });
});

describe('Web search', () => {
    beforeEach(() => {
        global.fetch = jest.fn();
    });

    afterEach(() => {
        delete global.fetch;
    });

    test('parses real result URLs, titles and snippets, skipping ads and duplicates', () => {
        const results = parseDuckDuckGoHtml(DDG_FIXTURE, 5);
        expect(results).toEqual([
            {
                title: 'Ministerio de Hacienda & Tributación',
                url: 'https://www.hacienda.go.cr/',
                snippet: 'Sitio oficial del Ministerio de Hacienda.',
                source: 'hacienda.go.cr'
            },
            {
                title: 'Segundo resultado',
                url: 'https://example.org/pagina',
                snippet: '',
                source: 'example.org'
            }
        ]);
    });

    test('returns parsed results from the endpoint', async () => {
        global.fetch.mockResolvedValueOnce(htmlResponse(200, DDG_FIXTURE));

        const response = await request(app).post('/api/web/search').send({ query: 'Hacienda Costa Rica', limit: 1 });

        expect(response.statusCode).toBe(200);
        expect(response.body.provider).toBe('duckduckgo');
        expect(response.body.results).toHaveLength(1);
        expect(response.body.results[0].url).toBe('https://www.hacienda.go.cr/');
    });

    test('returns an empty list instead of invented results when nothing matches', async () => {
        global.fetch.mockResolvedValueOnce(htmlResponse(200, '<html><body>No results</body></html>'));

        const response = await request(app).post('/api/web/search').send({ query: 'zzzz' });

        expect(response.statusCode).toBe(200);
        expect(response.body.results).toEqual([]);
    });

    test('reports provider failure as 502 instead of placeholder results', async () => {
        global.fetch.mockResolvedValueOnce(htmlResponse(503, 'busy'));

        const response = await request(app).post('/api/web/search').send({ query: 'Hacienda' });

        expect(response.statusCode).toBe(502);
        expect(response.body.error.code).toBe('SEARCH_UNAVAILABLE');
        expect(response.body.results).toBeUndefined();
    });

    test('validates the query', async () => {
        const response = await request(app).post('/api/web/search').send({ query: '' });
        expect(response.statusCode).toBe(400);
        expect(global.fetch).not.toHaveBeenCalled();
    });
});

describe('Web extract', () => {
    beforeEach(() => {
        global.fetch = jest.fn();
        setDnsLookupForTests(async () => [{ address: '93.184.215.14', family: 4 }]);
    });

    afterEach(() => {
        delete global.fetch;
    });

    test.each([
        'http://localhost:3000/health',
        'http://127.0.0.1/',
        'http://169.254.169.254/latest/meta-data/',
        'http://[::1]/',
        'file:///etc/passwd'
    ])('blocks %s', async (url) => {
        const response = await request(app).post('/api/web/extract').send({ url });
        expect(response.statusCode).toBe(400);
        expect(global.fetch).not.toHaveBeenCalled();
    });

    test('blocks hostnames that resolve to private addresses', async () => {
        setDnsLookupForTests(async () => [{ address: '10.0.0.5', family: 4 }]);
        const response = await request(app).post('/api/web/extract').send({ url: 'https://intranet.example.com/' });
        expect(response.statusCode).toBe(400);
        expect(response.body.error.code).toBe('URL_NOT_ALLOWED');
    });

    test('blocks redirects that point to private addresses', async () => {
        global.fetch.mockResolvedValueOnce(htmlResponse(302, '', { location: 'http://127.0.0.1/admin' }));
        const response = await request(app).post('/api/web/extract').send({ url: 'https://example.com/' });
        expect(response.statusCode).toBe(400);
        expect(global.fetch).toHaveBeenCalledTimes(1);
    });

    test('extracts title and visible text from a public page', async () => {
        global.fetch.mockResolvedValueOnce(htmlResponse(200,
            '<html><head><title>Aduanas &amp; Comercio</title><style>p{}</style></head>' +
            '<body><script>alert(1)</script><!--script>(function(){tracker()})()</script--><p>Requisitos de importación.</p></body></html>'));

        const response = await request(app).post('/api/web/extract').send({ url: 'https://example.com/' });

        expect(response.statusCode).toBe(200);
        expect(response.body.title).toBe('Aduanas & Comercio');
        expect(response.body.content).toContain('Requisitos de importación.');
        expect(response.body.content).not.toContain('alert');
        expect(response.body.content).not.toContain('tracker');
    });

    test('reports fetch failures instead of invented page content', async () => {
        global.fetch.mockResolvedValueOnce(htmlResponse(404, 'nope'));
        const response = await request(app).post('/api/web/extract').send({ url: 'https://example.com/missing' });
        expect(response.statusCode).toBe(502);
        expect(response.body.error.code).toBe('EXTRACT_FAILED');
    });

    test('recognizes private address ranges', () => {
        expect(isPrivateAddress('192.168.1.10')).toBe(true);
        expect(isPrivateAddress('172.20.0.1')).toBe(true);
        expect(isPrivateAddress('100.64.0.1')).toBe(true);
        expect(isPrivateAddress('::ffff:127.0.0.1')).toBe(true);
        expect(isPrivateAddress('fd00::1')).toBe(true);
        expect(isPrivateAddress('8.8.8.8')).toBe(false);
        expect(isPrivateAddress('2606:4700::1111')).toBe(false);
    });
});

describe('Error handling', () => {
    test('malformed JSON gets a JSON 400', async () => {
        const response = await request(app)
            .post('/api/gemini/chat')
            .set('Content-Type', 'application/json')
            .send('{"messages": [');
        expect(response.statusCode).toBe(400);
        expect(response.body.error.code).toBe('INVALID_JSON');
    });

    test('unknown routes get a JSON 404', async () => {
        const response = await request(app).get('/api/does-not-exist');
        expect(response.statusCode).toBe(404);
        expect(response.body.error.code).toBe('NOT_FOUND');
    });
});
