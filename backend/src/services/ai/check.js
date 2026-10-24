// Diagnóstico de la IA (Ajustes → «Probar la IA»): hace llamadas mínimas reales al proveedor y
// devuelve qué funciona y, si algo falla, el motivo (con el detalle que da Google). Sin datos personales.
import { readFileSync } from 'node:fs';
import { WEB_TOOLS } from '../../../public/js/tools.js';

const TOOLS_FILE = new URL('../../../public/shared/tools.json', import.meta.url);

let webTools;
function loadWebTools() {
  if (!webTools) {
    const all = JSON.parse(readFileSync(TOOLS_FILE, 'utf8')).tools;
    webTools = all
      .filter((t) => WEB_TOOLS.includes(t.name) && t.name !== 'end_conversation')
      .map((t) => ({ name: t.name, description: t.description, parameters: t.parameters }));
  }
  return webTools;
}

export async function aiCheck({ config, chat, gemini, now = () => Date.now() }) {
  const checks = [];
  const run = async (name, fn) => {
    const t0 = now();
    try {
      checks.push({ name, ok: true, ms: now() - t0, ...(await fn()) });
    } catch (err) {
      checks.push({ name, ok: false, ms: now() - t0, code: err?.code ?? 'error', error: err?.message ?? 'Error desconocido.' });
    }
    checks.at(-1).ms = now() - t0;
  };
  if (!chat.enabled) {
    return { providers: [], voice: config.ai.voice, checks: [{ name: 'chat', ok: false, code: 'not_configured', error: 'No hay ninguna clave de IA en el servidor (GEMINI_API_KEY).' }] };
  }
  await run('chat', async () => {
    const r = await chat.respond({ instructions: 'Responde solo con la palabra OK.', input: [{ role: 'user', content: 'Prueba de conexión.' }], mode: 'FAST' });
    return { provider: r.provider, model: r.model, text: String(r.text ?? '').slice(0, 60) };
  });
  await run('chat_tools', async () => {
    const r = await chat.respond({
      instructions: 'Eres un asistente. Si te preguntan la hora, llama a get_datetime.',
      input: [{ role: 'user', content: '¿Qué hora es?' }],
      tools: loadWebTools(),
      mode: 'FAST',
    });
    return { provider: r.provider, model: r.model, toolCalls: r.toolCalls.map((c) => c.name), text: String(r.text ?? '').slice(0, 60) };
  });
  if (config.ai.voice === 'gemini') await run('voice_model', async () => ({ model: await gemini.liveModel() }));
  return { providers: config.ai.order, voice: config.ai.voice, checks };
}
