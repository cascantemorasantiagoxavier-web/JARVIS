require('dotenv').config({ path: require('path').resolve(__dirname, '../.env') });
const express = require('express');
const { createRealtimeSession, createChatCompletion } = require('./openaiService');
const { createGeminiCompletion } = require('./geminiService');
const { getAudio } = require('ms-tts-api');
const app = express();
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';

app.use(express.json({ limit: '50kb' }));

// Request logging middleware (safe, no secrets)
app.use((req, res, next) => {
    const start = Date.now();
    res.on('finish', () => {
        const duration = Date.now() - start;
        console.log(`[JARVIS Backend] ${req.method} ${req.path} - Status: ${res.statusCode} - Duration: ${duration}ms`);
    });
    next();
});

// Health check endpoint
app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        timestamp: Date.now(),
        providers: {
            openai: false, // Deactivated per configuration
            gemini: !!process.env.GEMINI_API_KEY,
            gemma: false // Local model not installed
        }
    });
});

// Secure OpenAI Chat / Intelligence endpoint
app.post('/api/chat', async (req, res) => {
    try {
        const apiKey = process.env.OPENAI_API_KEY;
        if (!apiKey) {
            console.error('[JARVIS Backend] Error: OPENAI_API_KEY not configured');
            return res.status(500).json({
                error: {
                    code: 'SERVER_CONFIG_ERROR',
                    message: 'Server configuration error: OPENAI_API_KEY not configured'
                }
            });
        }

        const { messages, model } = req.body;
        if (!messages || !Array.isArray(messages) || messages.length === 0) {
            return res.status(400).json({
                error: {
                    code: 'VALIDATION_ERROR',
                    message: 'Messages parameter must be a non-empty array'
                }
            });
        }

        const result = await createChatCompletion(apiKey, model, messages);
        res.json(result);
    } catch (error) {
        console.error('[JARVIS Backend] Chat completion error:', error.message);
        const statusCode = error.status || (error.code === 'OPENAI_QUOTA_EXHAUSTED' ? 429 : error.code === 'API_KEY_INVALID' ? 401 : 500);
        res.status(statusCode).json({
            error: {
                code: error.code || 'OPENAI_ERROR',
                message: error.message || 'Error communicating with OpenAI'
            }
        });
    }
});

// Secure Gemini Chat / Intelligence endpoint
app.post('/api/gemini/chat', async (req, res) => {
    try {
        const apiKey = process.env.GEMINI_API_KEY;
        if (!apiKey) {
            console.error('[JARVIS Backend] Error: GEMINI_API_KEY not configured');
            return res.status(500).json({
                error: {
                    code: 'SERVER_CONFIG_ERROR',
                    message: 'Server configuration error: GEMINI_API_KEY not configured'
                }
            });
        }

        const { messages, model } = req.body;
        if (!messages || !Array.isArray(messages) || messages.length === 0) {
            return res.status(400).json({
                error: {
                    code: 'VALIDATION_ERROR',
                    message: 'Messages parameter must be a non-empty array'
                }
            });
        }

        const result = await createGeminiCompletion(apiKey, model, messages);
        // Extraer la respuesta en texto que genera Gemini
            const textResponse = result.text || result.content || (typeof result === 'string' ? result : JSON.stringify(result));

            // Generar el audio MP3 con la voz elegante de JARVIS
            const audioStream = await getAudio(textResponse, 'es-ES-AlvaroNeural');
            const audioBase64 = audioStream.toString('base64');

            // Devolver texto Y audio juntos a la app Android
            res.json({
              ...result,
              text: textResponse,
              audio: audioBase64
            });
    } catch (error) {
        console.error('[JARVIS Backend] Gemini completion error:', error.message);
        const statusCode = error.status || (error.code === 'GEMINI_QUOTA_EXHAUSTED' ? 429 : error.code === 'API_KEY_INVALID' ? 401 : 500);
        res.status(statusCode).json({
            error: {
                code: error.code || 'GEMINI_ERROR',
                message: error.message || 'Error communicating with Gemini'
            }
        });
    }
});

// Secure Realtime Session endpoint
app.post('/api/realtime/session', async (req, res) => {
    try {
        const apiKey = process.env.OPENAI_API_KEY;
        const model = process.env.OPENAI_REALTIME_MODEL || 'gpt-4o-realtime-preview-2024-12-17';

        if (!apiKey) {
            console.error('[JARVIS Backend] Error: OPENAI_API_KEY not configured');
            return res.status(500).json({
                error: {
                    code: 'SERVER_CONFIG_ERROR',
                    message: 'Server configuration error: OPENAI_API_KEY not configured'
                }
            });
        }

        const sessionInfo = await createRealtimeSession(apiKey, model);
        res.json(sessionInfo);
    } catch (error) {
        console.error('[JARVIS Backend] Session creation error:', error.message);
        const statusCode = error.status || (error.code === 'OPENAI_QUOTA_EXHAUSTED' ? 429 : error.code === 'API_KEY_INVALID' ? 401 : 500);
        res.status(statusCode).json({
            error: {
                code: error.code || 'REALTIME_SESSION_ERROR',
                message: error.message || 'Unable to create realtime session'
            }
        });
    }
});

const { performWebSearch, extractWebPage } = require('./webToolsService');

app.post('/api/web/search', async (req, res) => {
    try {
        const { query, limit } = req.body;
        if (!query) {
            return res.status(400).json({ error: { code: 'VALIDATION_ERROR', message: 'Query parameter is required' } });
        }
        const results = await performWebSearch(query, limit || 5);
        res.json({ results });
    } catch (error) {
        console.error('[JARVIS Backend] Web search error:', error.message);
        res.status(500).json({ error: { code: 'WEB_SEARCH_ERROR', message: error.message } });
    }
});

app.post('/api/web/extract', async (req, res) => {
    try {
        const { url } = req.body;
        if (!url) {
            return res.status(400).json({ error: { code: 'VALIDATION_ERROR', message: 'URL parameter is required' } });
        }
        const data = await extractWebPage(url);
        res.json(data);
    } catch (error) {
        console.error('[JARVIS Backend] Web extract error:', error.message);
        res.status(500).json({ error: { code: 'WEB_EXTRACT_ERROR', message: error.message } });
    }
});

if (process.env.NODE_ENV !== 'test') {
    app.listen(PORT, HOST, () => {
        console.log(`[JARVIS Backend] Running securely on http://${HOST}:${PORT}`);
    });
}

module.exports = app;
