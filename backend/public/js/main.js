// JARVIS Web: une el cerebro, la voz, el globo y los paneles. La voz nunca arranca sola.
import { api, ApiError, asset } from './api.js';
import { Brain } from './brain.js';
import { formatDateTime } from './dates.js';
import { Globe } from './globe.js';
import { Voice } from './realtime.js';
import { chatLog, clipProjects, memory, settings, tasks, wipeAll } from './store.js';
import { persistableCards, ToolBox } from './tools.js';
import { confirmDialog, el, renderCard, renderMarkdown, toast } from './ui.js';

const $ = (id) => document.getElementById(id);
const APK_URL = 'https://github.com/cascantemorasantiagoxavier-web/JARVIS/releases/download/jarvis-ultimo/jarvis-debug.apk';
const VOICES = ['cedar', 'marin', 'alloy', 'ash', 'ballad', 'coral', 'echo', 'sage', 'shimmer', 'verse'];
const STATE_LABEL = { idle: 'EN ESPERA', connecting: 'CONECTANDO', listening: 'ESCUCHANDO', thinking: 'PROCESANDO', speaking: 'HABLANDO' };
const VOICEBAR_TEXT = {
  idle: 'Voz apagada · toca «Hablar» para empezar',
  connecting: 'Conectando con la voz…',
  listening: 'Te escucho. Habla con naturalidad; puedes interrumpirme.',
  thinking: 'Procesando…',
  speaking: 'JARVIS está hablando…',
};

let toolbox;
let brain;
let voice;
let globe;
let health = null;
let busy = false;
let voiceStartedAt = 0;
let openPanelName = null;

const cardHandlers = {
  verify: (id) => runDirect('business_verify', { id }),
  publishClip: (projectId, clip) => publishClip(projectId, clip),
  refreshClips: (projectId) => refreshClips(projectId),
};

// ---------------------------------------------------------------- arranque

async function boot() {
  startClock();
  wireStatic();
  const [defs, persona, globeData] = await Promise.all([
    asset('/shared/tools.json'),
    asset('/shared/persona-es.md', 'text'),
    asset('/data/globe.json').catch(() => ({ land: [], cities: [] })),
  ]);
  toolbox = new ToolBox({ definitions: defs.tools, host: { confirm: confirmDialog, locate, endVoice: () => voice?.requestEnd(), notify: toast } });
  brain = new Brain({ toolbox, persona });
  voice = new Voice({ toolbox, brain, onState: onVoiceState, onTranscript, onCards: (cards) => addCards(cards), onError: (m) => addSystem(m, true) });
  globe = new Globe($('globe'), globeData, {
    reduceMotion: reduceMotion(),
    level: () => voice.level(),
    reserveBottom: () => $('globe').getBoundingClientRect().bottom - document.querySelector('.core-status').getBoundingClientRect().top + 8,
  });
  globe.start();
  restoreLog();
  refreshWeather();
  setInterval(refreshWeather, 30 * 60_000);
  setInterval(checkReminders, 30_000);
  checkReminders();
  await checkServer();
  if (!settings.get().token && health) $('onboard').showModal();
}

