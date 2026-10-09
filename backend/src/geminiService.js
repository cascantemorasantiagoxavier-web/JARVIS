const fetch = global.fetch || require('node-fetch');

async function createGeminiCompletion(apiKey, model, messages, maxRetries = 2) {
    if (!apiKey) {
        const error = new Error('GEMINI_API_KEY is not configured on the server');
        error.code = 'SERVER_CONFIG_ERROR';
        throw error;
    }

    const targetModel = model || process.env.GEMINI_MODEL || 'gemini-flash-lite-latest';
    const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${targetModel}:generateContent?key=${apiKey}`;

    let systemInstruction = null;
    const contents = [];

    for (const msg of messages) {
        if (msg.role === 'system') {
            systemInstruction = { parts: [{ text: msg.content }] };
        } else {
            const role = msg.role === 'user' ? 'user' : 'model';
            contents.push({
                role: role,
                parts: [{ text: msg.content }]
            });
        }
    }

    const requestBody = {
        contents: contents
    };
    if (systemInstruction) {
        requestBody.systemInstruction = systemInstruction;
    }

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            console.log(`[JARVIS Backend] Requesting Gemini (model: ${targetModel}), attempt ${attempt + 1}/${maxRetries + 1}...`);
            const response = await fetch(endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'User-Agent': 'JARVIS-Backend/1.0'
                },
                body: JSON.stringify(requestBody),
                timeout: 8000
            });

            const responseText = await response.text();
            let data;
            try {
                data = JSON.parse(responseText);
            } catch (_) {
                data = { error: { message: responseText } };
            }

            if (response.ok) {
                const candidateText = data.candidates?.[0]?.content?.parts?.[0]?.text || '';
                return {
                    id: `gemini_${Date.now()}`,
                    model: targetModel,
                    content: candidateText,
                    usage: data.usageMetadata || {}
                };
            } else {
                const errorMsg = data.error?.message || `Gemini API returned status ${response.status}`;
                const isTemporary = response.status === 503 || response.status === 429 || errorMsg.includes('high demand') || errorMsg.includes('temporary');

                if (isTemporary && attempt < maxRetries) {
                    const delayMs = 300 * (attempt + 1);
                    console.warn(`[JARVIS Backend] Gemini temporary error (${response.status}: ${errorMsg}), fast retry in ${delayMs}ms...`);
                    await new Promise(r => setTimeout(r, delayMs));
                    continue;
                }

                const errCode = data.error?.code === 429 || response.status === 429 ? 'GEMINI_QUOTA_EXHAUSTED' : response.status === 400 || response.status === 401 ? 'API_KEY_INVALID' : 'GEMINI_ERROR';
                const error = new Error(errorMsg);
                error.code = errCode;
                error.status = response.status;
                throw error;
            }
        } catch (e) {
            if (e.status && !e.message.includes('fetch')) throw e;
            if (attempt === maxRetries) {
                const error = new Error(e.message || 'Network error communicating with Gemini API');
                error.code = 'NETWORK_ERROR';
                throw error;
            }
            console.warn(`[JARVIS Backend] Fast retry on attempt ${attempt + 1} (${e.message})...`);
            await new Promise(r => setTimeout(r, 300));
        }
    }
}

module.exports = { createGeminiCompletion };
