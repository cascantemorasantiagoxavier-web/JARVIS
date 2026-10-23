// Cerebro de JARVIS Web: el mismo esquema que la app (persona + contexto + bucle de herramientas con
// la API Responses a través de /api/chat). Las herramientas se ejecutan en el navegador con ToolBox;
// los recuerdos privados nunca se envían al modelo.
import { api, ApiError } from './api.js';
import { calculate } from './calc.js';
import { formatDateTime } from './dates.js';
import { ToolBox } from './tools.js';
import { chatLog, memory, tasks } from './store.js';

const MAX_ROUNDS = 6;
const HISTORY_TURNS = 12;

const WEB_LIMITS = `Versión web
- Funcionas en un navegador: no tienes acceso a notas de Obsidian, cámara, contactos, SMS ni alarmas del teléfono.
- Llamar, abrir rutas, compartir o crear eventos solo deja un botón en pantalla; la persona lo toca.
- Los recordatorios de tareas solo suenan si la página está abierta.
- Para clips de video usa clips_create, clips_status y clips_publish (OpusClip publica en YouTube). Publica en privado salvo que la persona pida otra cosa, y recuérdale que solo suba contenido propio o con permiso.`;

export class Brain {
  /**
   * @param {{toolbox: ToolBox, persona: string, now?: () => Date}} opts
   */
  constructor({ toolbox, persona, now = () => new Date() }) {
    this.toolbox = toolbox;
    this.persona = persona;
    this.now = now;
    this.busy = false;
  }

  /** Persona + fecha + recuerdos no privados + tareas pendientes + límites de la web. */
  instructions({ voice = false } = {}) {
    const now = this.now();
    const memories = memory.all().filter((m) => !m.private).slice(-30);
    const pending = tasks.all().filter((t) => !t.done).slice(0, 15);
    const ctx = [
      `Contexto actual\n- Fecha y hora local: ${formatDateTime(now)} (${Intl.DateTimeFormat().resolvedOptions().timeZone}).`,
      memories.length ? `Recuerdos guardados por la persona (ids para update_memory/forget):\n${memories.map((m) => `- [${m.id}] ${m.content}`).join('\n')}` : 'Sin recuerdos guardados.',
      pending.length ? `Tareas pendientes:\n${pending.map((t) => `- [${t.id}] ${t.title}${t.due ? ` (${formatDateTime(new Date(t.due), t.hasTime)})` : ''}`).join('\n')}` : 'Sin tareas pendientes.',
      WEB_LIMITS,
      voice ? 'Estás en una conversación de voz: frases cortas.' : 'Estás en el chat: puedes usar listas breves y enlaces en Markdown.',
    ];
    return `${this.persona.trim()}\n\n${ctx.join('\n\n')}`;
  }

  /** Últimos turnos de texto para dar continuidad (sin tarjetas ni resultados de herramientas). */
  history() {
    return chatLog
      .all()
      .filter((e) => (e.role === 'user' || e.role === 'assistant') && e.text)
      .slice(-HISTORY_TURNS)
      .map((e) => ({ role: e.role, content: e.text }));
  }

  /**
   * Responde a un mensaje escrito. `history`: turnos previos (por defecto, el chat guardado).
   * @returns {Promise<{text: string, cards: object[], tools: string[], local: boolean}>}
   */
  async ask(text, { onStatus = () => {}, history = this.history() } = {}) {
    const input = [...history, { role: 'user', content: text }];
    const cards = [];
    const used = [];
    for (let round = 0; round < MAX_ROUNDS; round++) {
      onStatus(round === 0 ? 'Pensando…' : 'Procesando resultados…');
      let res;
      try {
        res = await api.chat({ instructions: this.instructions(), input, tools: this.toolbox.modelTools(), mode: 'BALANCED' });
      } catch (err) {
        if (err instanceof ApiError && (err.status === 503 || err.code === 'no_token')) {
          const local = await this.local(text);
          if (local) return { ...local, local: true, notice: err.message };
        }
        throw err;
      }
      if (!res.toolCalls?.length) {
        return { text: res.text || 'No tengo una respuesta para eso.', cards, tools: used, local: false };
      }
      // Los razonamientos sin contenido cifrado no se pueden reenviar con store:false.
      input.push(...res.outputItems.filter((i) => i.type !== 'reasoning' || i.encrypted_content));
      for (const call of res.toolCalls) {
        onStatus(`Usando ${call.name}…`);
        const result = await this.toolbox.run(call.name, call.arguments);
        used.push(call.name);
        cards.push(...result.cards);
        input.push({ type: 'function_call_output', call_id: call.callId, output: ToolBox.forModel(result) });
      }
    }
    return { text: 'Usé demasiadas herramientas seguidas y me detuve para no gastar de más. ¿Puedes concretar la petición?', cards, tools: used, local: false };
  }

  /** Respuestas sin IA cuando el servidor no tiene chat configurado: hora, cálculos y clima. */
  async local(text) {
    const t = text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
    if (/\b(que hora|hora es|que dia|fecha de hoy|que fecha)\b/.test(t)) {
      const r = await this.toolbox.run('get_datetime', {});
      return { text: r.message, cards: r.cards, tools: ['get_datetime'] };
    }
    const expr = t.replace(/^(cuanto es|calcula|calcular)\s+/, '').replace(/\?$/, '');
    if (/^[\d\s+\-*/^().,%x×÷]+$/.test(expr) && /\d/.test(expr) && /[+\-*/^%x×÷]/.test(expr)) {
      try {
        const v = calculate(expr.replace(/x/g, '*'));
        return { text: `${expr.trim()} = ${v.toLocaleString('es-CR')}`, cards: [], tools: ['calculate'] };
      } catch {
        /* no era un cálculo */
      }
    }
    if (/\b(clima|tiempo|temperatura|llover|lluvia)\b/.test(t)) {
      const place = text.match(/\ben\s+([A-Za-zÁÉÍÓÚáéíóúñÑ .,-]{3,60})\??$/)?.[1];
      const r = await this.toolbox.run('get_weather', place ? { place } : {});
      return { text: r.message, cards: r.cards, tools: ['get_weather'] };
    }
    return null;
  }
}