function reduceMotion() {
  return settings.get().reduceMotion || matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function startClock() {
  const tick = () => {
    const now = new Date();
    $('clock-time').textContent = now.toLocaleTimeString('es-CR', { hour: '2-digit', minute: '2-digit', hour12: false });
    $('clock-date').textContent = now.toLocaleDateString('es-CR', { weekday: 'long', day: 'numeric', month: 'long' });
    if (voice?.active && voiceStartedAt) {
      const s = Math.floor((Date.now() - voiceStartedAt) / 1000);
      $('vb-timer').textContent = `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
    }
  };
  tick();
  setInterval(tick, 1000);
}

function wireStatic() {
  $('composer').addEventListener('submit', (e) => {
    e.preventDefault();
    send($('input').value);
  });
  $('input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      send($('input').value);
    }
  });
  $('input').addEventListener('input', autoGrow);
  document.addEventListener('click', (e) => {
    const ask = e.target.closest('[data-ask]');
    if (ask) send(ask.dataset.ask);
    const panel = e.target.closest('[data-panel]');
    if (panel) openPanel(panel.dataset.panel);
  });
  $('talk').addEventListener('click', toggleVoice);
  $('hangup').addEventListener('click', () => voice?.stop('Conversación terminada.'));
  $('mute').addEventListener('click', () => {
    const muted = $('mute').getAttribute('aria-pressed') !== 'true';
    voice?.setMuted(muted);
    $('mute').setAttribute('aria-pressed', String(muted));
    $('mute').textContent = muted ? 'Activar micrófono' : 'Silenciar micrófono';
  });
  $('weather-chip').addEventListener('click', () => runDirect('get_weather', {}));
  $('clear-chat').addEventListener('click', async () => {
    if (await confirmDialog('Limpiar la conversación', ['Se borra el historial guardado en este navegador.'], 'Limpiar')) {
      chatLog.clear();
      $('log').replaceChildren();
    }
  });
  $('panel-close').addEventListener('click', closePanel);
  $('scrim').addEventListener('click', closePanel);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && openPanelName && !document.querySelector('dialog[open]')) closePanel();
  });
  $('onboard-form').addEventListener('submit', onboardSubmit);
}

function autoGrow() {
  const t = $('input');
  t.style.height = 'auto';
  t.style.height = `${Math.min(t.scrollHeight, 140)}px`;
}

// ---------------------------------------------------------------- servidor

async function checkServer() {
  const chip = $('server-chip');
  try {
    health = await api.health();
  } catch {
    health = null;
    chip.dataset.state = 'down';
    chip.textContent = 'Sin servidor';
    return;
  }
  const f = health.features ?? {};
  if (!settings.get().token) {
    chip.dataset.state = 'partial';
    chip.textContent = 'Falta token';
    return;
  }
  try {
    await api.usage();
  } catch (err) {
    chip.dataset.state = 'down';
    chip.textContent = err.status === 401 ? 'Token inválido' : 'Sin servidor';
    if (err.status === 401) toast(err.message);
    return;
  }
  chip.dataset.state = f.chat && f.realtime ? 'ok' : 'partial';
  chip.textContent = f.chat ? 'En línea' : 'En línea · sin IA';
}

async function onboardSubmit(e) {
  if (e.submitter?.value !== 'ok') return; // «Ahora no» cierra el diálogo
  e.preventDefault();
  const token = $('onboard-token').value.trim();
  const errBox = $('onboard-error');
  errBox.hidden = true;
  if (token.length < 16) {
    errBox.textContent = 'El token es demasiado corto.';
    errBox.hidden = false;
    return;
  }
  const previous = settings.get().token;
  settings.update({ token });
  try {
    await api.usage();
    $('onboard').close('ok');
    $('onboard-token').value = '';
    toast('Conectado con tu servidor.');
    checkServer();
  } catch (err) {
    settings.update({ token: previous });
    errBox.textContent = err.message;
    errBox.hidden = false;
  }
}

// ---------------------------------------------------------------- conversación

async function send(raw) {
  const text = String(raw ?? '').trim();
  if (!text || busy) return;
  busy = true;
  $('send').disabled = true;
  $('input').value = '';
  autoGrow();
  const history = brain.history();
  addMessage('user', text);
  chatLog.push({ role: 'user', text });
  try {
    const r = await brain.ask(text, { history, onStatus: setThinking });
    if (r.local && r.notice) addSystem(`${r.notice} Respondí sin IA con lo que puedo hacer en el navegador.`);
    addMessage('assistant', r.text, r.cards);
    chatLog.push({ role: 'assistant', text: r.text, cards: persistableCards(r.cards) });
    if (settings.get().speakReplies) speak(r.text);
  } catch (err) {
    addSystem(err instanceof ApiError || err instanceof Error ? err.message : 'Error inesperado.', true);
  } finally {
    busy = false;
    $('send').disabled = false;
    setThinking(null);
    if (openPanelName) renderPanel(openPanelName); // la conversación pudo crear tareas o recuerdos
  }
}

function setThinking(text) {
  $('thinking').hidden = !text;
  if (text) $('thinking-text').textContent = text;
  globe?.setState(text ? 'thinking' : voice?.state ?? 'idle');
  if (!voice?.active) {
    $('core-state').textContent = text ? 'PROCESANDO' : STATE_LABEL.idle;
    $('core-detail').textContent = text ?? '';
  }
}

function addMessage(role, text, cards = [], { scroll = true, via } = {}) {
  const cls = role === 'user' ? 'user' : role === 'assistant' ? 'jarvis' : role;
  const who = role === 'user' ? 'TÚ' : 'JARVIS';
  const li = el('li', { class: `msg ${cls}` }, el('span', { class: 'who' }, via ? `${who} · ${via}` : who));
  if (text) li.append(role === 'user' ? el('p', {}, text) : renderMarkdown(text));
  for (const c of cards ?? []) {
    const node = renderCard(c, cardHandlers);
    if (node) li.append(node);
  }
  $('log').append(li);
  if (scroll) li.scrollIntoView({ block: 'end', behavior: reduceMotion() ? 'auto' : 'smooth' });
  return li;
}

function addCards(cards) {
  if (!cards?.length) return;
  addMessage('assistant', '', cards);
  chatLog.push({ role: 'assistant', text: '', cards: persistableCards(cards) });
}

function addSystem(text, error = false) {
  const li = el('li', { class: `msg system${error ? ' error' : ''}` }, text);
  $('log').append(li);
  li.scrollIntoView({ block: 'end' });
}

function restoreLog() {
  for (const e of chatLog.all()) addMessage(e.role, e.text, e.cards, { scroll: false, via: e.via });
  $('log').scrollTop = $('log').scrollHeight;
}

/** Herramienta pedida con un toque (botón): misma política de confirmación, con `direct`. */
async function runDirect(name, args, { show = true } = {}) {
  const r = await toolbox.run(name, args, { direct: true });
  if (show) {
    if (r.ok) addCards(r.cards.length ? r.cards : []);
    if (!r.cards.length || !r.ok) addSystem(r.message, !r.ok);
  }
  return r;
}

function speak(text) {
  if (!('speechSynthesis' in window)) return;
  const clean = text.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').replace(/https?:\/\/\S+/g, '').replace(/[*_`#>]/g, '').slice(0, 700);
  const u = new SpeechSynthesisUtterance(clean);
  u.lang = 'es-CR';
  const v = speechSynthesis.getVoices().find((x) => x.lang?.startsWith('es'));
  if (v) u.voice = v;
  speechSynthesis.cancel();
  speechSynthesis.speak(u);
}

// ---------------------------------------------------------------- voz

async function toggleVoice() {
  if (voice.active) {
    voice.stop('Conversación terminada.');
    return;
  }
  if (!settings.get().token) {
    $('onboard').showModal();
    return;
  }
  if (health && !health.features?.realtime) {
    addSystem('La voz no está configurada en el servidor: falta OPENAI_API_KEY en Render.', true);
    return;
  }
  speechSynthesis?.cancel?.();
  try {
    await voice.start();
  } catch (err) {
    addSystem(err.message, true);
  }
}

function onVoiceState(state, detail) {
  if (state === 'connecting') voiceStartedAt = Date.now();
  const active = state !== 'idle';
  $('core-state').textContent = STATE_LABEL[state] ?? state.toUpperCase();
  $('core-detail').textContent = detail ?? '';
  $('voicebar').dataset.state = state;
  $('vb-text').textContent = detail && state !== 'idle' ? detail : VOICEBAR_TEXT[state] ?? '';
  $('talk').dataset.active = String(active);
  $('talk-label').textContent = active ? 'Colgar' : 'Hablar';
  $('talk').setAttribute('aria-label', active ? 'Terminar la conversación de voz' : 'Hablar con JARVIS');
  $('hangup').hidden = !active;
  $('mute').hidden = !active;
  if (!active) {
    $('vb-timer').textContent = '';
    $('mute').setAttribute('aria-pressed', 'false');
    $('mute').textContent = 'Silenciar micrófono';
    if (detail) addSystem(detail);
  }
  globe?.setState(state);
}

function onTranscript(role, text) {
  addMessage(role, text, [], { via: 'voz' });
  chatLog.push({ role, text, via: 'voz' });
}

// ---------------------------------------------------------------- clima, ubicación y recordatorios

async function refreshWeather() {
  try {
    const r = await toolbox.weather();
    if (!r.ok) throw new Error(r.message);
    const d = r.data;
    $('weather-text').textContent = `${Math.round(d.temperature_c)} °C · ${d.description} · ${d.place.split(',')[0]}`;
    $('weather-chip').title = `${d.place} — ${d.source}`;
  } catch {
    $('weather-text').textContent = 'Clima no disponible';
  }
}

function locate() {
  if (!navigator.geolocation) return Promise.resolve(null);
  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ lat: p.coords.latitude, lon: p.coords.longitude }),
      () => resolve(null),
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 },
    );
  });
}

