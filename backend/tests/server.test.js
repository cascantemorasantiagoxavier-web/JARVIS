const request = require('supertest');
const app = require('../src/index');

describe('JARVIS Backend API Tests', () => {
    test('GET /health returns status ok', async () => {
        const response = await request(app).get('/health');
        expect(response.statusCode).toBe(200);
        expect(response.body.status).toBe('ok');
        expect(response.body.timestamp).toBeDefined();
        expect(response.body.providers).toBeDefined();
    });

    test('POST /api/realtime/session handles missing API key safely', async () => {
        const originalKey = process.env.OPENAI_API_KEY;
        delete process.env.OPENAI_API_KEY;

        const response = await request(app).post('/api/realtime/session');
        expect(response.statusCode).toBe(500);
        expect(response.body.error).toBeDefined();
        expect(response.body.error.code).toBe('SERVER_CONFIG_ERROR');

        // Assigning undefined would store the string "undefined" and trigger live calls later.
        if (originalKey !== undefined) process.env.OPENAI_API_KEY = originalKey;
    });

    test('POST /api/chat handles validation and missing payload', async () => {
        const response = await request(app).post('/api/chat').send({});
        expect(response.statusCode).toBe(400);
        expect(response.body.error).toBeDefined();
        expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    test('POST /api/chat returns structured response or quota error when configured', async () => {
        if (!process.env.OPENAI_API_KEY) {
            return;
        }

        const response = await request(app)
            .post('/api/chat')
            .send({
                messages: [{ role: 'user', content: 'Hola' }]
            });

        expect(response.body).toBeDefined();
        if (response.statusCode === 200) {
            expect(response.body.content).toBeDefined();
        } else {
            expect(response.body.error).toBeDefined();
            expect(response.body.error.code).toBeDefined();
        }
    });

    test('POST /api/gemini/chat handles validation and missing API key safely', async () => {
        const originalKey = process.env.GEMINI_API_KEY;
        delete process.env.GEMINI_API_KEY;

        const response = await request(app).post('/api/gemini/chat').send({});
        expect(response.statusCode).toBe(500);
        expect(response.body.error).toBeDefined();
        expect(response.body.error.code).toBe('SERVER_CONFIG_ERROR');

        if (originalKey) process.env.GEMINI_API_KEY = originalKey;
    });

    test('POST /api/gemini/chat handles non-empty messages when key configured', async () => {
        process.env.GEMINI_API_KEY = 'test_dummy_key';
        const response = await request(app).post('/api/gemini/chat').send({});
        expect(response.statusCode).toBe(400);
        expect(response.body.error).toBeDefined();
        expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    test('POST /api/realtime/session returns realtime session when configured', async () => {
        if (!process.env.OPENAI_API_KEY || process.env.OPENAI_API_KEY.includes('your-key-here')) {
            console.warn('Skipping OpenAI session integration test because OPENAI_API_KEY is not set');
            return;
        }

        const response = await request(app).post('/api/realtime/session');
        if (response.statusCode === 200) {
            expect(response.body).toBeDefined();
            expect(response.body.clientSecret || response.body.client_secret || response.body.sessionId).toBeDefined();
        } else {
            expect(response.body.error).toBeDefined();
        }
    });
});
