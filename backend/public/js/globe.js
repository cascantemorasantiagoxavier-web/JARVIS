// Núcleo holográfico: globo de puntos (Natural Earth, dominio público) en proyección ortográfica,
// anillos HUD y Costa Rica resaltada. Sin asignar memoria por fotograma; se pausa con la pestaña oculta
// y respeta «reducir movimiento».

const CR = { lat: 9.93, lon: -84.08 };
const DEG = Math.PI / 180;
const TILT = -14 * DEG; // inclina el globo para que el hemisferio norte tropical quede de frente

const SPEED = { idle: 0.06, connecting: 0.2, listening: 0.1, thinking: 0.35, speaking: 0.12 }; // rad/s

export class Globe {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {{land: number[][], cities: (number|string)[][]}} data
   */
  constructor(canvas, data, { reduceMotion = false, level = () => 0, reserveBottom = () => 0 } = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.reduceMotion = reduceMotion;
    this.level = level;
    this.reserveBottom = reserveBottom; // px CSS que ocupan el estado y el botón bajo el globo
    this.state = 'idle';
    this.rot = -CR.lon * DEG + Math.PI / 2; // Costa Rica de frente al empezar
    this.pack(data);
    this.running = false;
    this.last = 0;
    this.t = 0;
    this.smooth = 0;
    this.p = [0, 0, 0];
    this.frame = this.frame.bind(this);
    new ResizeObserver(() => this.resize()).observe(canvas);
    document.addEventListener('visibilitychange', () => (document.hidden ? this.pause() : this.start()));
    this.resize();
  }

  pack(data) {
    const n = data.land.length;
    this.n = n;
    this.cosLat = new Float32Array(n);
    this.sinLat = new Float32Array(n);
    this.lon = new Float32Array(n);
    data.land.forEach(([lat, lon], i) => {
      this.cosLat[i] = Math.cos(lat * DEG);
      this.sinLat[i] = Math.sin(lat * DEG);
      this.lon[i] = lon * DEG;
    });
    this.cities = data.cities.map(([lat, lon]) => [Math.cos(lat * DEG), Math.sin(lat * DEG), lon * DEG]);
  }

  resize() {
    const r = this.canvas.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.w = Math.max(1, Math.round(r.width * dpr));
    this.h = Math.max(1, Math.round(r.height * dpr));
    this.canvas.width = this.w;
    this.canvas.height = this.h;
    this.dpr = dpr;
    // Centro y radio dentro del alto libre; en pantallas bajas se permite algo de solape.
    this.availH = Math.max(this.h - this.reserveBottom() * dpr, this.h * 0.85);
    const css = getComputedStyle(document.documentElement);
    this.color = {
      main: css.getPropertyValue('--amber').trim() || '#ffb547',
      dim: css.getPropertyValue('--amber-dim').trim() || '#8a5a17',
      hot: css.getPropertyValue('--amber-hot').trim() || '#ffe0a3',
    };
    this.draw();
  }

  setState(state) {
    this.state = state;
    if (this.reduceMotion) this.draw();
  }

  setReduceMotion(v) {
    this.reduceMotion = v;
    if (v) this.pause();
    else this.start();
    this.draw();
  }

  start() {
    if (this.running || this.reduceMotion || document.hidden) return;
    this.running = true;
    this.last = performance.now();
    requestAnimationFrame(this.frame);
  }

  pause() {
    this.running = false;
  }

  frame(now) {
    if (!this.running) return;
    const dt = Math.min(0.1, (now - this.last) / 1000);
    this.last = now;
    this.t += dt;
    this.rot += (SPEED[this.state] ?? SPEED.idle) * dt;
    this.draw();
    requestAnimationFrame(this.frame);
  }

  /** Proyecta (cosLat, sinLat, lon) → [x, y, z] en la escena; z > 0 mira hacia la cámara. */
  project(cl, sl, lon, out) {
    const a = lon + this.rot;
    const x = cl * Math.cos(a);
    const z0 = cl * Math.sin(a);
    out[0] = -x;
    out[1] = sl * Math.cos(TILT) - z0 * Math.sin(TILT);
    out[2] = sl * Math.sin(TILT) + z0 * Math.cos(TILT);
    return out;
  }

