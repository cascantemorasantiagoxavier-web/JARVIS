// Genera los iconos PNG de JARVIS Web (instalar como app, avisos, iPhone) a partir de los SVG.
// Uso: npm run build-icons   (necesita playwright y Chromium; los PNG resultantes se suben al repositorio)
import { mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const pub = join(dirname(fileURLToPath(import.meta.url)), '..', 'public');
const out = join(pub, 'icons');
mkdirSync(out, { recursive: true });
const font = readFileSync(join(pub, 'fonts', 'orbitron_bold.ttf')).toString('base64');

const jobs = [
  ['icon.svg', 'icon-192.png', 192],
  ['icon.svg', 'icon-512.png', 512],
  ['icon-maskable.svg', 'maskable-512.png', 512],
  ['icon-maskable.svg', 'icon-180.png', 180], // iPhone: sin esquinas redondeadas propias
];

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
try {
  for (const [svg, png, size] of jobs) {
    const page = await browser.newPage({ viewport: { width: size, height: size } });
    const markup = readFileSync(join(pub, svg), 'utf8');
    await page.setContent(`<!doctype html><style>@font-face{font-family:Orbitron;font-weight:700;src:url(data:font/ttf;base64,${font})}
      html,body{margin:0;background:transparent}svg{display:block;width:${size}px;height:${size}px}</style>${markup}`);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: join(out, png), omitBackground: true });
    await page.close();
    console.log(`icons/${png} (${size}px)`);
  }
} finally {
  await browser.close();
}
