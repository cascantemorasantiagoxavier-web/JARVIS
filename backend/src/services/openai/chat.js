// Cerebro de texto: proxy controlado hacia el modelo con function calling. El dispositivo declara y
// ejecuta las herramientas; este servicio solo reenvía el turno, sin estado y sin registrar el contenido.
// Proveedores (config.ai.order): Gemini, OpenAI (API Responses) y Llama (compatible con OpenAI). Si el
// primero falla por cuota, caída o tiempo, se intenta el siguiente.
import { Errors } from '../../http/errors.js';
import { mapOpenAIError } from './realtime.js';

// FAST/BALANCED usan el esfuerzo de razonamiento configurado; DEEP lo sube a "medium".
const MODE = {
  FAST: { maxOutputTokens: 500, effort: null },
  BALANCED: { maxOutputTokens: 1500, effort: null },
  DEEP: { maxOutputTokens: 4000, effort: 'medium' },
};

/** ¿Vale la pena probar con otro proveedor? (cuota, caída, tiempo; no errores de la propia petición). */
export function shouldFallback(err) {
  if (err?.code === 'rate_limited') return false; // límite propio de JARVIS
  return err?.status >= 429 || err?.code === 'not_configured';
}

/** Quita los datos propios de otros proveedores antes de enviar a la API Responses de OpenAI. */
export function forOpenAI(input) {
  return input.map(({ provider_data: _omit, ...rest }) => rest);
}

export class ChatService {
  constructor({ config, http, logger, usage, gemini, llama }) {
    this.config = config;
    this.http = http;
    this.logger = logger;
    this.usage = usage;
    this.clients = { gemini, llama };
  }

  get providers() {
    return this.config.ai.order;
  }

  get enabled() {
    return this.providers.length > 0;
  }

  async respond(req) {
    if (!this.enabled) throw Errors.notConfigured('chat con IA (GEMINI_API_KEY, OPENAI_API_KEY o LLAMA_API_KEY)');
    if (this.usage.chatRequestsToday() >= this.config.openai.text.maxRequestsPerDay) {
      throw Errors.rateLimited('Límite diario de peticiones de chat alcanzado (control de costos).', 3600);
    }
    this.usage.recordChat();
    let lastErr;
    for (const [i, provider] of this.providers.entries()) {
      try {
        return provider === 'openai' ? await this.openai(req) : await this.clients[provider].chat(req);
      } catch (err) {
        lastErr = err;
        if (i === this.providers.length - 1 || !shouldFallback(err)) throw err;
        this.logger?.warn('chat_provider_fallback', { from: provider, to: this.providers[i + 1], code: err?.code });
      }
    }
    throw lastErr;
  }

  async openai({ instructions, input, tools = [], mode = 'BALANCED' }) {
    const textCfg = this.config.openai.text;
    const m = MODE[mode] ?? MODE.BALANCED;
    const effort = textCfg.reasoningEffort ? (m.effort ?? textCfg.reasoningEffort) : null;
    const body = {
      model: textCfg.model,
      instructions,
      input: forOpenAI(input),
      tools: tools.map((t) => ({
        type: 'function',
        name: t.name,
        description: t.description,
        parameters: t.parameters,
        strict: false,
      })),
      tool_choice: tools.length ? 'auto' : undefined,
      parallel_tool_calls: tools.length ? true : undefined,
      max_output_tokens: m.maxOutputTokens,
      store: false,
    };
    if (effort) {
      body.reasoning = { effort };
      body.include = ['reasoning.encrypted_content'];
    }
    const res = await this.http.request('OpenAI', `${this.config.openai.baseUrl}/responses`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.config.openai.apiKey}`, 'content-type': 'application/json' },
      body: JSON.stringify(body),
      mapError: mapOpenAIError,
      timeout: mode === 'DEEP' ? 120000 : 60000,
    });
    return { ...parseResponse(res.json()), provider: 'openai' };
  }
}

/** Extrae texto, llamadas a herramientas y los items a reenviar en la siguiente ronda. */
export function parseResponse(body) {
  const output = Array.isArray(body?.output) ? body.output : [];
  const texts = [];
  const toolCalls = [];
  for (const item of output) {
    if (item.type === 'message') {
      for (const part of item.content ?? []) {
        if (part.type === 'output_text' && part.text) texts.push(part.text);
        if (part.type === 'refusal' && part.refusal) texts.push(part.refusal);
      }
    } else if (item.type === 'function_call') {
      toolCalls.push({ callId: item.call_id, name: item.name, arguments: item.arguments ?? '{}' });
    }
  }
  return {
    status: body?.status ?? 'unknown',
    incompleteReason: body?.incomplete_details?.reason ?? null,
    text: texts.join('\n').trim(),
    toolCalls,
    outputItems: output.filter((i) => ['message', 'function_call', 'reasoning'].includes(i.type)),
    usage: body?.usage
      ? { inputTokens: body.usage.input_tokens ?? 0, outputTokens: body.usage.output_tokens ?? 0 }
      : null,
    model: body?.model ?? null,
  };
}