  draw() {
    const { ctx, w, h, dpr, color } = this;
    if (!color) return;
    ctx.clearRect(0, 0, w, h);
    const cx = w / 2;
    const cy = this.availH / 2;
    const R = Math.min(w, this.availH) * 0.32;
    const raw = this.state === 'speaking' || this.state === 'listening' ? this.level() : 0;
    this.smooth += (raw - this.smooth) * 0.25;
    const pulse = this.reduceMotion ? 0 : this.smooth;
    const p = this.p;

    // halo
    const g = ctx.createRadialGradient(cx, cy, R * 0.6, cx, cy, R * (1.35 + pulse * 0.25));
    g.addColorStop(0, 'rgba(255,170,60,0.10)');
    g.addColorStop(0.7, 'rgba(255,150,40,0.05)');
    g.addColorStop(1, 'rgba(255,150,40,0)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);

    // disco y contorno
    ctx.beginPath();
    ctx.arc(cx, cy, R, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(30,18,4,0.55)';
    ctx.fill();
    ctx.lineWidth = 1.2 * dpr;
    ctx.strokeStyle = color.dim;
    ctx.stroke();

    // tierra
    const dot = Math.max(1, 1.6 * dpr * (R / 220));
    ctx.fillStyle = color.main;
    for (let i = 0; i < this.n; i++) {
      this.project(this.cosLat[i], this.sinLat[i], this.lon[i], p);
      if (p[2] <= 0) continue;
      ctx.globalAlpha = 0.25 + 0.75 * p[2];
      ctx.fillRect(cx + p[0] * R - dot / 2, cy - p[1] * R - dot / 2, dot, dot);
    }
    // capitales
    ctx.fillStyle = color.hot;
    for (const [cl, sl, lon] of this.cities) {
      this.project(cl, sl, lon, p);
      if (p[2] <= 0.15) continue;
      ctx.globalAlpha = 0.4 + 0.6 * p[2];
      ctx.fillRect(cx + p[0] * R - dot, cy - p[1] * R - dot, dot * 2, dot * 2);
    }
    ctx.globalAlpha = 1;

    // Costa Rica
    this.project(Math.cos(CR.lat * DEG), Math.sin(CR.lat * DEG), CR.lon * DEG, p);
    if (p[2] > 0) {
      const x = cx + p[0] * R;
      const y = cy - p[1] * R;
      const k = this.reduceMotion ? 0.5 : (this.t * 0.8) % 1;
      ctx.strokeStyle = color.hot;
      ctx.lineWidth = 1.5 * dpr;
      ctx.globalAlpha = (1 - k) * p[2];
      ctx.beginPath();
      ctx.arc(x, y, (4 + k * 18) * dpr, 0, Math.PI * 2);
      ctx.stroke();
      ctx.globalAlpha = p[2];
      ctx.fillStyle = color.hot;
      ctx.beginPath();
      ctx.arc(x, y, 3 * dpr, 0, Math.PI * 2);
      ctx.fill();
      ctx.font = `${11 * dpr}px Orbitron, sans-serif`;
      ctx.fillText('COSTA RICA', x + 8 * dpr, y - 6 * dpr);
      ctx.globalAlpha = 1;
    }

    // anillos HUD
    const t = this.reduceMotion ? 0 : this.t;
    this.ring(cx, cy, R * 1.12, t * 0.4, 18, 0.55, color.main, 2);
    this.ring(cx, cy, R * 1.2 + pulse * R * 0.08, -t * 0.25, 60, 0.75, color.dim, 1);
    this.ticks(cx, cy, R * 1.3, -t * 0.1, 120, color.dim);
    this.ring(cx, cy, R * 1.38, t * 0.15, 4, 0.22, color.main, 2.5);
  }

  /** Anillo discontinuo de `seg` segmentos que cubren `fill` del perímetro. */
  ring(cx, cy, r, angle, seg, fill, stroke, width) {
    const { ctx, dpr } = this;
    const step = (Math.PI * 2) / seg;
    ctx.strokeStyle = stroke;
    ctx.lineWidth = width * dpr;
    ctx.beginPath();
    for (let i = 0; i < seg; i++) {
      const a = angle + i * step;
      ctx.moveTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
      ctx.arc(cx, cy, r, a, a + step * fill);
    }
    ctx.stroke();
  }

  ticks(cx, cy, r, angle, n, stroke) {
    const { ctx, dpr } = this;
    ctx.strokeStyle = stroke;
    ctx.lineWidth = dpr;
    ctx.beginPath();
    for (let i = 0; i < n; i++) {
      const a = angle + (i / n) * Math.PI * 2;
      const len = (i % 10 === 0 ? 9 : 4) * dpr;
      ctx.moveTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
      ctx.lineTo(cx + Math.cos(a) * (r + len), cy + Math.sin(a) * (r + len));
    }
    ctx.stroke();
  }
}
