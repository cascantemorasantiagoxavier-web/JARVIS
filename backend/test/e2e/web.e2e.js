// Prueba de extremo a extremo de JARVIS Web en Chromium real (Playwright).
// Servidor Express real (en proceso) con OpenAI SIMULADO; negocios, clips y Open-Meteo simulados en el
// navegador. La voz usa WebRTC real contra un segundo RTCPeerConnection en la misma página que hace
// de "OpenAI": así se prueban SDP, canal de datos, herramientas por voz y colgado.
// Uso: npm run test:e2e   (CHROMIUM_PATH=/ruta/chrome opcional; SCREENSHOTS=dir para capturas)
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import { fakeFetch, json, startServer, TOKEN } from '../helpers.js';

let chromium;
try {
  ({ chromium } = await import('playwright'));
} catch {
  /* sin playwright: se omite */
}

const SHOTS = process.env.SCREENSHOTS;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });
const shot = async (page, name) => SHOTS && page.screenshot({ path: join(SHOTS, `${name}.png`) });

// Cola de respuestas simuladas de OpenAI /responses y registro de lo que se le envió.
const openaiQueue = [];
const openaiBodies = [];
const transcribeForms = [];
const reply = (text) => ({ status: 'completed', model: 'gpt-test', output: [{ type: 'message', content: [{ type: 'output_text', text }] }] });
const call = (name, args, id = `call_${name}`) => ({ status: 'completed', model: 'gpt-test', output: [{ type: 'function_call', call_id: id, name, arguments: JSON.stringify(args) }] });

const BUSINESS = {
  id: 'osm:node:1',
  name: { value: 'Ferretería El Clavo', status: 'found', sources: ['osm'] },
  category: { value: 'Ferretería', status: 'found', sources: ['osm'] },
  address: { value: 'Calle 4, Heredia', status: 'found', sources: ['osm'] },
  province: { value: 'Heredia', status: 'found', sources: ['osm-admin'] },
  canton: { value: null, status: 'unavailable', sources: [] },
  district: { value: null, status: 'unavailable', sources: [] },
  phone: { value: '+506 2260 0000', status: 'found', sources: ['osm'] },
  website: { value: null, status: 'unavailable', sources: [] },
  email: { value: null, status: 'unavailable', sources: [] },
  openingHours: { value: null, status: 'unavailable', sources: [] },
  coordinates: { value: { lat: 10.0, lon: -84.11 }, status: 'found', sources: ['osm'] },
  links: { osm: 'https://www.openstreetmap.org/node/1', directions: 'https://www.google.com/maps/dir/?api=1&destination=10,-84.11' },
  sources: { osm: { name: 'OpenStreetMap', license: 'ODbL' } },
};
const CLIP = { number: 1, clipId: 'c1', curationId: 'cur1', projectId: 'P123', title: 'Mejor momento', durationSec: 42, score: 91, previewUrl: null, exportUrl: 'https://cdn.example.org/c1.mp4' };

let srv;
let browser;
const skip = !chromium && 'playwright no está instalado';

before(async () => {
  if (skip) return;
  const fetchImpl = fakeFetch([
    [(u) => u.endsWith('/responses'), (_u, init) => {
      openaiBodies.push(JSON.parse(init.body));
      const next = openaiQueue.shift();
      return next ? json(200, next) : json(200, reply('(sin guion)'));
    }],
    [(u) => u.endsWith('/audio/transcriptions'), (_u, init) => {
      transcribeForms.push(init.body);
      return json(200, { text: 'Recuérdame comprar café mañana' });
    }],
  ]);
  srv = await startServer({ overrides: { fetchImpl } });
  browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || undefined,
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
  });
});

after(async () => {
  await browser?.close();
  await srv?.close();
});

