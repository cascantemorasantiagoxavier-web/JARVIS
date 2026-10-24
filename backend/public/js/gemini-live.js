// Voz en vivo con Gemini Live (WebSocket). El servidor entrega un token efímero de un solo uso con la
// configuración bloqueada (modelo, voz, instrucciones, herramientas); la clave de Gemini nunca llega aquí.
// Micrófono → PCM 16 kHz (AudioWorklet) → realtimeInput; audio de Gemini (PCM 24 kHz) → altavoz.
// Las herramientas usan el MISMO ToolBox que el chat (mismas confirmaciones). Solo arranca con «Hablar».
import { api } from './api.js';
import { ToolBox } from './tools.js';
import { settings } from './store.js';

const IDLE_MS = 30_000;
const MAX_MINUTES = 10;
const MAX_INSTRUCTIONS = 11_500;

export const GEMINI_VOICES = ['Charon', 'Puck', 'Kore', 'Fenrir', 'Aoede', 'Leda', 'Orus', 'Zephyr'];

function toBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

function fromBase64(b64) {
  const s = atob(b64);
  const bytes = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i);
  return bytes;
}

export class GeminiVoice {
  /** Misma interfaz que Voice (realtime.js): start, stop, requestEnd, setMuted, level, active. */
  constructor({ toolbox, brain, onState, onTranscript, onCards, onError }) {
    Object.assign(this, { toolbox, brain, onState, onTranscript, onCards, onError });
    this.state = 'idle';
    this.timers = {};
    this.reset();
    this.levelBuf = new Uint8Array(256);
  }

  reset() {
    Object.assign(this, {
      ws: null, ctx: null, stream: null, worklet: null, outGain: null, analysers: [], sources: new Set(),
      playHead: 0, ready: false, muted: false, speaking: false, pendingTools: 0, endRequested: false,
      turnDone: false, userText: '', botText: '', cancelled: new Set(),
    });
  }

  get active() {
    return this.state !== 'idle';
  }

  static supported() {
    return Boolean(window.WebSocket && window.AudioWorkletNode && navigator.mediaDevices?.getUserMedia);
  }

  setState(s, detail) {
    this.state = s;
    this.onState(s, detail);
  }

