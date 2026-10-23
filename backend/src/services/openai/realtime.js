// Integración con OpenAI Realtime (API GA).
// Dos flujos oficiales soportados:
//  1) "ephemeral": el servidor crea un client secret (POST /v1/realtime/client_secrets)
//     y el teléfono hace el intercambio SDP directamente con /v1/realtime/calls.
//  2) "unified": el teléfono envía su oferta SDP a este backend, que la reenvía a
//     /v1/realtime/calls (multipart: sdp + session) con la clave permanente.
//     Ninguna credencial de OpenAI llega al teléfono. Es el modo por defecto.
// La clave permanente nunca sale del servidor ni se registra en logs.
import { AppError, Errors } from '../../http/errors.js';
import { clampInstructions } from './persona.js';

export function mapOpenAIError(status, text, headers) {
  let code;
  let type;
  try {
    const j = JSON.parse(text);
    code = j?.error?.code;
    type = j?.error?.type;
  } catch {
    /* cuerpo no JSON */
  }
  if (status === 401) return Errors.upstreamAuth('OpenAI');
  if (status === 403) return new AppError(502, 'upstream_forbidden', 'OpenAI denegó el acceso a este modelo o función para la cuenta configurada.');
  if (status === 429) {
    const retry = Number.parseInt(headers?.get?.('retry-after') ?? '', 10);
    if (code === 'insufficient_quota' || type === 'insufficient_quota') {
      return new AppError(429, 'upstream_quota', 'La cuenta de OpenAI no tiene saldo o cuota disponible.');
    }
    return Errors.upstreamQuota('OpenAI', Number.isFinite(retry) ? retry : undefined);
  }
  if (status === 400 || status === 404 || status === 422) {
    if (code === 'model_not_found' || /model/i.test(String(code ?? ''))) {
      return new AppError(502, 'upstream_model', 'El modelo configurado no está disponible para esta cuenta.');
    }
    return new AppError(502, 'upstream_rejected', 'OpenAI rechazó la configuración de la petición.');
  }
  return undefined;
}

export class RealtimeService {
  constructor({ config, http, logger, usage, now = () => Date.now(), setTimer = setTimeout, clearTimer = clearTimeout }) {
    this.config = config;
    this.http = http;
    this.logger = logger;
    this.usage = usage;
    this.now = now;
    this.setTimer = setTimer;
    this.clearTimer = clearTimer;
    this.activeCalls = new Map();
  }

  get enabled() {
    return Boolean(this.config.openai.apiKey);
  }

  requireEnabled() {
    if (!this.enabled) throw Errors.notConfigured('voz Realtime (OPENAI_API_KEY)');
  }

  resolveModel(requested) {
    const rt = this.config.openai.realtime;
    if (!requested) return rt.model;
    if (!rt.allowedModels.includes(requested)) {
      throw Errors.badRequest(`Modelo Realtime no permitido. Opciones: ${rt.allowedModels.join(', ')}.`);
    }
    return requested;
  }

  /** Configuración de sesión GA. Las herramientas las declara el dispositivo con session.update. */
  buildSession({ model, voice, speed, instructions, language = 'es', maxOutputTokens = 1500 } = {}) {
    const rt = this.config.openai.realtime;
    const session = {
      type: 'realtime',
      model: this.resolveModel(model),
      instructions: clampInstructions(instructions),
      output_modalities: ['audio'],
      max_output_tokens: maxOutputTokens,
      truncation: { type: 'retention_ratio', retention_ratio: 0.8 },
      audio: {
        input: {
          noise_reduction: { type: 'near_field' },
          turn_detection: {
            type: 'semantic_vad',
            eagerness: 'auto',
            create_response: true,
            interrupt_response: true,
          },
        },
        output: { voice: voice ?? rt.voice },
      },
    };
    if (rt.transcriptionModel) {
      session.audio.input.transcription = { model: rt.transcriptionModel, language };
    }
    if (typeof speed === 'number') session.audio.output.speed = speed;
    return session;
  }

