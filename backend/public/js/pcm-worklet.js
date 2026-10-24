// AudioWorklet: toma el micrófono a la frecuencia del navegador, la baja a `targetRate` (16 kHz para
// Gemini) y envía bloques PCM de 16 bits. Se usa para la voz en vivo de Gemini y para dictar en WAV.
class PcmCapture extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const o = options?.processorOptions ?? {};
    this.ratio = sampleRate / (o.targetRate ?? 16000);
    this.chunk = o.chunk ?? 1600; // 100 ms a 16 kHz
    this.buf = new Int16Array(this.chunk);
    this.n = 0;
    this.acc = 0; // suma de muestras del bloque que se está promediando
    this.count = 0;
    this.pos = 0; // posición fraccional dentro del bloque de salida
    // "flush": envía lo que quede en el bloque actual (al terminar un dictado).
    this.port.onmessage = (e) => {
      if (e.data === 'flush') {
        if (this.n) this.port.postMessage(this.buf.slice(0, this.n).buffer);
        this.n = 0;
        this.port.postMessage('flushed');
      }
    };
  }

  process(inputs) {
    const ch = inputs[0]?.[0];
    if (!ch) return true;
    for (let i = 0; i < ch.length; i++) {
      this.acc += ch[i];
      this.count++;
      this.pos += 1;
      if (this.pos >= this.ratio) {
        this.pos -= this.ratio;
        const v = Math.max(-1, Math.min(1, this.acc / this.count));
        this.acc = 0;
        this.count = 0;
        this.buf[this.n++] = v < 0 ? v * 0x8000 : v * 0x7fff;
        if (this.n === this.chunk) {
          this.port.postMessage(this.buf.buffer, [this.buf.buffer]);
          this.buf = new Int16Array(this.chunk);
          this.n = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor('pcm-capture', PcmCapture);
