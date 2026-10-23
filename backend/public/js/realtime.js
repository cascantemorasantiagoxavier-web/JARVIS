// Voz de JARVIS Web con OpenAI GPT Realtime por WebRTC (modo unificado): el navegador envía su oferta
// SDP a /api/realtime/call y el servidor la reenvía a OpenAI con la clave, que nunca llega aquí.
// Solo arranca cuando la persona toca «Hablar». Cierra sola por inactividad o duración máxima.
import { api } from './api.js';
import { ToolBox } from './tools.js';
import { settings } from './store.js';

const IDLE_MS = 30_000;
const MAX_MINUTES = 10;
const MAX_INSTRUCTIONS = 11_500;

export class Voice {
  /**
   * @param {{toolbox: ToolBox, brain: import('./brain.js').Brain,
   *   onState(state: string, detail?: string): void,
   *   onTranscript(role: 'user'|'assistant', text: string): void,
   *   onCards(cards: object[]): void,
   *   onError(message: string): void}} opts
   * Estados: idle, connecting, listening, thinking, speaking.
   */
  constructor({ toolbox, brain, onState, onTranscript, onCards, onError }) {
    Object.assign(this, { toolbox, brain, onState, onTranscript, onCards, onError });
    this.state = 'idle';
    this.pc = null;
    this.dc = null;
    this.stream = null;
    this.audio = null;
    this.callId = null;
    this.timers = {};
    this.speaking = false;
    this.pendingTools = 0;
    this.endRequested = false;
    this.analysers = [];
    this.levelBuf = new Uint8Array(256);
  }

  get active() {
    return this.state !== 'idle';
  }

  static supported() {
    return Boolean(window.RTCPeerConnection && navigator.mediaDevices?.getUserMedia);
  }

  setState(s, detail) {
    this.state = s;
    this.onState(s, detail);
  }