function checkReminders() {
  const now = Date.now();
  for (const t of tasks.all()) {
    if (t.done || !t.remind || !t.due || t.notifiedAt || Date.parse(t.due) > now) continue;
    tasks.update(t.id, { notifiedAt: new Date().toISOString() });
    const msg = `Recordatorio: ${t.title}`;
    toast(msg, 15_000);
    addSystem(msg);
    if ('Notification' in window && Notification.permission === 'granted') {
      try {
        new Notification('J.A.R.V.I.S.', { body: t.title, icon: '/icon.svg', tag: `task-${t.id}` });
      } catch {
        /* algunos navegadores móviles solo notifican desde un service worker */
      }
    }
  }
}

// ---------------------------------------------------------------- paneles

const PANELS = {
  tasks: ['Tareas', renderTasks],
  memory: ['Memoria', renderMemory],
  business: ['Negocios', renderBusiness],
  clips: ['Clips y YouTube', renderClips],
  settings: ['Ajustes', renderSettings],
};

function openPanel(name) {
  if (!PANELS[name]) return;
  openPanelName = name;
  $('panel-title').textContent = PANELS[name][0];
  for (const b of document.querySelectorAll('.module')) b.setAttribute('aria-current', String(b.dataset.panel === name));
  renderPanel(name);
  $('panel').hidden = false;
  $('scrim').hidden = false;
  $('panel-close').focus();
}

