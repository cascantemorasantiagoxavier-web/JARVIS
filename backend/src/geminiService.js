// Resolved at call time so tests can stub global.fetch.
const fetch = (...args) => (global.fetch || require('node-fetch'))(...args);

const DEFAULT_MODEL = 'gemini-flash-lite-latest';
const ATTEMPT_TIMEOUT_MS = Number(process.env.GEMINI_TIMEOUT_MS) || 10000;
// Keep the whole call (retries included) under the Android client's 25s read timeout.
const TOTAL_BUDGET_MS = Number(process.env.GEMINI_TOTAL_BUDGET_MS) || 20000;

function buildError(message, code, status) {
    const error = new Error(message);
    error.code = code;
    if (status) error.status = status;
    return error;
}

// Gemini only accepts "user" and "model" turns; system messages go to systemInstruction.
function toGeminiRequest(messages) {
    const systemParts = [];
    const contents = [];

    for (const msg of messages) {
        if (msg.role === 'system') {
            systemParts.push({ text: msg.content });
        } else {
            contents.push({
                role: msg.role === 'user' ? 'user' : 'model',
                parts: [{ text: msg.content }]
            });
        }
    }

    const requestBody = { contents };
    if (systemParts.length > 0) {
        requestBody.systemInstruction = { parts: systemParts };
    }
    return requestBody;
}

function classifyHttpError(status, message) {
    if (status === 429) return 'GEMINI_QUOTA_EXHAUSTED';
    if (status === 401 || status === 403 || /api key/i.test(message)) return 'API_KEY_INVALID';
    if (status === 404) return 'GEMINI_MODEL_NOT_FOUND';
    if (status === 400) return 'GEMINI_BAD_REQUEST';
    if (status === 503) return 'GEMINI_UNAVAILABLE';
    return 'GEMINI_ERROR';
}

async function createGeminiCompletion(apiKey, model, messages, maxRetries = 2) {
    if (!apiKey) {
        throw buildError('GEMINI_API_KEY is not configured on the server', 'SERVER_CONFIG_ERROR');
    }
    if (!Array.isArray(messages) || messages.length === 0) {
        throw buildError('messages must be a non-empty array', 'VALIDATION_ERROR', 400);
    }

    const targetModel = model || process.env.GEMINI_MODEL || DEFAULT_MODEL;
    // The key travels in a header so it never appears in URLs or proxy logs.
    const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(targetModel)}:generateContent`;
    const requestBody = toGeminiRequest(messages);

    const deadline = Date.now() + TOTAL_BUDGET_MS;
    let lastError = null;
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
        const remainingMs = deadline - Date.now();
        if (remainingMs < 1000) break;
        const attemptTimeoutMs = Math.min(ATTEMPT_TIMEOUT_MS, remainingMs);
        let response;
        try {
            console.log(`[JARVIS Backend] Requesting Gemini (model: ${targetModel}), attempt ${attempt + 1}/${maxRetries + 1}...`);
            response = await fetch(endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'x-goog-api-key': apiKey,
                    'User-Agent': 'JARVIS-Backend/1.0'
                },
                body: JSON.stringify(requestBody),
                signal: AbortSignal.timeout(attemptTimeoutMs)
            });
        } catch (e) {
            const isTimeout = e.name === 'TimeoutError' || e.name === 'AbortError';
            lastError = isTimeout
                ? buildError(`Gemini did not respond within ${attemptTimeoutMs}ms`, 'GEMINI_TIMEOUT', 504)
                : buildError(e.message || 'Network error communicating with Gemini API', 'NETWORK_ERROR', 502);
            if (attempt < maxRetries) {
                console.warn(`[JARVIS Backend] Gemini network failure (${lastError.code}), retrying...`);
                await new Promise(r => setTimeout(r, 300 * (attempt + 1)));
                continue;
            }
            throw lastError;
        }

        const responseText = await response.text();
        let data;
        try {
            data = JSON.parse(responseText);
        } catch (_) {
            data = { error: { message: responseText.slice(0, 300) } };
        }

        if (response.ok) {
            const candidate = data.candidates?.[0];
            const text = (candidate?.content?.parts || [])
                .map(part => part.text || '')
                .join('')
                .trim();
            if (!text) {
                const reason = candidate?.finishReason || data.promptFeedback?.blockReason || 'NO_CANDIDATES';
                throw buildError(`Gemini returned no text (reason: ${reason})`, 'GEMINI_EMPTY_RESPONSE', 502);
            }
            return {
                id: `gemini_${Date.now()}`,
                model: targetModel,
                content: text,
                finishReason: candidate.finishReason || null,
                usage: data.usageMetadata || {}
            };
        }

        const errorMsg = data.error?.message || `Gemini API returned status ${response.status}`;
        const isTemporary = response.status === 503 || response.status === 429 || response.status === 500;
        lastError = buildError(errorMsg, classifyHttpError(response.status, errorMsg), response.status);

        if (isTemporary && attempt < maxRetries) {
            const delayMs = 300 * (attempt + 1);
            console.warn(`[JARVIS Backend] Gemini temporary error (${response.status}), retry in ${delayMs}ms...`);
            await new Promise(r => setTimeout(r, delayMs));
            continue;
        }
        throw lastError;
    }
    throw lastError || buildError('Gemini request exceeded its time budget', 'GEMINI_TIMEOUT', 504);
}

module.exports = { createGeminiCompletion, toGeminiRequest };
