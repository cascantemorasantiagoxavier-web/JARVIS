// Jest sets NODE_ENV=test; tests must not pick up real keys from .env unless explicitly asked to.
if (process.env.NODE_ENV !== 'test' || process.env.JARVIS_LIVE_TESTS === '1') {
    require('dotenv').config({ path: require('path').resolve(__dirname, '../.env') });
}
const express = require('express');
const { createRealtimeSession, createChatCompletion } = require('./openaiService');
const { createGeminiCompletion } = require('./geminiService');
const { performWebSearch, extractWebPage } = require('./webToolsService');
const googleTTS = require('google-tts-api');
const app = express();

const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';

const ALLOWED_ROLES = new Set(['system', 'user', 'assistant', 'model']);
const MAX_MESSAGES = 60;
const MODEL_NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/;
const RATE_LIMIT_WINDOW_MS = Number(process.env.RATE_LIMIT_WINDOW_MS) || 60000;
const RATE_LIMIT_MAX = Number(process.env.RATE_LIMIT_MAX) || 60;

// Render terminates TLS in front of the app; trust one proxy hop so req.ip is the client.
app.set('trust proxy', 1);
app.disable('x-powered-by');
app.use(express.json({ limit: '50kb' }));

function sendError(res, status, code, message) {
    return res.status(status).json({ error: { code, message } });
}

// Upstream status codes the Android client reacts to (retry on 429/503, auth message on 401/403).
function upstreamStatus(error) {
    if (error.code === 'SERVER_CONFIG_ERROR') return 500;
    if (error.code === 'VALIDATION_ERROR') return 400;
    if ([401, 403, 429, 503, 504].includes(error.status)) return error.status;
    return 502;
}

function validateChatPayload(body) {
    const { messages, model } = body || {};
    if (!Array.isArray(messages) || messages.length === 0) {
        return 'Payload must include a non-empty messages array';
    }
    if (messages.length > MAX_MESSAGES) {
        return `messages cannot contain more than ${MAX_MESSAGES} entries`;
    }
    for (const msg of messages) {
        if (!msg || typeof msg !== 'object' || !ALLOWED_ROLES.has(msg.role) || typeof msg.content !== 'string' || msg.content.length === 0) {
            return 'Each message needs a role (system, user, assistant or model) and non-empty string content';
        }
    }
    if (model !== undefined && model !== null && (typeof model !== 'string' || !MODEL_NAME_PATTERN.test(model))) {
        return 'model must be a plain model identifier';
    }
    return null;
}

// Minimal fixed-window limiter per client IP so a leaked URL cannot drain the provider quotas.
const rateBuckets = new Map();
setInterval(() => {
    const now = Date.now();
    for (const [ip, bucket] of rateBuckets) {
        if (now - bucket.windowStart >= RATE_LIMIT_WINDOW_MS) rateBuckets.delete(ip);
    }
}, RATE_LIMIT_WINDOW_MS).unref();

// Request logging middleware (method, path, status and timing only; never bodies or keys)
app.use((req, res, next) => {
    const start = Date.now();
    res.on('finish', () => {
        const duration = Date.now() - start;
        console.log(`[JARVIS Backend] ${req.method} ${req.path} - Status: ${res.statusCode} - Duration: ${duration}ms`);
    });
    next();
});

app.use('/api', (req, res, next) => {
    const now = Date.now();
    const key = req.ip || 'unknown';
    let bucket = rateBuckets.get(key);
    if (!bucket || now - bucket.windowStart >= RATE_LIMIT_WINDOW_MS) {
        bucket = { windowStart: now, count: 0 };
        rateBuckets.set(key, bucket);
    }
    bucket.count++;
    if (bucket.count > RATE_LIMIT_MAX) {
        res.set('Retry-After', String(Math.ceil((bucket.windowStart + RATE_LIMIT_WINDOW_MS - now) / 1000)));
        return sendError(res, 429, 'RATE_LIMITED', 'Too many requests, slow down');
    }
    next();
});

// Health check endpoint
app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        timestamp: Date.now(),
        providers: {
            openai: false,
            gemini: !!process.env.GEMINI_API_KEY,
            gemma: false
        }
    });
});

// Realtime session creation endpoint for WebSockets
app.post('/api/realtime/session', async (req, res) => {
    try {
        const apiKey = process.env.OPENAI_API_KEY;
        if (!apiKey) {
            console.error('[JARVIS Backend] Error: OPENAI_API_KEY not configured');
            return sendError(res, 500, 'SERVER_CONFIG_ERROR', 'OPENAI_API_KEY not configured on backend server');
        }
        const sessionInfo = await createRealtimeSession(apiKey, process.env.OPENAI_REALTIME_MODEL);
        res.json(sessionInfo);
    } catch (error) {
        console.error('[JARVIS Backend] Session creation error:', error.message);
        sendError(res, upstreamStatus(error), error.code || 'SESSION_CREATION_FAILED', error.message || 'Failed to create OpenAI Realtime session');
    }
});

