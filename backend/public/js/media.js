// Foto y dictado en el navegador. Solo se capturan cuando la persona toca el botón; la imagen o el audio
// van a su servidor (que los reenvía a OpenAI sin guardarlos) y no se guardan en el navegador.

const MAX_SIDE = 1600;

/** Reduce y convierte la imagen elegida a JPEG (corrige la orientación de las fotos del teléfono). */
export async function imageToJpeg(file, { maxSide = MAX_SIDE, quality = 0.85 } = {}) {
  if (!file?.type?.startsWith('image/')) throw new Error('Ese archivo no es una imagen.');
  let bitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    throw new Error('No pude abrir esa imagen. Prueba con una foto JPG o PNG.');
  }
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
  const w = Math.max(1, Math.round(bitmap.width * scale));
  const h = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff'; // PNG con transparencia → fondo blanco
  ctx.fillRect(0, 0, w, h);
  ctx.drawImage(bitmap, 0, 0, w, h);
  bitmap.close?.();
  const dataUrl = canvas.toDataURL('image/jpeg', quality);
  return { dataUrl, base64: dataUrl.slice(dataUrl.indexOf(',') + 1), mimeType: 'image/jpeg', width: w, height: h };
}

/** Miniatura pequeña (data URL) para mostrar en la conversación sin guardar la foto completa. */
export function thumbnail(dataUrl, size = 160) {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const s = Math.min(1, size / Math.max(img.width, img.height));
      const c = document.createElement('canvas');
      c.width = Math.round(img.width * s);
      c.height = Math.round(img.height * s);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      resolve(c.toDataURL('image/jpeg', 0.7));
    };
    img.onerror = () => resolve(null);
    img.src = dataUrl;
  });
}

const AUDIO_TYPES = [
  ['audio/webm;codecs=opus', 'audio/webm'],
  ['audio/webm', 'audio/webm'],
  ['audio/mp4', 'audio/mp4'],
  ['audio/ogg;codecs=opus', 'audio/ogg'],
];

/** Grabadora de notas de voz para dictar (máx. `maxSeconds`). */
export class Recorder {
  constructor({ maxSeconds = 120, onTick = () => {}, onAutoStop = () => {} } = {}) {
    Object.assign(this, { maxSeconds, onTick, onAutoStop });
    this.rec = null;
  }

  static supported() {
    return Boolean(window.MediaRecorder && navigator.mediaDevices?.getUserMedia);
  }

  get recording() {
    return this.rec?.state === 'recording';
  }

  async start() {
    const pick = AUDIO_TYPES.find(([t]) => MediaRecorder.isTypeSupported?.(t));
    if (!pick) throw new Error('Este navegador no puede grabar audio.');
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    } catch {
      throw new Error('Sin permiso de micrófono. Actívalo en el candado de la barra de direcciones.');
    }
    this.mimeType = pick[1];
    this.chunks = [];
    this.rec = new MediaRecorder(this.stream, { mimeType: pick[0], audioBitsPerSecond: 48_000 });
    this.rec.ondataavailable = (e) => e.data.size && this.chunks.push(e.data);
    this.rec.start(1000);
    this.started = Date.now();
    this.timer = setInterval(() => {
      const s = Math.floor((Date.now() - this.started) / 1000);
      this.onTick(s);
      if (s >= this.maxSeconds) this.onAutoStop();
    }, 500);
  }

  /** Detiene y devuelve {base64, mimeType, seconds}. */
  async stop() {
    if (!this.rec) return null;
    const rec = this.rec;
    const done = new Promise((r) => (rec.onstop = r));
    if (rec.state !== 'inactive') rec.stop();
    await done;
    this.release();
    const blob = new Blob(this.chunks, { type: this.mimeType });
    if (blob.size < 1000) throw new Error('La grabación quedó vacía. Mantén el micrófono un poco más.');
    const dataUrl = await new Promise((resolve, reject) => {
      const fr = new FileReader();
      fr.onload = () => resolve(fr.result);
      fr.onerror = () => reject(new Error('No pude leer la grabación.'));
      fr.readAsDataURL(blob);
    });
    return { base64: dataUrl.slice(dataUrl.indexOf(',') + 1), mimeType: this.mimeType, seconds: Math.round((Date.now() - this.started) / 1000) };
  }

  cancel() {
    try {
      if (this.rec && this.rec.state !== 'inactive') this.rec.stop();
    } catch {
      /* ya detenida */
    }
    this.release();
  }

  release() {
    clearInterval(this.timer);
    for (const t of this.stream?.getTracks() ?? []) t.stop();
    this.rec = null;
    this.stream = null;
  }
}

