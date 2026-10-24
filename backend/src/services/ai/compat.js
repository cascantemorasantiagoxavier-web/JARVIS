// Groq (Llama, gpt-oss, Kimi…) u otro proveedor compatible con la API Chat Completions de OpenAI:
// Groq por defecto, o Cerebras, OpenRouter, Meta Llama API, Together… cambiando LLAMA_BASE_URL. Mismo formato
// de salida que /api/chat con OpenAI, para que la web y la app no cambien.
import { AppError, Errors } from '../../http/errors.js';

const PROVIDER = 'Llama';

export function mapCompatError(status, text) {
  let message = '';
  try {
    message = String(JSON.parse(text)?.error?.message ?? '');
  } catch {
    /* cuerpo no JSON */
  }
  if (status === 401) return Errors.upstreamAuth(PROVIDER);
  if (status === 403) return new AppError(502, 'upstream_forbidden', 'El proveedor de Llama rechazó la clave o el modelo.');
  if (status === 429) return new AppError(429, 'upstream_quota', 'Se alcanzó el límite de uso del proveedor de Llama. Prueba en un rato.');
  if (status === 404 || /model/i.test(message)) return new AppError(502, 'upstream_model', 'El modelo de Llama configurado no existe en ese proveedor.');
  if (status === 400 || status === 422) return new AppError(502, 'upstream_rejected', 'El proveedor de Llama rechazó la petición.');
  return undefined;
}

function textOf(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((p) => typeof p?.text === 'string')
    .map((p) => p.text)
    .join('\n');
}

/** Items de la API Responses → mensajes de Chat Completions (las llamadas de una ronda van juntas). */
export function toChatMessages(instructions, input) {
  const messages = instructions ? [{ role: 'system', content: instructions }] : [];
  for (const item of input) {
    if (item.type === 'reasoning') continue;
    if (item.type === 'function_call') {
      const last = messages.at(-1);
      const call = { id: item.call_id, type: 'function', function: { name: item.name, arguments: item.arguments || '{}' } };
      if (last?.role === 'assistant' && !last.closed) (last.tool_calls ??= []).push(call);
      else messages.push({ role: 'assistant', content: null, tool_calls: [call] });
    } else if (item.type === 'function_call_output') {
      messages.push({ role: 'tool', tool_call_id: item.call_id, content: String(item.output ?? '') });
    } else {
      const text = textOf(item.content);
      if (!text) continue;
      if (item.role === 'assistant') messages.push({ role: 'assistant', content: text });
      else messages.push({ role: 'user', content: text });
    }
  }
  return messages;
}

export function fromChatCompletion(body) {
  const choice = body?.choices?.[0];
  const msg = choice?.message ?? {};
  const text = typeof msg.content === 'string' ? msg.content.trim() : '';
  const toolCalls = (msg.tool_calls ?? [])
    .filter((c) => c?.function?.name)
    .map((c, i) => ({ callId: c.id || `call_${Date.now().toString(36)}_${i}`, name: c.function.name, arguments: c.function.arguments || '{}' }));
  const outputItems = [];
  if (text) outputItems.push({ type: 'message', role: 'assistant', content: [{ type: 'output_text', text }] });
  for (const c of toolCalls) outputItems.push({ type: 'function_call', call_id: c.callId, name: c.name, arguments: c.arguments });
  return {
    status: choice?.finish_reason === 'length' ? 'incomplete' : 'completed',
    incompleteReason: choice?.finish_reason === 'length' ? 'max_output_tokens' : null,
    text,
    toolCalls,
    outputItems,
    usage: body?.usage ? { inputTokens: body.usage.prompt_tokens ?? 0, outputTokens: body.usage.completion_tokens ?? 0 } : null,
    model: body?.model ?? null,
  };
}

const MODE_TOKENS = { FAST: 1024, BALANCED: 2048, DEEP: 4096 };

export class CompatClient {
  constructor({ config, http }) {
    this.cfg = config.llama;
    this.http = http;
    this.modelCache = null;
  }

  get enabled() {
    return Boolean(this.cfg.apiKey);
  }

  async call(path, { method = 'POST', body, form, timeout = 60_000 } = {}) {
    const headers = { authorization: `Bearer ${this.cfg.apiKey}` };
    if (body) headers['content-type'] = 'application/json';
    const res = await this.http.request(PROVIDER, `${this.cfg.baseUrl}/${path}`, {
      method,
      headers,
      body: form ?? (body ? JSON.stringify(body) : undefined),
      mapError: mapCompatError,
      timeout,
    });
    return res.json();
  }

