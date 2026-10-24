// Voz en vivo con Gemini Live. El navegador no recibe la clave: el servidor crea un token efímero de un
// solo uso (POST v1alpha/auth_tokens) con la configuración BLOQUEADA (modelo, voz, instrucciones y
// herramientas), y el navegador abre el WebSocket "BidiGenerateContentConstrained" con ese token.
// Contrato verificado contra @google/genai 2.31.0 (tokens.create, live.connect).
import { AppError, Errors } from '../../http/errors.js';
import { functionDeclarations } from './gemini.js';

const TOKEN_MINUTES = 30; // duración máxima de la conversación con ese token
const START_MINUTES = 2; // tiempo para abrir la conexión

export class GeminiLiveService {
  constructor({ config, gemini, usage, logger, now = () => Date.now() }) {
    this.config = config;
    this.gemini = gemini;
    this.usage = usage;
    this.logger = logger;
    this.now = now;
  }

  get enabled() {
    return this.config.ai.voice === 'gemini';
  }

  checkQuota() {
    const rt = this.config.openai.realtime; // mismos límites de sesiones que la voz de OpenAI
    const stats = this.usage.realtimeSessions();
    if (stats.lastHour >= rt.maxSessionsPerHour) throw Errors.rateLimited('Límite de sesiones de voz por hora alcanzado (control de costos).', 600);
    if (stats.today >= rt.maxSessionsPerDay) throw Errors.rateLimited('Límite diario de sesiones de voz alcanzado (control de costos).', 3600);
  }

  wsUrl() {
    const u = new URL(this.config.gemini.baseUrl);
    u.protocol = u.protocol === 'http:' ? 'ws:' : 'wss:';
    return `${u.origin}/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained`;
  }

  async createToken({ instructions, voice, tools = [] }) {
    if (!this.enabled) throw Errors.notConfigured('voz en vivo con Gemini (GEMINI_API_KEY)');
    this.checkQuota();
    const model = await this.gemini.liveModel();
    const setup = {
      model: `models/${model}`,
      generationConfig: {
        responseModalities: ['AUDIO'],
        speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice || this.config.gemini.voice } } },
      },
      systemInstruction: { parts: [{ text: instructions || 'Eres J.A.R.V.I.S., un asistente en español.' }] },
      inputAudioTranscription: {},
      outputAudioTranscription: {},
    };
    if (tools.length) setup.tools = [{ functionDeclarations: functionDeclarations(tools) }];
    const now = this.now();
    const body = {
      uses: 1,
      expireTime: new Date(now + TOKEN_MINUTES * 60_000).toISOString(),
      newSessionExpireTime: new Date(now + START_MINUTES * 60_000).toISOString(),
      bidiGenerateContentSetup: setup,
    };
    const r = await this.gemini.call('auth_tokens', { body, version: 'v1alpha', timeout: 20_000 });
    if (typeof r?.name !== 'string' || !r.name.startsWith('auth_tokens/')) {
      throw new AppError(502, 'upstream_error', 'Gemini no entregó un token de voz válido.');
    }
    this.usage.recordRealtimeSession();
    this.logger?.info('gemini_live_token_created', { model, tools: tools.length });
    return {
      provider: 'gemini',
      token: r.name,
      wsUrl: this.wsUrl(),
      model,
      setup,
      expiresAt: body.expireTime,
      maxSessionMinutes: 10,
    };
  }
}