/** WAV PCM 16 bits mono a partir de bloques Int16. */
export function encodeWav(chunks, rate) {
  const samples = chunks.reduce((n, c) => n + c.length, 0);
  const view = new DataView(new ArrayBuffer(44 + samples * 2));
  const str = (o, s) => [...s].forEach((ch, i) => view.setUint8(o + i, ch.charCodeAt(0)));
  str(0, 'RIFF');
  view.setUint32(4, 36 + samples * 2, true);
  str(8, 'WAVE');
  str(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, rate, true);
  view.setUint32(28, rate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  str(36, 'data');
  view.setUint32(40, samples * 2, true);
  let o = 44;
  for (const c of chunks) for (let i = 0; i < c.length; i++, o += 2) view.setInt16(o, c[i], true);
  return new Uint8Array(view.buffer);
}

/** Dictado en WAV 16 kHz (formato que acepta cualquier proveedor, incluido Gemini). Misma interfaz que Recorder. */
export class WavRecorder {
  constructor({ maxSeconds = 120, onTick = () => {}, onAutoStop = () => {} } = {}) {
    Object.assign(this, { maxSeconds, onTick, onAutoStop });
    this.on = false;
  }

  static supported() {
    return Boolean(window.AudioWorkletNode && navigator.mediaDevices?.getUserMedia);
  }

  get recording() {
    return this.on;
  }

  async start() {
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    } catch {
      throw new Error('Sin permiso de micrófono. Actívalo en el candado de la barra de direcciones.');
    }
    this.ctx = new AudioContext();
    await this.ctx.audioWorklet.addModule('/js/pcm-worklet.js');
    this.node = new AudioWorkletNode(this.ctx, 'pcm-capture', { processorOptions: { targetRate: 16000, chunk: 4000 } });
    const sink = this.ctx.createGain();
    sink.gain.value = 0;
    this.ctx.createMediaStreamSource(this.stream).connect(this.node).connect(sink).connect(this.ctx.destination);
    this.chunks = [];
    this.flushed = null;
    this.node.port.onmessage = (e) => {
      if (e.data === 'flushed') this.flushed?.();
      else if (e.data instanceof ArrayBuffer) this.chunks.push(new Int16Array(e.data));
    };
    if (this.ctx.state === 'suspended') await this.ctx.resume();
    this.on = true;
    this.started = Date.now();
    this.timer = setInterval(() => {
      const s = Math.floor((Date.now() - this.started) / 1000);
      this.onTick(s);
      if (s >= this.maxSeconds) this.onAutoStop();
    }, 500);
  }

  async stop() {
    if (!this.on) return null;
    this.on = false;
    await Promise.race([new Promise((r) => { this.flushed = r; this.node.port.postMessage('flush'); }), new Promise((r) => setTimeout(r, 500))]);
    const seconds = Math.round((Date.now() - this.started) / 1000);
    this.release();
    const wav = encodeWav(this.chunks, 16000);
    if (wav.length < 44 + 16000) throw new Error('La grabación quedó vacía. Mantén el micrófono un poco más.');
    let s = '';
    for (let i = 0; i < wav.length; i += 0x8000) s += String.fromCharCode(...wav.subarray(i, i + 0x8000));
    return { base64: btoa(s), mimeType: 'audio/wav', seconds };
  }

  cancel() {
    this.on = false;
    this.release();
  }

  release() {
    clearInterval(this.timer);
    for (const t of this.stream?.getTracks() ?? []) t.stop();
    this.ctx?.close().catch(() => {});
    this.stream = null;
    this.ctx = null;
  }
}
