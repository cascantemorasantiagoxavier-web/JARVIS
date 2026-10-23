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

/** Borra todo lo guardado por JARVIS en este navegador. */
export function wipeAll() {
  for (const k of Object.keys(localStorage)) if (k.startsWith(PREFIX)) localStorage.removeItem(k);
}
