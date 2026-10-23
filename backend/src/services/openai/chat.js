// Cerebro de texto: proxy controlado hacia la API Responses con function calling.
// El dispositivo es quien declara y ejecuta las herramientas; este servicio solo
// reenvía el turno, sin estado (store:false) y sin registrar el contenido.
import { Errors } from '../../http/errors.js';
import { mapOpenAIError } from './realtime.js';

// FAST/BALANCED usan el esfuerzo de razonamiento configurado; DEEP lo sube a "medium".
const MODE = {
  FAST: { maxOutputTokens: 500, effort: null },
  BALANCED: { maxOutputTokens: 1500, effort: null },
  DEEP: { maxOutputTokens: 4000, effort: 'medium' },
};

export class ChatService {
  constructor({ config, http, logger, usage }) {
    this.config = config;
    this.http = http;
    this.logger = logger;
    this.usage = usage;
  }

  get enabled() {
    return Boolean(this.config.openai.apiKey);
  }

  async respond({ instructions, input, tools = [], mode = 'BALANCED' }) {
    if (!this.enabled) throw Errors.notConfigured('chat con IA (OPENAI_API_KEY)');
    if (this.usage.chatRequestsToday() >= this.config.openai.text.maxRequestsPerDay) {
      throw Errors.rateLimited('Límite diario de peticiones de chat alcanzado (control de costos).', 3600);
    }
    const textCfg = this.config.openai.text;
    const m = MODE[mode] ?? MODE.BALANCED;
    const effort = textCfg.reasoningEffort ? (m.effort ?? textCfg.reasoningEffort) : null;
    const body = {
      model: textCfg.model,
      instructions,
      input,
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
    this.usage.recordChat();
    const res = await this.http.request('OpenAI', `${this.config.openai.baseUrl}/responses`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.config.openai.apiKey}`, 'content-type': 'application/json' },
      body: JSON.stringify(body),
      mapError: mapOpenAIError,
      timeout: mode === 'DEEP' ? 120000 : 60000,
    });
    return parseResponse(res.json());
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