async function openPage({ viewport = { width: 1440, height: 900 }, token = TOKEN } = {}) {
  const ctx = await browser.newContext({ viewport, locale: 'es-CR', timezoneId: 'America/Costa_Rica', permissions: ['microphone', 'geolocation'], geolocation: { latitude: 10.0, longitude: -84.1 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && !/Failed to load resource/.test(m.text()) && errors.push(m.text()));
  await page.route('https://geocoding-api.open-meteo.com/**', (r) => r.fulfill({ json: { results: [{ name: 'San José', country: 'Costa Rica', latitude: 9.93, longitude: -84.08 }] } }));
  await page.route('https://api.open-meteo.com/**', (r) => r.fulfill({
    json: {
      current: { temperature_2m: 23.4, weather_code: 3, relative_humidity_2m: 80, wind_speed_10m: 9 },
      daily: { time: ['2026-10-23'], temperature_2m_max: [26], temperature_2m_min: [18], precipitation_probability_max: [70], weather_code: [61] },
    },
  }));
  await page.route('**/api/business/search', (r) => r.fulfill({
    json: { total: 1, results: [BUSINESS], category: { label: 'Ferreterías' }, area: { name: 'Heredia' }, source: { license: 'ODbL — © colaboradores de OpenStreetMap' } },
  }));
  if (token) await page.addInitScript((t) => localStorage.setItem('jarvis.settings', JSON.stringify({ token: t })), token);
  await page.goto(srv.base);
  return { page, ctx, errors };
}

async function sendChat(page, text) {
  await page.fill('#input', text);
  await page.press('#input', 'Enter');
  await page.waitForFunction(() => document.getElementById('thinking').hidden && !document.getElementById('send').disabled);
}

test('sin token pide conectar con el servidor y valida el token', { skip }, async () => {
  const { page, ctx, errors } = await openPage({ token: null });
  await page.waitForSelector('#onboard[open]');
  await shot(page, '01-conectar');
  await page.fill('#onboard-token', 'token-equivocado-0123456789');
  await page.click('#onboard-ok');
  await page.waitForSelector('#onboard-error:not([hidden])');
  assert.match(await page.textContent('#onboard-error'), /rechazó el token/);
  await page.fill('#onboard-token', TOKEN);
  await page.click('#onboard-ok');
  await page.waitForFunction(() => !document.getElementById('onboard').open);
  await page.waitForFunction(() => document.getElementById('server-chip').textContent === 'En línea');
  assert.equal(JSON.parse(await page.evaluate(() => localStorage.getItem('jarvis.settings'))).token, TOKEN);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('pantalla principal: HUD, reloj, clima, globo y servidor en línea', { skip }, async () => {
  const { page, ctx, errors } = await openPage();
  await page.waitForFunction(() => document.getElementById('server-chip').textContent === 'En línea');
  await page.waitForFunction(() => /23 °C/.test(document.getElementById('weather-text').textContent));
  assert.match(await page.textContent('#clock-time'), /^\d{2}:\d{2}$/);
  assert.equal(await page.textContent('#core-state'), 'EN ESPERA');
  const lit = await page.evaluate(() => {
    const c = document.getElementById('globe');
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    let n = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] > 150 && d[i + 1] > 60) n++;
    return n;
  });
  assert.ok(lit > 500, `el globo dibuja puntos (${lit})`);
  assert.equal(await page.evaluate(() => document.querySelector('#voicebar').dataset.state), 'idle', 'la voz no arranca sola');
  await page.waitForTimeout(400);
  await shot(page, '02-inicio');
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('chat: el modelo usa business_search, la web la ejecuta y devuelve el resultado', { skip }, async () => {
  const { page, ctx, errors } = await openPage();
  openaiQueue.push(call('business_search', { category: 'ferreterías', location: 'Heredia' }), reply('Encontré **1 ferretería** en Heredia. Teléfono en la tarjeta.'));
  const before = openaiBodies.length;
  await sendChat(page, 'Busca ferreterías en Heredia');
  const second = openaiBodies[before + 1];
  const out = second.input.find((i) => i.type === 'function_call_output');
  assert.ok(out, 'la segunda ronda lleva el resultado de la herramienta');
  assert.equal(out.call_id, 'call_business_search');
  assert.match(out.output, /Ferretería El Clavo/);
  assert.ok(openaiBodies[before].tools.some((t) => t.name === 'business_search'));
  assert.ok(!openaiBodies[before].tools.some((t) => t.name === 'end_conversation'), 'end_conversation solo en voz');
  assert.match(openaiBodies[before].instructions, /Eres J\.A\.R\.V\.I\.S\./);
  await page.waitForSelector('.biz-name');
  assert.equal(await page.textContent('.biz-name'), 'Ferretería El Clavo');
  assert.equal(await page.getAttribute('.biz a.chip[href^="tel:"]', 'href'), 'tel:+50622600000');
  assert.match(await page.textContent('.biz dl'), /Correono disponible/, 'datos faltantes como «no disponible»');
  assert.equal(await page.textContent('.biz .st'), 'encontrado', 'cada dato con su estado');
  assert.match(await page.textContent('.msg.jarvis:last-child'), /Encontré 1 ferretería/);
  await shot(page, '03-chat-negocios');
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('confirmación: forget pide permiso y si se cancela no borra nada', { skip }, async () => {
  const { page, ctx } = await openPage();
  await page.evaluate(() => localStorage.setItem('jarvis.memory', JSON.stringify([{ id: 1, content: 'Mi color favorito es el ámbar', kind: 'PREFERENCE' }])));
  openaiQueue.push(call('forget', { memory_id: 1 }), reply('De acuerdo, no lo borré.'));
  const before = openaiBodies.length;
  await page.fill('#input', 'Olvida mi color favorito');
  await page.press('#input', 'Enter');
  await page.waitForSelector('#confirm[open]');
  assert.match(await page.textContent('#confirm-lines'), /Mi color favorito es el ámbar/);
  await shot(page, '04-confirmacion');
  await page.click('#confirm button[value="cancel"]');
  await page.waitForFunction(() => document.getElementById('thinking').hidden);
  const out = openaiBodies[before + 1].input.find((i) => i.type === 'function_call_output');
  assert.match(out.output, /not_confirmed/);
  assert.equal(JSON.parse(await page.evaluate(() => localStorage.getItem('jarvis.memory'))).length, 1);
  await ctx.close();
});

test('seguridad: el texto del modelo no puede inyectar HTML ni enlaces javascript:', { skip }, async () => {
  const { page, ctx } = await openPage();
  openaiQueue.push(reply('Hola <img src=x onerror="window.__xss=1"> [clic](javascript:alert(1)) y [bien](https://example.org)'));
  await sendChat(page, 'prueba');
  assert.equal(await page.locator('.msg.jarvis img').count(), 0);
  assert.equal(await page.evaluate(() => window.__xss), undefined);
  const hrefs = await page.$$eval('.msg.jarvis a', (as) => as.map((a) => a.getAttribute('href')));
  assert.deepEqual(hrefs, ['https://example.org/']);
  await ctx.close();
});

test('paneles: tareas con fecha en español y memoria privada', { skip }, async () => {
  const { page, ctx } = await openPage();
  await page.click('.module[data-panel="tasks"]');
  await page.fill('input[name="title"]', 'Pagar la luz');
  await page.fill('input[name="due"]', 'mañana a las 3');
  await page.click('#panel-body button[type="submit"]');
  await page.waitForSelector('.item .sub');
  assert.match(await page.textContent('.item .sub'), /15:00|3:00/);
  assert.doesNotMatch(await page.textContent('#toasts'), /\.\./, 'sin punto doble tras «p. m.»');
  await shot(page, '05-tareas');
  await page.click('.module[data-panel="memory"]');
  await page.fill('textarea[name="content"]', 'Mi PIN del banco no se comparte');
  await page.check('input[name="private"]');
  await page.click('#panel-body button[type="submit"]');
  await page.waitForSelector('.item .tag');
  const instructions = await page.evaluate(async () => {
    const { Brain } = await import('/js/brain.js');
    return new Brain({ toolbox: null, persona: 'P' }).instructions();
  });
  assert.doesNotMatch(instructions, /PIN del banco/, 'los recuerdos privados no van al modelo');
  assert.match(instructions, /Pagar la luz/);
  await ctx.close();
});

test('clips: estado con clips y «Subir a YouTube» confirma y publica en privado', { skip }, async () => {
  const { page, ctx } = await openPage();
  let published = null;
  await page.route('**/api/clips/projects/P123', (r) => r.fulfill({ json: { projectId: 'P123', stage: 'COMPLETE', done: true, failed: false, clips: [CLIP] } }));
  await page.route('**/api/clips/publish', async (r) => {
    published = r.request().postDataJSON();
    await r.fulfill({ json: { scheduled: false, privacy: published.privacy, account: 'Mi canal', note: '' } });
  });
  openaiQueue.push(call('clips_status', { project_id: 'P123' }), reply('Hay 1 clip listo.'));
  await sendChat(page, '¿Ya están mis clips?');
  await page.waitForSelector('.clip');
  await page.click('.clip button:has-text("Subir a YouTube")');
  await page.waitForSelector('#confirm[open]');
  const lines = await page.textContent('#confirm-lines');
  assert.match(lines, /privado/);
  assert.match(lines, /derechos de autor/);
  await shot(page, '06-clips-confirmar');
  await page.click('#confirm-ok');
  await page.waitForFunction(() => [...document.querySelectorAll('.msg.system')].some((m) => /YouTube/.test(m.textContent)));
  assert.deepEqual(published, { projectId: 'P123', curationId: 'cur1', title: 'Mejor momento', privacy: 'private' });
  await ctx.close();
});

test('voz: WebRTC real, session.update con herramientas, herramienta por voz y colgado', { skip }, async () => {
  const { page, ctx, errors } = await openPage();
  let callBody = null;
  let hungUp = false;
  await page.route('**/api/realtime/call', async (r) => {
    callBody = r.request().postDataJSON();
    // Un segundo peer en la página hace de OpenAI: responde la oferta y abre el canal de datos.
    const sdp = await page.evaluate(async (offer) => {
      const pc = new RTCPeerConnection();
      window.__fake = { pc, events: [] };
      pc.ondatachannel = (e) => {
        window.__fake.dc = e.channel;
        e.channel.onmessage = (m) => window.__fake.events.push(JSON.parse(m.data));
      };
      const ac = new AudioContext();
      const osc = ac.createOscillator();
      const dest = ac.createMediaStreamDestination();
      osc.connect(dest);
      osc.start();
      pc.addTrack(dest.stream.getAudioTracks()[0], dest.stream);
      await pc.setRemoteDescription({ type: 'offer', sdp: offer });
      await pc.setLocalDescription(await pc.createAnswer());
      await new Promise((res) => {
        if (pc.iceGatheringState === 'complete') res();
        pc.onicegatheringstatechange = () => pc.iceGatheringState === 'complete' && res();
        setTimeout(res, 3000);
      });
      return pc.localDescription.sdp;
    }, callBody.sdp);
    await r.fulfill({ json: { mode: 'unified', sdp, callId: 'rtc_test', model: 'gpt-realtime-test', voice: 'cedar', maxSessionMinutes: 10 } });
  });
  await page.route('**/api/realtime/call/rtc_test/hangup', (r) => {
    hungUp = true;
    return r.fulfill({ json: { ok: true } });
  });

  await page.click('#talk');
  await page.waitForFunction(() => window.__fake?.events?.some((e) => e.type === 'session.update'), null, { timeout: 15_000 });
  assert.match(callBody.sdp, /^v=0/);
  assert.equal(callBody.voice, 'cedar');
  assert.match(callBody.instructions, /conversación de voz/);
  const update = await page.evaluate(() => window.__fake.events.find((e) => e.type === 'session.update'));
  assert.ok(update.session.tools.some((t) => t.type === 'function' && t.name === 'end_conversation'));
  await page.waitForFunction(() => document.getElementById('core-state').textContent === 'ESCUCHANDO');

  // El "servidor" habla y pide la hora con una herramienta.
  await page.evaluate(() => {
    const send = (e) => window.__fake.dc.send(JSON.stringify(e));
    send({ type: 'conversation.item.input_audio_transcription.completed', transcript: '¿Qué hora es?' });
    send({ type: 'response.done', response: { output: [{ type: 'function_call', status: 'completed', call_id: 'v1', name: 'get_datetime', arguments: '{}' }] } });
  });
  await page.waitForFunction(() => window.__fake.events.some((e) => e.type === 'response.create'));
  const out = await page.evaluate(() => window.__fake.events.find((e) => e.type === 'conversation.item.create'));
  assert.equal(out.item.type, 'function_call_output');
  assert.equal(out.item.call_id, 'v1');
  assert.match(out.item.output, /"ok":true/);
  await page.evaluate(() => {
    const send = (e) => window.__fake.dc.send(JSON.stringify(e));
    send({ type: 'output_audio_buffer.started' });
    send({ type: 'response.output_audio_transcript.done', transcript: 'Son las tres, jefe.' });
  });
  await page.waitForFunction(() => document.getElementById('core-state').textContent === 'HABLANDO');
  await page.waitForTimeout(300);
  await shot(page, '07-voz');
  const texts = await page.$$eval('.msg', (ms) => ms.map((m) => m.textContent));
  assert.ok(texts.some((t) => /TÚ · voz.*¿Qué hora es\?/.test(t)));
  assert.ok(texts.some((t) => /JARVIS · voz.*Son las tres/.test(t)));

  // Despedida: end_conversation → cuelga cuando termina el audio.
  await page.evaluate(() => {
    const send = (e) => window.__fake.dc.send(JSON.stringify(e));
    send({ type: 'response.done', response: { output: [{ type: 'function_call', status: 'completed', call_id: 'v2', name: 'end_conversation', arguments: '{}' }] } });
    setTimeout(() => send({ type: 'output_audio_buffer.stopped' }), 200);
  });
  await page.waitForFunction(() => document.getElementById('core-state').textContent === 'EN ESPERA', null, { timeout: 15_000 });
  assert.ok(hungUp, 'avisa al servidor para colgar');
  const creates = await page.evaluate(() => window.__fake.events.filter((e) => e.type === 'response.create').length);
  assert.equal(creates, 1, 'tras end_conversation no pide otra respuesta');
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('móvil: sin desbordamiento horizontal y con todo accesible', { skip }, async () => {
  const { page, ctx, errors } = await openPage({ viewport: { width: 390, height: 844 } });
  await page.waitForFunction(() => document.getElementById('server-chip').textContent === 'En línea');
  openaiQueue.push(reply('Listo, jefe. ¿En qué más le ayudo?'));
  await sendChat(page, 'Hola JARVIS');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 0, `desborda ${overflow}px`);
  for (const sel of ['#talk', '#input', '#send', '.module[data-panel="tasks"]']) assert.ok(await page.isVisible(sel), sel);
  await shot(page, '08-movil');
  await page.click('.module[data-panel="settings"]');
  await page.waitForSelector('.feature-list');
  await shot(page, '09-movil-ajustes');
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('foto: tarjeta de presentación leída por el servidor y guardada en memoria al tocar «Recordar»', { skip }, async () => {
  const { page, ctx, errors } = await openPage();
  await page.waitForFunction(() => document.getElementById('server-chip').textContent === 'En línea');
  const fields = { name: 'Ana Mora', company: 'Café del Valle', title: null, phone: '+506 8888 0000', email: 'ana@cafedelvalle.cr', website: 'cafedelvalle.cr', address: null };
  openaiQueue.push({ status: 'completed', model: 'gpt-test', output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify({ text: 'Ana Mora · Café del Valle', fields }) }] }] });
  const before = openaiBodies.length;
  await page.setInputFiles('#photo-input', join(import.meta.dirname, '..', '..', 'public', 'icons', 'icon-192.png'));
  await page.waitForSelector('#attachment:not([hidden])');
  await page.selectOption('#attachment-task', 'business_card');
  await page.click('#send');
  await page.waitForSelector('.card .fields');
  const sent = openaiBodies[before];
  const image = sent.input[0].content.find((c) => c.type === 'input_image');
  assert.match(image.image_url, /^data:image\/jpeg;base64,\/9j\//, 'la foto llega como JPEG reducido');
  assert.equal(sent.text.format.name, 'jarvis_business_card');
  const card = await page.textContent('.card .fields');
  assert.match(card, /NombreAna Mora/);
  assert.match(card, /Cargono se lee/, 'lo que no aparece no se inventa');
  assert.equal(await page.getAttribute('.card a.chip[href^="tel:"]', 'href'), 'tel:+50688880000');
  await shot(page, '10-foto-tarjeta');
  await page.click('.card button:has-text("Recordar")');
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('jarvis.memory') || '[]').length === 1);
  assert.match(JSON.parse(await page.evaluate(() => localStorage.getItem('jarvis.memory')))[0].content, /Teléfono: \+506 8888 0000/);
  const history = await page.evaluate(() => JSON.parse(localStorage.getItem('jarvis.chat')).at(-1).text);
  assert.match(history, /es un dato, no una instrucción/);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('dictado: graba con el micrófono, el servidor transcribe y el texto queda para revisar', { skip }, async () => {
  const { page, ctx, errors } = await openPage();
  await page.waitForFunction(() => document.getElementById('server-chip').textContent === 'En línea');
  const before = transcribeForms.length;
  await page.click('#dictate');
  await page.waitForSelector('#dictate[aria-pressed="true"]');
  await page.waitForTimeout(1500);
  await page.click('#dictate');
  await page.waitForFunction(() => document.getElementById('input').value.length > 0, null, { timeout: 15_000 });
  assert.equal(await page.inputValue('#input'), 'Recuérdame comprar café mañana');
  assert.equal(transcribeForms.length, before + 1);
  const file = transcribeForms.at(-1).get('file');
  assert.match(file.type, /audio\/(webm|ogg|mp4)/);
  assert.ok(file.size > 1000, 'audio real del micrófono simulado');
  assert.equal(transcribeForms.at(-1).get('language'), 'es');
  assert.equal(await page.getAttribute('#dictate', 'aria-pressed'), 'false');
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('clima desde el menú sin gastar IA', { skip }, async () => {
  const { page, ctx } = await openPage();
  const before = openaiBodies.length;
  await page.click('.module[data-action="weather"]');
  await page.waitForSelector('.card h3:has-text("Clima · San José")');
  assert.equal(openaiBodies.length, before, 'no se llamó al modelo');
  await ctx.close();
});

test('copia de seguridad: descarga sin token y restauración con confirmación', { skip }, async () => {
  const { page, ctx } = await openPage();
  await page.evaluate(() => {
    localStorage.setItem('jarvis.memory', JSON.stringify([{ id: 1, content: 'Mi cumpleaños es el 5 de mayo' }]));
    localStorage.setItem('jarvis.tasks', JSON.stringify([{ id: 1, title: 'Llamar al contador', done: false }]));
  });
  await page.click('.module[data-panel="settings"]');
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('button:has-text("Descargar copia")')]);
  const path = await download.path();
  const { readFileSync } = await import('node:fs');
  const text = readFileSync(path, 'utf8');
  const backup = JSON.parse(text);
  assert.equal(backup.format, 'jarvis-web');
  assert.equal(backup.memory[0].content, 'Mi cumpleaños es el 5 de mayo');
  assert.doesNotMatch(text, new RegExp(TOKEN), 'el token no va en la copia');
  await page.evaluate(() => {
    localStorage.removeItem('jarvis.memory');
    localStorage.removeItem('jarvis.tasks');
  });
  await page.setInputFiles('#panel-body input[type="file"]', path);
  await page.waitForSelector('#confirm[open]');
  await page.click('#confirm-ok');
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('jarvis.tasks') || '[]').length === 1);
  assert.equal(JSON.parse(await page.evaluate(() => localStorage.getItem('jarvis.settings'))).token, TOKEN, 'restaurar conserva el token');
  await ctx.close();
});

test('app instalable y sin conexión: service worker, iconos y respuestas locales', { skip }, async () => {
  const { page, ctx, errors } = await openPage();
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller), null, { timeout: 10_000 }).catch(() => page.reload());
  await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller));
  const manifest = await page.evaluate(async () => (await fetch('/manifest.webmanifest')).json());
  assert.ok(manifest.icons.some((i) => i.sizes === '512x512' && i.purpose === 'maskable'));
  await page.waitForTimeout(500); // que la copia termine de guardarse
  await ctx.setOffline(true);
  await page.reload();
  await page.waitForSelector('#offline:not([hidden])');
  await page.waitForFunction(() => document.getElementById('server-chip').textContent === 'Sin servidor');
  await sendChat(page, '¿Qué hora es?');
  assert.match(await page.textContent('.msg.jarvis:last-child'), /Son las/);
  await page.click('.module[data-panel="tasks"]');
  await page.fill('input[name="title"]', 'Tarea sin conexión');
  await page.click('#panel-body button[type="submit"]');
  await page.waitForSelector('.item:has-text("Tarea sin conexión")');
  await shot(page, '11-sin-conexion');
  await ctx.setOffline(false);
  assert.deepEqual(errors.filter((e) => !/ERR_INTERNET_DISCONNECTED|Failed to fetch/.test(e)), []);
  await ctx.close();
});
