// Calculadora segura (sin eval): + - * / ^ %, paréntesis, sqrt, sin, cos, tan, log, ln, abs, round, pi, e.

const FUNCS = {
  sqrt: Math.sqrt,
  sin: (x) => Math.sin((x * Math.PI) / 180),
  cos: (x) => Math.cos((x * Math.PI) / 180),
  tan: (x) => Math.tan((x * Math.PI) / 180),
  log: Math.log10,
  ln: Math.log,
  abs: Math.abs,
  round: Math.round,
};
const CONSTS = { pi: Math.PI, e: Math.E };

function tokenize(src) {
  const s = src.toLowerCase().replace(/×/g, '*').replace(/÷/g, '/').replace(/,(?=\d{3}\b)/g, '').replace(/,/g, '.');
  const out = [];
  let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (/\s/.test(c)) {
      i++;
    } else if (/[0-9.]/.test(c)) {
      let j = i;
      while (j < s.length && /[0-9.]/.test(s[j])) j++;
      const n = Number(s.slice(i, j));
      if (!Number.isFinite(n)) throw new Error(`Número inválido: ${s.slice(i, j)}`);
      out.push({ t: 'num', v: n });
      i = j;
    } else if (/[a-z]/.test(c)) {
      let j = i;
      while (j < s.length && /[a-z]/.test(s[j])) j++;
      out.push({ t: 'id', v: s.slice(i, j) });
      i = j;
    } else if ('+-*/^%()'.includes(c)) {
      out.push({ t: 'op', v: c });
      i++;
    } else {
      throw new Error(`Símbolo no permitido: ${c}`);
    }
  }
  return out;
}

/** Evalúa la expresión. Lanza Error con mensaje en español si no es válida. */
export function calculate(expr) {
  if (!expr || expr.length > 300) throw new Error('Expresión vacía o demasiado larga.');
  const tk = tokenize(expr);
  let p = 0;
  const peek = () => tk[p];
  const eat = (v) => (peek()?.v === v ? (p++, true) : false);

  function primary() {
    const t = tk[p++];
    if (!t) throw new Error('Expresión incompleta.');
    if (t.t === 'num') return t.v;
    if (t.t === 'id') {
      if (t.v in CONSTS) return CONSTS[t.v];
      const f = FUNCS[t.v];
      if (!f) throw new Error(`Función desconocida: ${t.v}`);
      if (!eat('(')) throw new Error(`Falta "(" después de ${t.v}`);
      const v = expr0();
      if (!eat(')')) throw new Error('Falta ")"');
      return f(v);
    }
    if (t.v === '(') {
      const v = expr0();
      if (!eat(')')) throw new Error('Falta ")"');
      return v;
    }
    if (t.v === '-') return -factor();
    if (t.v === '+') return factor();
    throw new Error(`Símbolo inesperado: ${t.v}`);
  }
  function factor() {
    let v = primary();
    while (eat('%')) v = v / 100;
    if (eat('^')) v = v ** factor();
    return v;
  }
  function term() {
    let v = factor();
    for (;;) {
      if (eat('*')) v *= factor();
      else if (eat('/')) {
        const d = factor();
        if (d === 0) throw new Error('División entre cero.');
        v /= d;
      } else return v;
    }
  }
  function expr0() {
    let v = term();
    for (;;) {
      if (eat('+')) v += term();
      else if (eat('-')) v -= term();
      else return v;
    }
  }
  const v = expr0();
  if (p < tk.length) throw new Error('Expresión mal formada.');
  if (!Number.isFinite(v)) throw new Error('El resultado no es un número finito.');
  return Math.round(v * 1e10) / 1e10;
}
