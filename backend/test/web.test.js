import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { PUBLIC_DIR } from '../src/app.js';
import { OPENAI_KEY, startServer, TOKEN } from './helpers.js';

const SHARED = join(PUBLIC_DIR, '..', '..', 'shared');

test('web: / sirve la página de JARVIS sin token y con CSP estricta', async () => {
  const srv = await startServer();
  try {
    const r = await srv.call('GET', '/', undefined, { token: null });
    assert.equal(r.status, 200);
    assert.match(r.headers.get('content-type'), /text\/html/);
    assert.match(r.text, /<title>J\.A\.R\.V\.I\.S\.<\/title>/);
    assert.match(r.text, /<script type="module" src="\/js\/main\.js"><\/script>/);
    assert.doesNotMatch(r.text, /<script>(?!<\/script>)/, 'sin scripts en línea');
    const csp = r.headers.get('content-security-policy');
    assert.match(csp, /script-src 'self'(;|$)/);
    assert.match(csp, /connect-src 'self' https:\/\/api\.open-meteo\.com https:\/\/geocoding-api\.open-meteo\.com/);
    assert.match(csp, /object-src 'none'/);
    assert.doesNotMatch(r.text, new RegExp(OPENAI_KEY));
    assert.doesNotMatch(r.text, new RegExp(TOKEN), 'el token del servidor no va en la página');
  } finally {
    await srv.close();
  }
});

test('web: módulos, estilos, fuentes, globo y manifiesto se sirven con su tipo', async () => {
  const srv = await startServer();
  try {
    const cases = [
      ['/js/main.js', /javascript/],
      ['/js/tools.js', /javascript/],
      ['/css/hud.css', /text\/css/],
      ['/fonts/orbitron_medium.ttf', /font\/ttf|application\/x-font-ttf|application\/octet-stream/],
      ['/data/globe.json', /application\/json/],
      ['/manifest.webmanifest', /manifest\+json|application\/json/],
      ['/icon.svg', /image\/svg\+xml/],
    ];
    for (const [path, type] of cases) {
      const r = await srv.call('GET', path, undefined, { token: null });
      assert.equal(r.status, 200, path);
      assert.match(r.headers.get('content-type'), type, path);
    }
    const globe = JSON.parse((await srv.call('GET', '/data/globe.json', undefined, { token: null })).text);
    assert.ok(globe.land.length > 4000, 'puntos de tierra');
    assert.ok(globe.cities.some((c) => c[2] === 'San José'), 'San José en las capitales');
  } finally {
    await srv.close();
  }
});

test('web: las rutas /api siguen exigiendo el token aunque haya archivos estáticos', async () => {
  const srv = await startServer();
  try {
    assert.equal((await srv.call('GET', '/api/usage', undefined, { token: null })).status, 401);
    assert.equal((await srv.call('GET', '/api/usage')).status, 200);
    assert.equal((await srv.call('GET', '/api/no-existe')).status, 404);
    // Recorrer directorios fuera de public no funciona.
    const r = await fetch(`${srv.base}/..%2F..%2Fsrc%2Fconfig.js`);
    assert.notEqual(r.status, 200);
    const env = await fetch(`${srv.base}/.env`);
    assert.notEqual(env.status, 200);
  } finally {
    await srv.close();
  }
});

test('web: las copias de shared/ en public/shared están sincronizadas (npm run sync-shared)', () => {
  for (const f of ['tools.json', 'persona-es.md']) {
    assert.equal(readFileSync(join(PUBLIC_DIR, 'shared', f), 'utf8'), readFileSync(join(SHARED, f), 'utf8'), `${f} desincronizado: ejecuta npm run sync-shared`);
  }
});

test('web: todas las herramientas que usa la web existen en el contrato y respetan su riesgo', async () => {
  const { WEB_TOOLS } = await import('../public/js/tools.js');
  const tools = JSON.parse(readFileSync(join(SHARED, 'tools.json'), 'utf8')).tools;
  const byName = new Map(tools.map((t) => [t.name, t]));
  for (const name of WEB_TOOLS) assert.ok(byName.has(name), `${name} no está en shared/tools.json`);
  // Las sensibles o con confirm están entre las que la web confirma siempre.
  for (const name of WEB_TOOLS) {
    const t = byName.get(name);
    if (t.confirm || t.risk === 'sensitive') assert.ok(['forget', 'delete_task', 'clips_publish'].includes(name), `${name} requiere confirmación`);
  }
});

test('web: ningún archivo público contiene secretos ni usa innerHTML', () => {
  const walk = (dir) => readdirSync(dir, { withFileTypes: true }).flatMap((d) => (d.isDirectory() ? walk(join(dir, d.name)) : [join(dir, d.name)]));
  for (const file of walk(PUBLIC_DIR).filter((f) => /\.(js|html|css|json|md|webmanifest|svg)$/.test(f))) {
    const text = readFileSync(file, 'utf8');
    assert.doesNotMatch(text, /sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,}/, `posible clave en ${file}`);
    if (file.endsWith('.js')) assert.doesNotMatch(text, /\.innerHTML\s*=|insertAdjacentHTML|eval\(|new Function\(/, `HTML/JS dinámico inseguro en ${file}`);
  }
});
