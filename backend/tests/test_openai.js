const fetch = global.fetch || require('node-fetch');
require('dotenv').config({ path: require('path').resolve(__dirname, '../.env') });
const apiKey = process.env.OPENAI_API_KEY;

async function run() {
    console.log("Testing Realtime Session with model gpt-realtime-2.1...");
    try {
        const res = await fetch('https://api.openai.com/v1/realtime/sessions', {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${apiKey}`,
                'OpenAI-Beta': 'realtime=v1',
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                model: 'gpt-realtime-2.1',
                voice: 'alloy'
            })
        });
        console.log(`POST /v1/realtime/sessions Status: ${res.status}`);
        console.log(await res.text());
    } catch (e) {
        console.error("Realtime error:", e.message);
    }
}

run();