function closePanel() {
  openPanelName = null;
  $('panel').hidden = true;
  $('scrim').hidden = true;
  for (const b of document.querySelectorAll('.module')) b.removeAttribute('aria-current');
}

function renderPanel(name) {
  const body = $('panel-body');
  body.replaceChildren(...[PANELS[name][1]()].flat().filter(Boolean));
}

function form(onSubmit, ...children) {
  return el('form', {
    class: 'row-form',
    onSubmit: (e) => {
      e.preventDefault();
      onSubmit(new FormData(e.target), e.target);
    },
  }, ...children);
}

function renderTasks() {
  const all = tasks.all();
  const pending = all.filter((t) => !t.done).sort((a, b) => (a.due ?? '9').localeCompare(b.due ?? '9'));
  const done = all.filter((t) => t.done).slice(-15).reverse();
  const item = (t) => el('li', { class: `item${t.done ? ' done' : ''}` },
    el('input', {
      type: 'checkbox', checked: t.done || null, 'aria-label': t.done ? `Marcar «${t.title}» como pendiente` : `Completar «${t.title}»`,
      onChange: async (e) => {
        if (e.target.checked) await toolbox.run('complete_task', { task_id: t.id }, { direct: true });
        else tasks.update(t.id, { done: false, doneAt: null });
        renderPanel('tasks');
      },
    }),
    el('div', { class: 'grow' },
      el('span', {}, t.title, t.priority === 'HIGH' ? el('span', { class: 'tag' }, 'alta') : null),
      t.due ? el('span', { class: 'sub' }, `${formatDateTime(new Date(t.due), t.hasTime)}${t.remind ? ' · con aviso' : ''}`) : null),
    el('button', {
      class: 'icon-btn', type: 'button', 'aria-label': `Eliminar «${t.title}»`,
      onClick: async () => {
        const r = await toolbox.run('delete_task', { task_id: t.id }, { direct: true });
        if (!r.ok && r.code !== 'not_confirmed') toast(r.message);
        renderPanel('tasks');
      },
    }, '✕'));
  const notifyBtn = 'Notification' in window && Notification.permission === 'default'
    ? el('button', { class: 'chip', type: 'button', onClick: async () => { await Notification.requestPermission(); renderPanel('tasks'); } }, 'Activar avisos del navegador')
    : null;
  return [
    form(async (fd, f) => {
      const r = await toolbox.run('create_task', { title: String(fd.get('title')), due: String(fd.get('due') || '') || undefined }, { direct: true });
      toast(r.message);
      if (r.ok) f.reset();
      renderPanel('tasks');
    },
    el('input', { type: 'text', name: 'title', placeholder: 'Nueva tarea', required: true, minlength: 2, maxlength: 200, 'aria-label': 'Título de la tarea' }),
    el('div', { class: 'inline' },
      el('input', { type: 'text', name: 'due', placeholder: 'Cuándo (p. ej. mañana a las 3)', maxlength: 80, 'aria-label': 'Fecha de la tarea' }),
      el('button', { class: 'chip primary', type: 'submit' }, 'Agregar'))),
    el('p', { class: 'muted small' }, 'Los avisos suenan solo si esta página está abierta.'),
    notifyBtn,
    el('h3', {}, `Pendientes (${pending.length})`),
    pending.length ? el('ul', { class: 'items' }, ...pending.map(item)) : el('p', { class: 'empty' }, 'Nada pendiente.'),
    done.length ? el('h3', {}, 'Completadas') : null,
    done.length ? el('ul', { class: 'items' }, ...done.map(item)) : null,
  ];
}

