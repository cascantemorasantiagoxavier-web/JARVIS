# JARVIS Secure Backend

Modular and secure backend service for JARVIS, responsible for negotiating ephemeral client session credentials with the OpenAI Realtime API without exposing permanent API keys to Android clients.

## Architecture
- **Node.js & Express**: Lightweight local/server execution.
- **Security**: Isolates `OPENAI_API_KEY` on the server, issuing short-lived ephemeral client secrets.
- **Endpoints**:
  - `GET /health`: Server health check.
  - `POST /api/realtime/session`: Requests secure session info from OpenAI.

## Local Execution
1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Set your `OPENAI_API_KEY` in `.env`.
3. Install dependencies and start:
   ```bash
   npm install
   npm start
   ```

## Testing
```bash
npm test
```
