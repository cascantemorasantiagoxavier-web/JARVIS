// Copia los archivos compartidos que usa la web (contrato de herramientas y personalidad) a public/shared.
// Uso: npm run sync-shared. Una prueba (test/web.test.js) falla si las copias se desincronizan.
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const shared = join(here, '..', '..', 'shared');
const out = join(here, '..', 'public', 'shared');
mkdirSync(out, { recursive: true });
for (const f of ['tools.json', 'persona-es.md']) {
  copyFileSync(join(shared, f), join(out, f));
  console.log(`copiado ${f}`);
}
