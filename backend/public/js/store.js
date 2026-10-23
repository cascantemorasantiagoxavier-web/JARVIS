// Datos de JARVIS Web guardados SOLO en este navegador (localStorage): ajustes, memoria, tareas,
// conversación y proyectos de clips. Nada de esto se sube al servidor salvo lo que se envía al modelo.

const PREFIX = 'jarvis.';

function read(key, fallback) {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw == null ? fallback : JSON.parse(raw);
  } catch {
    return fallback;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
    return true;
  } catch {
    return false; // modo privado o almacenamiento lleno: la app sigue, sin persistir
  }
}

export const DEFAULT_SETTINGS = {
  token: '',
  voice: 'cedar',
  weatherPlace: 'San José, Costa Rica',
  reduceMotion: false,
  speakReplies: false,
};

export const settings = {
  get: () => ({ ...DEFAULT_SETTINGS, ...read('settings', {}) }),
  update(patch) {
    const next = { ...settings.get(), ...patch };
    write('settings', next);
    return next;
  },
};

/** Colección con ids enteros correlativos (memoria, tareas). */
function collection(key) {
  return {
    all: () => read(key, []),
    save(list) {
      write(key, list);
    },
    add(item) {
      const list = read(key, []);
      const id = list.reduce((m, x) => Math.max(m, x.id), 0) + 1;
      const row = { id, ...item };
      list.push(row);
      write(key, list);
      return row;
    },
    update(id, patch) {
      const list = read(key, []);
      const i = list.findIndex((x) => x.id === id);
      if (i < 0) return null;
      list[i] = { ...list[i], ...patch };
      write(key, list);
      return list[i];
    },
    remove(id) {
      const list = read(key, []);
      const next = list.filter((x) => x.id !== id);
      write(key, next);
      return next.length !== list.length;
    },
  };
}

export const memory = collection('memory');
export const tasks = collection('tasks');

export const clipProjects = {
  all: () => read('clips', []),
  upsert(project) {
    const list = read('clips', []).filter((p) => p.projectId !== project.projectId);
    list.unshift(project);
    write('clips', list.slice(0, 30));
    return project;
  },
  get: (id) => read('clips', []).find((p) => p.projectId === id) ?? null,
  latest: () => read('clips', [])[0] ?? null,
};

export const chatLog = {
  all: () => read('chat', []),
  push(entry) {
    const list = read('chat', []);
    list.push({ at: new Date().toISOString(), ...entry });
    // Solo los últimos mensajes conservan sus tarjetas: localStorage tiene ~5 MB.
    const kept = list.slice(-200).map((e, i, all) => (i < all.length - 20 && e.cards ? { ...e, cards: undefined } : e));
    write('chat', kept);
  },
  clear: () => write('chat', []),
};

const EXPORT_FORMAT = 'jarvis-web';

/** Copia de seguridad de este navegador. El token del servidor NO se incluye (es un secreto). */
export function exportData() {
  const { token: _omit, ...prefs } = settings.get();
  return {
    format: EXPORT_FORMAT,
    version: 1,
    exportedAt: new Date().toISOString(),
    settings: prefs,
    memory: memory.all(),
    tasks: tasks.all(),
    clips: clipProjects.all(),
    chat: chatLog.all(),
  };
}

/** Restaura una copia (reemplaza memoria, tareas, clips y conversación; conserva el token). */
export function importData(data) {
  if (data?.format !== EXPORT_FORMAT || typeof data.version !== 'number') throw new Error('Ese archivo no es una copia de JARVIS Web.');
  if (data.version > 1) throw new Error('La copia es de una versión más nueva de JARVIS Web.');
  const list = (v, name) => {
    if (v == null) return [];
    if (!Array.isArray(v) || v.some((x) => typeof x !== 'object' || x === null)) throw new Error(`La sección «${name}» de la copia está dañada.`);
    return v;
  };
  const okId = (x) => Number.isInteger(x.id) && x.id > 0;
  const mem = list(data.memory, 'memoria').filter((m) => okId(m) && typeof m.content === 'string');
  const tsk = list(data.tasks, 'tareas').filter((t) => okId(t) && typeof t.title === 'string');
  const clips = list(data.clips, 'clips').filter((c) => typeof c.projectId === 'string');
  const chat = list(data.chat, 'conversación').filter((e) => ['user', 'assistant'].includes(e.role) && typeof (e.text ?? '') === 'string');
  memory.save(mem);
  tasks.save(tsk);
  write('clips', clips.slice(0, 30));
  write('chat', chat.slice(-200));
  if (data.settings && typeof data.settings === 'object') {
    const { token: _ignored, ...prefs } = data.settings;
    const allowed = Object.fromEntries(Object.entries(prefs).filter(([k]) => k in DEFAULT_SETTINGS));
    settings.update(allowed);
  }
  return { memory: mem.length, tasks: tsk.length, clips: clips.length, chat: chat.length };
}

/** Borra todo lo guardado por JARVIS en este navegador. */
export function wipeAll() {
  for (const k of Object.keys(localStorage)) if (k.startsWith(PREFIX)) localStorage.removeItem(k);
}