  /** Modelos del proveedor (caché 6 h) para elegir uno de Llama cuando el modelo es "auto". */
  async models() {
    if (this.modelCache && Date.now() - this.modelCache.at < 6 * 3_600_000) return this.modelCache.list;
    const r = await this.call('models', { method: 'GET', timeout: 20_000 });
    const list = (r.data ?? []).map((m) => String(m.id ?? '')).filter(Boolean);
    this.modelCache = { at: Date.now(), list };
    return list;
  }

  async pick(setting, prefer, what) {
    if (setting === 'none') return null;
    if (setting !== 'auto') return setting;
    const list = await this.models();
    const scored = list.map((id) => ({ id, s: prefer(id.toLowerCase()) })).filter((x) => x.s > 0).sort((a, b) => b.s - a.s || b.id.localeCompare(a.id));
    if (!scored.length) throw new AppError(503, 'not_configured', `El proveedor de Llama no ofrece un modelo para ${what}.`);
    return scored[0].id;
  }

  /**
   * Modelo de chat con "auto": el mejor para usar herramientas entre los que ofrece el proveedor (Groq,
   * Cerebras, OpenRouter…). Se descartan los de seguridad, audio, voz e incrustaciones.
   */
  chatModel() {
    return this.pick(this.cfg.model, (id) => {
      if (/guard|whisper|tts|playai|orpheus|embed|vision|moderation|compound|distil/.test(id)) return 0;
      const ranked = [
        [/gpt-oss-120b/, 10],
        [/kimi-k2/, 9],
        [/llama-4-maverick/, 8],
        [/qwen3?-?235b|qwen-3-235b/, 8],
        [/llama-3\.3-70b/, 7],
        [/deepseek-v3/, 7],
        [/qwen3-32b|qwen-qwq/, 6],
        [/llama-4-scout/, 5],
        [/gpt-oss-20b/, 4],
        [/llama/, 2],
      ];
      return ranked.find(([re]) => re.test(id))?.[1] ?? 0;
    }, 'chat');
  }

  visionModel() {
    return this.pick(this.cfg.visionModel, (id) => (/llama-4|vision|scout|maverick/.test(id) && !/guard/.test(id) ? 1 + (/maverick/.test(id) ? 1 : 0) : 0), 'fotos');
  }

  transcribeModel() {
    return this.pick(this.cfg.transcribeModel, (id) => (/whisper/.test(id) ? 1 + (/turbo/.test(id) ? 1 : 0) : 0), 'dictado');
  }

  async chat({ instructions, input, tools = [], mode = 'BALANCED' }) {
    const body = {
      model: await this.chatModel(),
      messages: toChatMessages(instructions, input),
      max_tokens: MODE_TOKENS[mode] ?? MODE_TOKENS.BALANCED,
    };
    if (tools.length) {
      body.tools = tools.map((t) => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } }));
      body.tool_choice = 'auto';
    }
    const raw = await this.call('chat/completions', { body, timeout: mode === 'DEEP' ? 120_000 : 60_000 });
    return { ...fromChatCompletion(raw), provider: 'llama' };
  }

  async vision({ prompt, question, imageBase64, mimeType, json }) {
    const body = {
      model: await this.visionModel(),
      messages: [
        { role: 'system', content: prompt },
        { role: 'user', content: [{ type: 'text', text: question ? `Pregunta del usuario: ${question}` : 'Analiza la imagen.' }, { type: 'image_url', image_url: { url: `data:${mimeType};base64,${imageBase64}` } }] },
      ],
      max_tokens: 3000,
      ...(json ? { response_format: { type: 'json_object' } } : {}),
    };
    return fromChatCompletion(await this.call('chat/completions', { body, timeout: 90_000 }));
  }

  async transcribe({ buffer, mimeType, ext, language }) {
    const model = await this.transcribeModel();
    if (!model) throw Errors.notConfigured('dictado con Llama (LLAMA_TRANSCRIBE_MODEL=none)');
    const form = new FormData();
    form.append('file', new Blob([buffer], { type: mimeType }), `nota.${ext}`);
    form.append('model', model);
    if (language) form.append('language', language);
    form.append('response_format', 'json');
    const out = await this.call('audio/transcriptions', { form, timeout: 120_000 });
    if (typeof out?.text !== 'string') throw Errors.upstream(PROVIDER, new Error('respuesta sin text'));
    return out.text.trim();
  }
}