// OpenAI chat completion endpoint
app.post('/api/chat', async (req, res) => {
    try {
        const validationError = validateChatPayload(req.body);
        if (validationError) {
            return sendError(res, 400, 'VALIDATION_ERROR', validationError);
        }

        const apiKey = process.env.OPENAI_API_KEY;
        if (!apiKey) {
            console.error('[JARVIS Backend] Error: OPENAI_API_KEY not configured');
            return sendError(res, 500, 'SERVER_CONFIG_ERROR', 'OPENAI_API_KEY not configured on backend server');
        }

        const { messages, model } = req.body;
        const result = await createChatCompletion(apiKey, model, messages);
        res.json(result);
    } catch (error) {
        console.error('[JARVIS Backend] Chat completion error:', error.message);
        sendError(res, upstreamStatus(error), error.code || 'CHAT_COMPLETION_FAILED', error.message || 'Failed to complete chat request');
    }
});

// Google Gemini chat completion endpoint
app.post('/api/gemini/chat', async (req, res) => {
    try {
        const apiKey = process.env.GEMINI_API_KEY;
        if (!apiKey) {
            console.error('[JARVIS Backend] Error: GEMINI_API_KEY not configured');
            return sendError(res, 500, 'SERVER_CONFIG_ERROR', 'GEMINI_API_KEY not configured on backend server');
        }

        const validationError = validateChatPayload(req.body);
        if (validationError) {
            return sendError(res, 400, 'VALIDATION_ERROR', validationError);
        }

        const { messages, model } = req.body;
        const result = await createGeminiCompletion(apiKey, model, messages);
        res.json(result);
    } catch (error) {
        console.error(`[JARVIS Backend] Gemini chat completion error (${error.code || 'UNKNOWN'}):`, error.message);
        sendError(res, upstreamStatus(error), error.code || 'GEMINI_CHAT_FAILED', error.message || 'Failed to complete Gemini request');
    }
});

// Web search endpoint: real DuckDuckGo results, or an explicit error. Never placeholder results.
app.post('/api/web/search', async (req, res) => {
    try {
        const { query, limit } = req.body || {};
        if (!query || typeof query !== 'string' || query.trim().length === 0 || query.length > 300) {
            return sendError(res, 400, 'VALIDATION_ERROR', 'Query string (1-300 chars) is required');
        }

        const maxResults = Math.min(Math.max(parseInt(limit, 10) || 5, 1), 10);
        const results = await performWebSearch(query.trim(), maxResults);
        res.json({ query: query.trim(), provider: 'duckduckgo', retrievedAt: new Date().toISOString(), results });
    } catch (error) {
        console.error('[JARVIS Backend] Web search error:', error.message);
        sendError(res, error.status || 502, error.code || 'SEARCH_FAILED', error.message);
    }
});

// Web extraction endpoint for public pages only (SSRF-protected)
app.post('/api/web/extract', async (req, res) => {
    try {
        const { url } = req.body || {};
        if (!url || typeof url !== 'string' || url.length > 2000) {
            return sendError(res, 400, 'VALIDATION_ERROR', 'URL string is required');
        }

        const page = await extractWebPage(url.trim());
        res.json(page);
    } catch (error) {
        console.error('[JARVIS Backend] Web extraction error:', error.message);
        sendError(res, error.status || 502, error.code || 'EXTRACT_FAILED', error.message);
    }
});

// TTS endpoint: returns a Google Translate TTS audio URL (max 150 chars of text)
app.post('/api/tts', async (req, res) => {
    try {
        const { text, lang } = req.body || {};
        if (!text || typeof text !== 'string') {
            return sendError(res, 400, 'VALIDATION_ERROR', 'El texto es obligatorio');
        }
        const language = typeof lang === 'string' && /^[a-z]{2,3}(-[A-Za-z]{2,4})?$/.test(lang) ? lang : 'es';

        const url = googleTTS.getAudioUrl(text.substring(0, 150), {
            lang: language,
            slow: false,
            host: 'https://translate.google.com'
        });

        res.json({ audioUrl: url, text: text });
    } catch (error) {
        console.error('[JARVIS Backend] Excepción en /api/tts:', error.message);
        sendError(res, 500, 'TTS_FAILED', error.message);
    }
});

app.use((req, res) => {
    sendError(res, 404, 'NOT_FOUND', `Route ${req.method} ${req.path} does not exist`);
});

// Malformed JSON bodies and other unhandled errors still answer with the standard JSON shape.
app.use((err, req, res, next) => {
    if (err.type === 'entity.parse.failed') {
        return sendError(res, 400, 'INVALID_JSON', 'Request body is not valid JSON');
    }
    if (err.type === 'entity.too.large') {
        return sendError(res, 413, 'PAYLOAD_TOO_LARGE', 'Request body exceeds 50kb');
    }
    console.error('[JARVIS Backend] Unhandled error:', err.message);
    sendError(res, 500, 'INTERNAL_ERROR', 'Unexpected server error');
});

if (require.main === module) {
    app.listen(PORT, HOST, () => {
        console.log(`[JARVIS Backend] Servidor corriendo en http://${HOST}:${PORT}`);
    });
}

module.exports = app;