function renderMemory() {
  const list = memory.all().slice().reverse();
  return [
    form(async (fd, f) => {
      const r = await toolbox.run('remember', { content: String(fd.get('content')), private: fd.get('private') === 'on' }, { direct: true });
      toast(r.message);
      if (r.ok) f.reset();
      renderPanel('memory');
    },
    el('textarea', { name: 'content', rows: 2, placeholder: 'Algo que JARVIS deba recordar', required: true, maxlength: 2000, 'aria-label': 'Recuerdo' }),
    el('div', { class: 'inline' },
      el('label', { class: 'check' }, el('input', { type: 'checkbox', name: 'private' }), 'Privado (nunca se envía al modelo)'),
      el('button', { class: 'chip primary', type: 'submit' }, 'Guardar'))),
    el('p', { class: 'muted small' }, 'La memoria vive solo en este navegador. No guardes contraseñas ni claves aquí.'),
    list.length
      ? el('ul', { class: 'items' }, ...list.map((m) => el('li', { class: 'item' },
        el('div', { class: 'grow' },
          el('span', {}, m.content, m.private ? el('span', { class: 'tag' }, 'privado') : null),
          el('span', { class: 'sub' }, m.createdAt ? formatDateTime(new Date(m.createdAt), false) : '')),
        el('button', {
          class: 'icon-btn', type: 'button', 'aria-label': 'Olvidar este recuerdo',
          onClick: async () => {
            await toolbox.run('forget', { memory_id: m.id }, { direct: true });
            renderPanel('memory');
          },
        }, '✕'))))
      : el('p', { class: 'empty' }, 'Todavía no hay recuerdos.'),
  ];
}

function renderBusiness() {
  const results = el('div', { id: 'biz-results' });
  return [
    form(async (fd) => {
      results.replaceChildren(el('p', { class: 'muted' }, 'Buscando en OpenStreetMap…'));
      const args = { category: String(fd.get('category')), limit: 15 };
      if (fd.get('near') === 'on') args.near_me = true;
      else args.location = String(fd.get('location') || 'San José, Costa Rica');
      const r = await toolbox.run('business_search', args, { direct: true });
      results.replaceChildren(el('p', { class: r.ok ? 'muted' : 'error' }, r.message), ...r.cards.map((c) => renderCard(c, cardHandlers)));
    },
    el('input', { type: 'text', name: 'category', placeholder: 'Qué buscas (ferreterías, sodas, veterinarias…)', required: true, minlength: 2, maxlength: 120, 'aria-label': 'Tipo de negocio' }),
    el('div', { class: 'inline' },
      el('input', { type: 'text', name: 'location', placeholder: 'Dónde (Heredia, Liberia…)', maxlength: 160, 'aria-label': 'Zona' }),
      el('label', { class: 'check' }, el('input', { type: 'checkbox', name: 'near' }), 'Cerca de mí'),
      el('button', { class: 'chip primary', type: 'submit' }, 'Buscar'))),
    el('p', { class: 'muted small' }, 'Datos de OpenStreetMap con su estado (encontrado, verificado, no disponible…). JARVIS no inventa teléfonos ni direcciones. «Verificar» revisa la web del negocio y, si está activo, Google Maps (sin guardar sus datos).'),
    results,
  ];
}

