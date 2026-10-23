// Fechas en español para tareas y eventos: ISO, "hoy", "mañana a las 3 pm", "pasado mañana",
// "el viernes a las 10", "en 20 minutos", "en 2 horas", "esta noche", "al mediodía", "5/11".

const DAYS = ['domingo', 'lunes', 'martes', 'miercoles', 'jueves', 'viernes', 'sabado'];

const fold = (s) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();

function applyTime(date, text) {
  const t = text.match(/\b(?:a\s+las?|a\s+la)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|de la manana|de la tarde|de la noche)?/);
  if (t) {
    let h = Number(t[1]);
    const m = Number(t[2] ?? 0);
    const suffix = t[3] ?? '';
    if ((suffix === 'pm' || suffix.includes('tarde') || suffix.includes('noche')) && h < 12) h += 12;
    if ((suffix === 'am' || suffix.includes('manana')) && h === 12) h = 0;
    if (!suffix && h >= 1 && h <= 6) h += 12; // "a las 3" casi siempre es por la tarde
    date.setHours(h, m, 0, 0);
    return true;
  }
  if (/\bmediodia\b/.test(text)) return date.setHours(12, 0, 0, 0), true;
  if (/\besta noche\b/.test(text)) return date.setHours(20, 0, 0, 0), true;
  return false;
}

/** Devuelve {date: Date, hasTime: boolean} o null si no se entiende. */
export function parseDue(text, now = new Date()) {
  if (!text) return null;
  const raw = text.trim();
  if (/^\d{4}-\d{2}-\d{2}(t\d{2}:\d{2}(:\d{2})?)?/i.test(raw)) {
    const d = new Date(raw.length === 10 ? `${raw}T09:00:00` : raw);
    if (!Number.isNaN(d.getTime())) return { date: d, hasTime: raw.length > 10 };
  }
  const t = fold(raw);
  const rel = t.match(/\ben\s+(\d+)\s*(minutos?|min|horas?|h|dias?)\b/);
  if (rel) {
    const n = Number(rel[1]);
    const unit = rel[2];
    const ms = unit.startsWith('d') ? n * 86_400_000 : unit.startsWith('h') ? n * 3_600_000 : n * 60_000;
    return { date: new Date(now.getTime() + ms), hasTime: true };
  }
  const d = new Date(now);
  d.setSeconds(0, 0);
  let matched = false;
  if (/\bpasado manana\b/.test(t)) {
    d.setDate(d.getDate() + 2);
    matched = true;
  } else if (/\bmanana\b/.test(t.replace(/de la manana/g, ''))) {
    d.setDate(d.getDate() + 1);
    matched = true;
  } else if (/\bhoy\b|\besta noche\b|\bmediodia\b/.test(t)) {
    matched = true;
  } else {
    const wd = DAYS.findIndex((x) => new RegExp(`\\b${x}\\b`).test(t));
    if (wd >= 0) {
      let diff = (wd - d.getDay() + 7) % 7;
      if (diff === 0) diff = 7;
      d.setDate(d.getDate() + diff);
      matched = true;
    } else {
      const dm = t.match(/\b(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?\b/);
      if (dm) {
        const year = dm[3] ? (dm[3].length === 2 ? 2000 + Number(dm[3]) : Number(dm[3])) : d.getFullYear();
        d.setFullYear(year, Number(dm[2]) - 1, Number(dm[1]));
        if (!dm[3] && d < now) d.setFullYear(year + 1);
        matched = true;
      }
    }
  }
  const hasTime = applyTime(d, t);
  if (!matched && !hasTime) return null;
  if (!hasTime) d.setHours(9, 0, 0, 0);
  if (!matched && hasTime && d < now) d.setDate(d.getDate() + 1);
  return { date: d, hasTime };
}

export function formatDateTime(date, withTime = true) {
  return new Intl.DateTimeFormat('es-CR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    ...(withTime ? { hour: 'numeric', minute: '2-digit' } : {}),
  }).format(date);
}