  async start() {
    if (this.active) return;
    if (!GeminiVoice.supported()) throw new Error('Este navegador no soporta la voz en vivo.');
    this.reset();
    this.setState('connecting');
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
    } catch {
      this.setState('idle');
      throw new Error('Sin permiso de micrófono. Actívalo en el candado de la barra de direcciones.');
    }
    try {
      const t = await api.liveToken({
        instructions: this.brain.instructions({ voice: true }).slice(0, MAX_INSTRUCTIONS),
        voice: settings.get().geminiVoice,
        tools: this.toolbox.modelTools({ voice: true }),
      });
      await this.setupAudio();
      this.ws = new WebSocket(`${t.wsUrl}?access_token=${encodeURIComponent(t.token)}`);
      this.ws.onopen = () => this.send({ setup: t.setup });
      this.ws.onmessage = async (e) => {
        try {
          this.handle(JSON.parse(typeof e.data === 'string' ? e.data : await e.data.text()));
        } catch {
          /* mensaje no JSON: se ignora */
        }
      };
      this.ws.onerror = () => this.active && this.stop('No se pudo conectar con la voz de Gemini.');
      this.ws.onclose = (e) => {
        if (!this.active) return;
        this.stop(e.code === 1000 ? undefined : `La voz de Gemini se desconectó${e.reason ? `: ${e.reason}` : '.'}`);
      };
      this.timers.connect = setTimeout(() => !this.ready && this.stop('Gemini no respondió a tiempo. Prueba de nuevo.'), 15_000);
      this.timers.max = setTimeout(() => this.stop('Duración máxima de la conversación (10 min).'), MAX_MINUTES * 60_000);
    } catch (err) {
      this.stop();
      throw err;
    }
  }

  async setupAudio() {
    this.ctx = new AudioContext();
    await this.ctx.audioWorklet.addModule('/js/pcm-worklet.js');
    const mic = this.ctx.createMediaStreamSource(this.stream);
    this.worklet = new AudioWorkletNode(this.ctx, 'pcm-capture', { processorOptions: { targetRate: 16000, chunk: 1600 } });
    const sink = this.ctx.createGain();
    sink.gain.value = 0; // mantiene el worklet activo sin oír el propio micrófono
    mic.connect(this.worklet).connect(sink).connect(this.ctx.destination);
    this.worklet.port.onmessage = (e) => {
      if (!this.ready || this.muted || !(e.data instanceof ArrayBuffer)) return;
      this.send({ realtimeInput: { audio: { data: toBase64(e.data), mimeType: 'audio/pcm;rate=16000' } } });
    };
    this.outGain = this.ctx.createGain();
    this.outGain.connect(this.ctx.destination);
    for (const node of [mic, this.outGain]) {
      const a = this.ctx.createAnalyser();
      a.fftSize = 256;
      node.connect(a);
      this.analysers.push(a);
    }
    if (this.ctx.state === 'suspended') await this.ctx.resume();
  }

  send(msg) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  armIdle() {
    clearTimeout(this.timers.idle);
    this.timers.idle = setTimeout(() => {
      if (!this.speaking && this.pendingTools === 0) this.stop('Cerré la voz por inactividad.');
      else this.armIdle();
    }, IDLE_MS);
  }

  handle(msg) {
    if (msg.setupComplete) {
      this.ready = true;
      clearTimeout(this.timers.connect);
      this.setState('listening');
      this.armIdle();
    }
    const sc = msg.serverContent;
    if (sc) {
      if (sc.inputTranscription?.text) {
        this.userText += sc.inputTranscription.text;
        this.armIdle();
        if (this.state === 'listening') this.setState('listening', 'Te escucho…');
      }
      const parts = sc.modelTurn?.parts ?? [];
      if (parts.length) {
        this.flushUser();
        this.turnDone = false;
        for (const p of parts) {
          const mime = p.inlineData?.mimeType ?? '';
          if (p.inlineData?.data && mime.startsWith('audio/pcm')) this.play(p.inlineData.data, Number(/rate=(\d+)/.exec(mime)?.[1] ?? 24000));
        }
      }
      if (sc.outputTranscription?.text) this.botText += sc.outputTranscription.text;
      if (sc.interrupted) {
        this.stopPlayback();
        this.flushBot();
        this.setState('listening');
      }
      if (sc.turnComplete) {
        this.flushUser();
        this.flushBot();
        this.turnDone = true;
        if (!this.sources.size) this.afterTurn();
      }
    }
    if (msg.toolCall?.functionCalls?.length) this.runTools(msg.toolCall.functionCalls);
    for (const id of msg.toolCallCancellation?.ids ?? []) this.cancelled.add(id);
    if (msg.goAway) this.stop('La sesión de voz de Gemini llegó a su límite de tiempo.');
  }

  flushUser() {
    const t = this.userText.trim();
    this.userText = '';
    if (t) this.onTranscript('user', t);
  }

  flushBot() {
    const t = this.botText.trim();
    this.botText = '';
    if (t) this.onTranscript('assistant', t);
  }

  play(b64, rate) {
    const bytes = fromBase64(b64);
    const pcm = new Int16Array(bytes.buffer, 0, Math.floor(bytes.length / 2));
    if (!pcm.length) return;
    const buf = this.ctx.createBuffer(1, pcm.length, rate);
    const ch = buf.getChannelData(0);
    for (let i = 0; i < pcm.length; i++) ch[i] = pcm[i] / 0x8000;
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    src.connect(this.outGain);
    const at = Math.max(this.ctx.currentTime + 0.03, this.playHead);
    src.start(at);
    this.playHead = at + buf.duration;
    this.sources.add(src);
    src.onended = () => {
      this.sources.delete(src);
      if (!this.sources.size) {
        this.speaking = false;
        if (this.turnDone) this.afterTurn();
      }
    };
    if (!this.speaking) {
      this.speaking = true;
      this.setState('speaking');
    }
  }

  stopPlayback() {
    for (const s of this.sources) {
      s.onended = null;
      try {
        s.stop();
      } catch {
        /* ya terminó */
      }
    }
    this.sources.clear();
    this.playHead = 0;
    this.speaking = false;
  }

  afterTurn() {
    this.armIdle();
    if (this.endRequested) return this.stop();
    if (this.pendingTools === 0 && this.active) this.setState('listening');
  }

  /** Ejecuta las llamadas con el mismo ToolBox del chat y devuelve todas las respuestas juntas. */
  async runTools(calls) {
    this.pendingTools += calls.length;
    this.setState('thinking', `Usando ${calls.map((c) => c.name).join(', ')}…`);
    const functionResponses = [];
    for (const c of calls) {
      const result = await this.toolbox.run(c.name, c.args ?? {}, { voice: true });
      this.pendingTools--;
      if (!this.active) return;
      if (this.cancelled.has(c.id)) continue;
      if (result.cards?.length) this.onCards(result.cards);
      let response;
      try {
        response = JSON.parse(ToolBox.forModel(result));
      } catch {
        response = { output: ToolBox.forModel(result) };
      }
      functionResponses.push({ id: c.id, name: c.name, response });
    }
    if (functionResponses.length) this.send({ toolResponse: { functionResponses } });
    this.armIdle();
  }

  /** end_conversation: colgar cuando termine de sonar la despedida (máximo 10 s). */
  requestEnd() {
    this.endRequested = true;
    clearTimeout(this.timers.end);
    this.timers.end = setTimeout(() => this.stop(), 10_000);
  }

  setMuted(muted) {
    this.muted = muted;
  }

  level() {
    let max = 0;
    for (const a of this.analysers) {
      a.getByteTimeDomainData(this.levelBuf);
      for (let i = 0; i < this.levelBuf.length; i++) max = Math.max(max, Math.abs(this.levelBuf[i] - 128));
    }
    return Math.min(1, max / 64);
  }

  stop(reason) {
    const wasActive = this.active;
    for (const t of Object.values(this.timers)) clearTimeout(t);
    this.timers = {};
    this.stopPlayback();
    try {
      if (this.ws && this.ws.readyState <= WebSocket.OPEN) this.ws.close(1000);
    } catch {
      /* ya cerrado */
    }
    if (this.ws) this.ws.onclose = null;
    for (const t of this.stream?.getTracks() ?? []) t.stop();
    this.ctx?.close().catch(() => {});
    this.reset();
    this.state = 'idle';
    if (wasActive) this.onState('idle', reason);
  }
}