function renderClips() {
  const projects = clipProjects.all();
  const enabled = health?.features?.clips;
  return [
    el('p', {}, 'Pega el enlace de un video (YouTube, Drive, etc.). OpusClip lo corta en clips cortos y, cuando digas, sube el que elijas a tu canal de YouTube.'),
    enabled === false ? el('p', { class: 'error' }, 'OpusClip no está configurado en el servidor: falta OPUSCLIP_API_KEY en Render.') : null,
    form(async (fd, f) => {
      const r = await toolbox.run('clips_create', { video_url: String(fd.get('url')), prompt: String(fd.get('prompt') || '') || undefined });
      toast(r.message, 8000);
      if (r.ok) f.reset();
      renderPanel('clips');
    },
    el('input', { type: 'url', name: 'url', placeholder: 'https://www.youtube.com/watch?v=…', required: true, maxlength: 2000, 'aria-label': 'Enlace del video' }),
    el('div', { class: 'inline' },
      el('input', { type: 'text', name: 'prompt', placeholder: 'Qué momentos buscar (opcional)', maxlength: 500, 'aria-label': 'Indicaciones para los clips' }),
      el('button', { class: 'chip primary', type: 'submit' }, 'Hacer clips'))),
    el('p', { class: 'muted small' }, 'Se publica en PRIVADO salvo que pidas otra cosa, y siempre te pregunto antes. Sube solo videos tuyos o con permiso.'),
    el('h3', {}, 'Proyectos'),
    projects.length
      ? el('div', {}, ...projects.map((p) => el('div', { class: 'card' },
        el('h3', {}, `${p.projectId} · ${p.stage ?? 'en cola'}`),
        el('p', { class: 'muted small' }, `${p.videoUrl ?? ''}${p.createdAt ? ` · ${formatDateTime(new Date(p.createdAt))}` : ''}`),
        renderCard({ type: 'clips', projectId: p.projectId, clips: p.clips ?? [] }, cardHandlers))))
      : el('p', { class: 'empty' }, 'Aún no hay proyectos.'),
  ];
}

async function refreshClips(projectId) {
  const r = await toolbox.run('clips_status', { project_id: projectId }, { direct: true });
  toast(r.message);
  if (openPanelName === 'clips') renderPanel('clips');
  else if (r.cards.length) addCards(r.cards);
}

async function publishClip(projectId, clip) {
  const r = await toolbox.run('clips_publish', { project_id: projectId, curation_id: clip.curationId, title: (clip.title || `Clip ${clip.number}`).slice(0, 100), privacy: 'private' }, { direct: true });
  if (r.code !== 'not_confirmed') toast(r.message, 8000);
  if (r.ok) addSystem(r.message);
}

