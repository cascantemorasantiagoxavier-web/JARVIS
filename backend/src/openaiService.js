const fetch = global.fetch || require('node-fetch');

async function createChatCompletion(apiKey, model, messages) {
    if (!apiKey) {
        const error = new Error('OPENAI_API_KEY is not configured on the server');
        error.code = 'SERVER_CONFIG_ERROR';
        throw error;
    }

    const targetModel = model || process.env.OPENAI_CHAT_MODEL || 'gpt-4o-mini';

    const response = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
            'Authorization': `Bearer ${apiKey}`,
            'Content-Type': 'application/json',
            'User-Agent': 'JARVIS-Backend/1.0'
        },
        body: JSON.stringify({
            model: targetModel,
            messages: messages,
            temperature: 0.7
        })
    });

    const responseText = await response.text();
    let data;
    try {
        data = JSON.parse(responseText);
    } catch (_) {
        data = { error: { message: responseText } };
    }

    if (!response.ok) {
        const errorMsg = data.error?.message || `OpenAI returned status ${response.status}`;
        const errCode = data.error?.code || (response.status === 429 ? 'OPENAI_QUOTA_EXHAUSTED' : response.status === 401 ? 'API_KEY_INVALID' : 'OPENAI_ERROR');
        const error = new Error(errorMsg);
        error.code = errCode;
        error.status = response.status;
        throw error;
    }

    const content = data.choices?.[0]?.message?.content || '';
    return {
        id: data.id,
        model: data.model || targetModel,
        content: content,
        usage: data.usage
    };
}

async function createRealtimeSession(apiKey, model) {
    if (!apiKey) {
        const error = new Error('OPENAI_API_KEY is not configured on the server');
        error.code = 'SERVER_CONFIG_ERROR';
        throw error;
    }

    const targetModel = (model && model !== 'gpt-realtime-2.1') ? model : 'gpt-4o-realtime-preview-2024-12-17';

    try {
        const response = await fetch(
            'https://api.openai.com/v1/realtime/sessions',
            {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${apiKey}`,
                    'OpenAI-Beta': 'realtime=v1',
                    'Content-Type': 'application/json',
                    'User-Agent': 'JARVIS-Backend/1.0'
                },
                body: JSON.stringify({
                    model: targetModel,
                    voice: 'alloy'
                })
            }
        );

        const responseText = await response.text();
        let data;
        try { data = JSON.parse(responseText); } catch (_) { data = {}; }

        if (response.ok) {
            const clientSecret = data.client_secret?.value || data.client_secret;
            return {
                sessionId: data.id || `sess_${Date.now()}`,
                clientSecret: clientSecret,
                expiresAt: data.client_secret?.expires_at ? (data.client_secret.expires_at * 1000) : (Date.now() + 3600000),
                model: targetModel
            };
        } else {
            const errorMsg = data.error?.message || `Realtime session endpoint returned ${response.status}`;
            const errCode = data.error?.code || (response.status === 429 ? 'OPENAI_QUOTA_EXHAUSTED' : response.status === 401 ? 'API_KEY_INVALID' : 'REALTIME_SESSION_ERROR');
            const error = new Error(errorMsg);
            error.code = errCode;
            error.status = response.status;
            throw error;
        }
    } catch (e) {
        if (e.code) throw e;
        const error = new Error(e.message || 'Unable to connect to OpenAI Realtime service');
        error.code = 'NETWORK_ERROR';
        throw error;
    }
}

module.exports = { createRealtimeSession, createChatCompletion };
