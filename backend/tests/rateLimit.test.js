process.env.RATE_LIMIT_MAX = '3';
const request = require('supertest');
const app = require('../src/index');

describe('Rate limiting', () => {
    test('limits /api calls per client but never /health', async () => {
        for (let i = 0; i < 3; i++) {
            const ok = await request(app).post('/api/web/search').send({});
            expect(ok.statusCode).toBe(400);
        }
        const limited = await request(app).post('/api/web/search').send({});
        expect(limited.statusCode).toBe(429);
        expect(limited.body.error.code).toBe('RATE_LIMITED');
        expect(limited.headers['retry-after']).toBeDefined();

        const health = await request(app).get('/health');
        expect(health.statusCode).toBe(200);
    });
});