function renderSettings() {
  const s = settings.get();
  const f = health?.features ?? {};
  const usageBox = el('ul', { class: 'lines' });
  const feature = (label, on, detail) => [el('span', {}, label), el('span', { class: on ? 'on' : 'off' }, on ? detail ?? 'activo' : 'no configurado')];
  return [
    el('h3', {}, 'Servidor'),
    form(async (fd) => {
      const token = String(fd.get('token')).trim();
      if (token) settings.update({ token });
      await checkServer();
      toast($('server-chip').textContent);
      renderPanel('settings');
    },
    el('label', { class: 'field' }, 'Token del servidor (JARVIS_CLIENT_TOKEN)',
      el('input', { type: 'password', name: 'token', autocomplete: 'off', spellcheck: 'false', placeholder: s.token ? '•••••••• (guardado)' : 'Pégalo aquí' })),
    el('div', { class: 'inline' },
      el('button', { class: 'chip primary', type: 'submit' }, 'Guardar y probar'),
      s.token ? el('button', { class: 'chip', type: 'button', onClick: () => { settings.update({ token: '' }); checkServer(); renderPanel('settings'); } }, 'Quitar token') : null)),
    el('p', { class: 'muted small' }, 'El token se guarda solo en este navegador. Las claves de OpenAI, Google y OpusClip se quedan en el servidor (Render → Environment).'),
    health
      ? el('ul', { class: 'feature-list' },
        ...feature('Chat con IA', f.chat),
        ...feature('Voz en tiempo real', f.realtime, f.realtimeModel),
        ...feature('Búsqueda web', f.webSearch?.length, (f.webSearch ?? []).join(', ')),
        ...feature('Negocios (OpenStreetMap)', f.business),
        ...feature('Comercio exterior (UN Comtrade)', f.trade),
        ...feature('Verificación con Google Maps', f.googlePlaces),
        ...feature('Clips (OpusClip → YouTube)', f.clips))
      : el('p', { class: 'error' }, 'No hay conexión con el servidor.'),
    el('button', {
      class: 'chip', type: 'button',
      onClick: async () => {
        try {
          const u = await api.usage();
          usageBox.replaceChildren(
            el('li', {}, `Chat: ${u.chat.today}/${u.chat.maxPerDay} hoy`),
            el('li', {}, `Voz: ${u.realtime.today ?? 0}/${u.realtime.maxPerDay} sesiones hoy`),
            el('li', {}, `Comtrade: ${u.trade.today}/${u.trade.maxPerDay} · Google: ${u.googlePlaces.today}/${u.googlePlaces.maxPerDay}`),
            el('li', {}, `Clips: ${u.clips.projectsToday}/${u.clips.maxProjectsPerDay} proyectos, ${u.clips.postsToday}/${u.clips.maxPostsPerDay} publicaciones`),
            el('li', { class: 'muted' }, u.note));
        } catch (err) {
          usageBox.replaceChildren(el('li', { class: 'error' }, err.message));
        }
      },
    }, 'Ver consumo de hoy'),
    usageBox,

    el('h3', {}, 'Voz y pantalla'),
    el('label', { class: 'field' }, 'Voz de JARVIS (tiempo real)',
      el('select', { name: 'voice', onChange: (e) => settings.update({ voice: e.target.value }) },
        ...VOICES.map((v) => el('option', { value: v, selected: v === s.voice || null }, v)))),
    el('label', { class: 'field' }, 'Lugar del clima',
      el('input', { type: 'text', value: s.weatherPlace, maxlength: 80, onChange: (e) => { settings.update({ weatherPlace: e.target.value.trim() || 'San José, Costa Rica' }); refreshWeather(); } })),
    el('label', { class: 'check' },
      el('input', { type: 'checkbox', checked: s.reduceMotion || null, onChange: (e) => { settings.update({ reduceMotion: e.target.checked }); globe.setReduceMotion(reduceMotion()); } }),
      'Reducir movimiento'),
    el('label', { class: 'check' },
      el('input', { type: 'checkbox', checked: s.speakReplies || null, onChange: (e) => settings.update({ speakReplies: e.target.checked }) }),
      'Leer en voz alta las respuestas del chat (voz del navegador, gratis)'),

    el('h3', {}, 'App para Android'),
    el('p', { class: 'small' }, 'También existe la app nativa (APK de prueba, sin firmar para la tienda): ', el('a', { href: APK_URL, target: '_blank', rel: 'noopener noreferrer' }, 'descargar APK'), '.'),

    el('h3', {}, 'Datos'),
    el('button', {
      class: 'chip danger', type: 'button',
      onClick: async () => {
        if (await confirmDialog('Borrar todo', ['Conversación, memoria, tareas, proyectos de clips, ajustes y token de este navegador.', 'No se puede deshacer.'], 'Borrar todo')) {
          voice?.stop();
          wipeAll();
          location.reload();
        }
      },
    }, 'Borrar todo lo guardado en este navegador'),
    el('p', { class: 'muted small' }, `JARVIS Web · servidor ${health?.version ?? '—'}`),
  ];
}

boot().catch((err) => {
  console.error(err);
  addSystem(`No se pudo iniciar JARVIS: ${err.message}`, true);
});
