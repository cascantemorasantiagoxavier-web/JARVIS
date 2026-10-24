// Service worker de JARVIS Web: permite instalarla como app, abrirla sin conexión y mostrar los avisos de
// tareas en Android. Primero la red (siempre la versión más nueva); la copia local solo se usa sin conexión.
// Nunca guarda ni intercepta /api ni /health: conversaciones, fotos y audio no pasan por esta caché.
const CACHE = 'jarvis-web-v2';
const SHELL = [
  '/',
  '/index.html',
  '/css/hud.css',
  '/js/main.js',
  '/js/api.js',
  '/js/brain.js',
  '/js/calc.js',
  '/js/dates.js',
  '/js/gemini-live.js',
  '/js/globe.js',
  '/js/media.js',
  '/js/pcm-worklet.js',
  '/js/realtime.js',
  '/js/store.js',
  '/js/tools.js',
  '/js/ui.js',
  '/shared/tools.json',
  '/shared/persona-es.md',
  '/data/globe.json',
  '/fonts/orbitron_regular.ttf',
  '/fonts/orbitron_medium.ttf',
  '/fonts/orbitron_bold.ttf',
  '/fonts/rajdhani_regular.ttf',
  '/fonts/rajdhani_medium.ttf',
  '/fonts/rajdhani_semibold.ttf',
  '/fonts/rajdhani_bold.ttf',
  '/manifest.webmanifest',
  '/icon.svg',
  '/icons/icon-192.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname === '/health') return;
  event.respondWith(
    fetch(req)
      .then((res) => {
        if (res.ok && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy));
        }
        return res;
      })
      .catch(async () => (await caches.match(req, { ignoreSearch: true })) ?? (req.mode === 'navigate' ? caches.match('/') : Response.error())),
  );
});

// Tocar un aviso de tarea abre (o enfoca) JARVIS.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      const open = list.find((c) => new URL(c.url).origin === self.location.origin);
      return open ? open.focus() : self.clients.openWindow('/');
    }),
  );
});