  checkQuota() {
    const rt = this.config.openai.realtime;
    const stats = this.usage.realtimeSessions();
    if (stats.lastHour >= rt.maxSessionsPerHour) {
      throw Errors.rateLimited('Límite de sesiones de voz por hora alcanzado (control de costos).', 600);
    }
    if (stats.today >= rt.maxSessionsPerDay) {
      throw Errors.rateLimited('Límite diario de sesiones de voz alcanzado (control de costos).', 3600);
    }
  }

  /** Flujo 1: client secret efímero para el teléfono. */
  async createClientSecret(options = {}) {
    this.requireEnabled();
    this.checkQuota();
    const session = this.buildSession(options);
    const res = await this.http.request('OpenAI', `${this.config.openai.baseUrl}/realtime/client_secrets`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${this.config.openai.apiKey}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        expires_after: { anchor: 'created_at', seconds: this.config.openai.realtime.secretTtlSeconds },
        session,
      }),
      mapError: mapOpenAIError,
    });
    const body = res.json();
    if (typeof body?.value !== 'string' || !body.value) {
      throw Errors.upstream('OpenAI', new Error('client_secret sin value'));
    }
    this.usage.recordRealtimeSession();
    this.logger.info('realtime_secret_created', { model: session.model, voice: session.audio.output.voice });
    return {
      mode: 'ephemeral',
      clientSecret: body.value,
      expiresAt: body.expires_at ?? null,
      model: session.model,
      voice: session.audio.output.voice,
      callsUrl: `${this.config.openai.baseUrl}/realtime/calls`,
    };
  }

  /** Flujo 2 (unificado): el backend negocia el SDP con OpenAI. */
  async createCall({ sdp, maxSessionMinutes = 15, ...options }) {
    this.requireEnabled();
    if (typeof sdp !== 'string' || !sdp.startsWith('v=')) throw Errors.badRequest('Oferta SDP inválida.');
    this.checkQuota();
    const session = this.buildSession(options);
    const form = new FormData();
    form.set('sdp', new Blob([sdp], { type: 'application/sdp' }));
    form.set('session', new Blob([JSON.stringify(session)], { type: 'application/json' }));
    const res = await this.http.request('OpenAI', `${this.config.openai.baseUrl}/realtime/calls`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.config.openai.apiKey}`, accept: 'application/sdp' },
      body: form,
      okStatuses: [200, 201],
      mapError: mapOpenAIError,
    });
    if (!res.text.startsWith('v=')) throw Errors.upstream('OpenAI', new Error('respuesta SDP inválida'));
    const location = res.headers.get('location') ?? '';
    const callId = location.split('/').filter(Boolean).pop() || null;
    this.usage.recordRealtimeSession();
    if (callId) this.armWatchdog(callId, maxSessionMinutes);
    this.logger.info('realtime_call_created', { model: session.model, hasCallId: Boolean(callId) });
    return {
      mode: 'unified',
      sdp: res.text,
      callId,
      model: session.model,
      voice: session.audio.output.voice,
      maxSessionMinutes,
    };
  }

  /** Vigilante de costos: cuelga la llamada si el teléfono no la cerró a tiempo. */
  armWatchdog(callId, minutes) {
    const ms = Math.max(1, Math.min(minutes, 60)) * 60_000;
    const timer = this.setTimer(() => {
      this.hangup(callId, 'watchdog').catch((err) =>
        this.logger.warn('realtime_watchdog_hangup_failed', { cause: err?.message }),
      );
    }, ms);
    timer?.unref?.();
    this.activeCalls.set(callId, { startedAt: this.now(), timer });
  }

  async hangup(callId, reason = 'client') {
    this.requireEnabled();
    if (!/^[A-Za-z0-9_-]{4,128}$/.test(callId)) throw Errors.badRequest('Identificador de llamada inválido.');
    const entry = this.activeCalls.get(callId);
    if (entry) {
      this.clearTimer(entry.timer);
      this.activeCalls.delete(callId);
    }
    await this.http.request('OpenAI', `${this.config.openai.baseUrl}/realtime/calls/${callId}/hangup`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.config.openai.apiKey}` },
      okStatuses: [200, 204, 404],
      mapError: mapOpenAIError,
    });
    this.logger.info('realtime_call_hangup', { reason });
    return { ok: true };
  }

  shutdown() {
    for (const { timer } of this.activeCalls.values()) this.clearTimer(timer);
    this.activeCalls.clear();
  }
}