  async start() {
    if (this.active) return;
    if (!Voice.supported()) throw new Error('Este navegador no soporta voz en tiempo real (WebRTC).');
    this.endRequested = false;
    this.setState('connecting');
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
    } catch {
      this.setState('idle');
      throw new Error('Sin permiso de micrófono. Actívalo en el candado de la barra de direcciones.');
    }
    try {
      this.pc = new RTCPeerConnection();
      this.audio = new Audio();
      this.audio.autoplay = true;
      this.pc.ontrack = (e) => {
        this.audio.srcObject = e.streams[0];
        this.watchLevel(e.streams[0]);
      };
      this.pc.onconnectionstatechange = () => {
        if (['failed', 'disconnected'].includes(this.pc?.connectionState)) this.stop('Se perdió la conexión de voz.');
      };
      const track = this.stream.getAudioTracks()[0];
      this.pc.addTrack(track, this.stream);
      this.watchLevel(this.stream);
      this.dc = this.pc.createDataChannel('oai-events');
      this.dc.onopen = () => this.configure();
      this.dc.onmessage = (e) => this.handle(e.data);
      const offer = await this.pc.createOffer();
      await this.pc.setLocalDescription(offer);
      const r = await api.realtimeCall({
        sdp: offer.sdp,
        voice: settings.get().voice,
        instructions: this.brain.instructions({ voice: true }).slice(0, MAX_INSTRUCTIONS),
        maxSessionMinutes: MAX_MINUTES,
      });
      this.callId = r.callId;
      await this.pc.setRemoteDescription({ type: 'answer', sdp: r.sdp });
      this.timers.max = setTimeout(() => this.stop('Duración máxima de la conversación (10 min).'), MAX_MINUTES * 60_000);
    } catch (err) {
      this.stop();
      throw err;
    }
  }

  /** Instrucciones y herramientas tras conectar (la voz se fijó al crear la llamada). */
  configure() {
    this.send({
      type: 'session.update',
      session: {
        type: 'realtime',
        instructions: this.brain.instructions({ voice: true }).slice(0, MAX_INSTRUCTIONS),
        tool_choice: 'auto',
        tools: this.toolbox.modelTools({ voice: true }).map((t) => ({ type: 'function', ...t })),
      },
    });
    this.setState('listening');
    this.armIdle();
  }

  send(event) {
    if (this.dc?.readyState === 'open') this.dc.send(JSON.stringify(event));
  }

  armIdle() {
    clearTimeout(this.timers.idle);
    this.timers.idle = setTimeout(() => {
      if (!this.speaking && this.pendingTools === 0) this.stop('Cerré la voz por inactividad.');
      else this.armIdle();
    }, IDLE_MS);
  }

  handle(raw) {
    let ev;
    try {
      ev = JSON.parse(raw);
    } catch {
      return;
    }
    switch (ev.type) {
      case 'input_audio_buffer.speech_started':
        this.armIdle();
        this.setState('listening', 'Te escucho…');
        break;
      case 'input_audio_buffer.speech_stopped':
        this.setState('thinking');
        break;
      case 'conversation.item.input_audio_transcription.completed':
        if (ev.transcript?.trim()) this.onTranscript('user', ev.transcript.trim());
        break;
      case 'response.output_audio_transcript.done':
        if (ev.transcript?.trim()) this.onTranscript('assistant', ev.transcript.trim());
        break;
      case 'output_audio_buffer.started':
        this.speaking = true;
        this.setState('speaking');
        break;
      case 'output_audio_buffer.stopped':
      case 'output_audio_buffer.cleared':
        this.speaking = false;
        this.armIdle();
        if (this.endRequested) this.stop();
        else if (this.pendingTools === 0) this.setState('listening');
        break;
      case 'response.done':
        this.onResponseDone(ev.response);
        break;
      case 'error':
        this.onError(ev.error?.message ?? 'Error de la sesión de voz.');
        break;
      default:
    }
  }

  /** Ejecuta todas las llamadas de la respuesta con el mismo ToolBox del chat y luego pide otra respuesta. */
  async onResponseDone(response) {
    const calls = (response?.output ?? []).filter((i) => i.type === 'function_call' && i.status !== 'incomplete');
    if (!calls.length) return;
    this.pendingTools += calls.length;
    this.setState('thinking', `Usando ${calls.map((c) => c.name).join(', ')}…`);
    let needsReply = false;
    for (const call of calls) {
      const result = await this.toolbox.run(call.name, call.arguments, { voice: true });
      this.pendingTools--;
      if (!this.active) return;
      if (result.cards?.length) this.onCards(result.cards);
      this.send({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: call.call_id, output: ToolBox.forModel(result) } });
      if (call.name !== 'end_conversation') needsReply = true;
    }
    if (needsReply && !this.endRequested) this.send({ type: 'response.create' });
    this.armIdle();
  }

  /** end_conversation: colgar cuando termine de sonar la despedida. */
  requestEnd() {
    this.endRequested = true;
    clearTimeout(this.timers.end);
    this.timers.end = setTimeout(() => this.stop(), this.speaking ? 10_000 : 1_500);
  }

  setMuted(muted) {
    for (const t of this.stream?.getAudioTracks() ?? []) t.enabled = !muted;
  }

  /** Nivel de audio 0..1 (voz de JARVIS o del micrófono) para animar el núcleo. */
  level() {
    let max = 0;
    for (const a of this.analysers) {
      a.getByteTimeDomainData(this.levelBuf);
      for (let i = 0; i < this.levelBuf.length; i++) max = Math.max(max, Math.abs(this.levelBuf[i] - 128));
    }
    return Math.min(1, max / 64);
  }

  watchLevel(stream) {
    try {
      this.ctx ??= new AudioContext();
      const a = this.ctx.createAnalyser();
      a.fftSize = 256;
      this.ctx.createMediaStreamSource(stream).connect(a);
      this.analysers.push(a);
    } catch {
      /* sin Web Audio: el núcleo se anima sin nivel */
    }
  }

  stop(reason) {
    const wasActive = this.active;
    for (const t of Object.values(this.timers)) clearTimeout(t);
    this.timers = {};
    if (this.callId) api.realtimeHangup(this.callId).catch(() => {});
    this.callId = null;
    try {
      this.dc?.close();
      this.pc?.close();
    } catch {
      /* ya cerrado */
    }
    for (const t of this.stream?.getTracks() ?? []) t.stop();
    if (this.audio) this.audio.srcObject = null;
    this.ctx?.close().catch(() => {});
    Object.assign(this, { dc: null, pc: null, stream: null, audio: null, ctx: null, analysers: [], speaking: false, pendingTools: 0 });
    if (wasActive) this.setState('idle', reason);
  }
}
