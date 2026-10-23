// Contadores de uso en memoria para límites de costo. Se reinician al
// reiniciar el proceso (documentado): son una barrera de seguridad, no facturación.
export class UsageTracker {
  constructor(now = () => Date.now()) {
    this.now = now;
    this.realtime = [];
    this.chat = [];
    this.media = { vision: [], transcribe: [] };
  }

  prune(list, windowMs) {
    const cutoff = this.now() - windowMs;
    while (list.length && list[0] < cutoff) list.shift();
  }

  recordRealtimeSession() {
    this.realtime.push(this.now());
  }

  realtimeSessions() {
    this.prune(this.realtime, 86_400_000);
    const hourAgo = this.now() - 3_600_000;
    return { today: this.realtime.length, lastHour: this.realtime.filter((t) => t >= hourAgo).length };
  }

  recordChat() {
    this.chat.push(this.now());
  }

  chatRequestsToday() {
    this.prune(this.chat, 86_400_000);
    return this.chat.length;
  }

  /** kind: "vision" | "transcribe". */
  recordMedia(kind) {
    this.media[kind].push(this.now());
  }

  mediaToday(kind) {
    this.prune(this.media[kind], 86_400_000);
    return this.media[kind].length;
  }
}
