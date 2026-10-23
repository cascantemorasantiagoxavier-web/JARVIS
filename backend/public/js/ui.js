// Piezas de interfaz de JARVIS Web. Todo se construye con nodos DOM y textContent: nada de innerHTML
// con contenido del modelo, de páginas web ni de OpenStreetMap (son datos no confiables).

/** Crea un elemento. `attrs`: atributos, `class`, `on<Evento>` y `dataset`. Los hijos pueden ser texto. */
export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'dataset') Object.assign(node.dataset, v);
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'href') {
      const safe = safeHref(v);
      if (safe) node.setAttribute('href', safe);
    } else node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null && c !== false) node.append(c instanceof Node ? c : String(c));
  return node;
}

/** Solo enlaces http(s) y tel:. Cualquier otro esquema (javascript:, data:…) se descarta. */
export function safeHref(href) {
  const s = String(href ?? '').trim();
  if (/^tel:\+?[\d-]{3,20}$/.test(s)) return s;
  try {
    const u = new URL(s);
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.toString() : null;
  } catch {
    return null;
  }
}

function link(text, href) {
  const safe = safeHref(href);
  if (!safe) return document.createTextNode(text);
  return el('a', { href: safe, target: safe.startsWith('tel:') ? null : '_blank', rel: 'noopener noreferrer' }, text);
}

const INLINE = /(\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^\]\n]+\]\(https?:\/\/[^\s)]+\)|https?:\/\/[^\s<>()]+[^\s<>().,;:!?¿¡"'])/g;

function inline(text) {
  const out = [];
  let last = 0;
  for (const m of text.matchAll(INLINE)) {
    if (m.index > last) out.push(text.slice(last, m.index));
    const t = m[0];
    if (t.startsWith('**')) out.push(el('strong', {}, t.slice(2, -2)));
    else if (t.startsWith('`')) out.push(el('code', {}, t.slice(1, -1)));
    else if (t.startsWith('[')) {
      const mm = t.match(/^\[([^\]]+)\]\((.+)\)$/);
      out.push(link(mm[1], mm[2]));
    } else out.push(link(t, t));
    last = m.index + t.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

/** Markdown mínimo y seguro: párrafos, listas, negritas, código y enlaces http(s). */
export function renderMarkdown(text) {
  const frag = document.createDocumentFragment();
  const lines = String(text ?? '').replace(/\r/g, '').split('\n');
  let list = null;
  let para = [];
  const flushPara = () => {
    if (para.length) frag.append(el('p', {}, ...para.flatMap((l, i) => (i ? [el('br'), ...inline(l)] : inline(l)))));
    para = [];
  };
  for (const raw of lines) {
    const line = raw.trimEnd();
    const bullet = line.match(/^\s*[-*•]\s+(.*)$/);
    const num = line.match(/^\s*\d+[.)]\s+(.*)$/);
    if (bullet || num) {
      flushPara();
      const tag = bullet ? 'ul' : 'ol';
      if (!list || list.tagName.toLowerCase() !== tag) {
        list = el(tag);
        frag.append(list);
      }
      list.append(el('li', {}, ...inline((bullet ?? num)[1])));
      continue;
    }
    list = null;
    if (!line.trim()) {
      flushPara();
      continue;
    }
    const h = line.match(/^#{1,6}\s+(.*)$/);
    if (h) {
      flushPara();
      frag.append(el('p', {}, el('strong', {}, h[1])));
      continue;
    }
    para.push(line);
  }
  flushPara();
  return frag;
}

const STATUS = { verified: 'verificado', found: 'encontrado', approximate: 'aproximado', conflict: 'en conflicto', unavailable: 'no disponible' };

function statusBadge(f) {
  const s = f?.status ?? 'unavailable';
  return el('span', { class: `st st-${s}`, title: f?.note ?? null }, STATUS[s] ?? s);
}

function fieldRow(label, f, render = (v) => v) {
  const value = f?.value;
  return [
    el('dt', {}, label),
    el('dd', {}, value == null ? el('span', { class: 'muted' }, 'no disponible') : render(value), value == null ? null : statusBadge(f)),
  ];
}

/**
 * Tarjeta de un resultado de herramienta.
 * @param {object} card
 * @param {{verify(id:string):void, publishClip(projectId:string, clip:object):void, refreshClips(projectId:string):void}} handlers
 */
export function renderCard(card, handlers) {
  switch (card.type) {
    case 'web':
      return el('div', { class: 'card' },
        el('h3', {}, `Web · ${card.query}`),
        ...(card.results ?? []).slice(0, 8).map((r) => el('div', { class: 'web-result' },
          link(r.title || r.url, r.url),
          el('p', {}, [r.source, r.publishedAt].filter(Boolean).join(' · ')),
          r.snippet ? el('p', {}, r.snippet) : null)),
        el('div', { class: 'source' }, `Fuente: ${card.provider ?? 'búsqueda web'}. Contenido de terceros.`));
    case 'businesses':
      return el('div', { class: 'card' },
        el('h3', {}, card.title ?? 'Negocios'),
        ...(card.results ?? []).map((b) => businessItem(b, handlers)),
        el('div', { class: 'source' }, card.source?.license ?? 'OpenStreetMap — ODbL © colaboradores de OpenStreetMap'));
    case 'actions':
      return el('div', { class: 'card' },
        el('h3', {}, card.title ?? 'Acciones'),
        el('div', { class: 'actions' }, ...(card.actions ?? []).map(actionButton)));
    case 'info':
      return el('div', { class: 'card' }, el('h3', {}, card.title ?? ''), el('ul', { class: 'lines' }, ...(card.lines ?? []).map((l) => el('li', {}, l))));
    case 'clips':
      return clipsCard(card, handlers);
    default:
      return null;
  }
}

function businessItem(b, handlers) {
  const phone = b.phone?.value;
  const coords = b.coordinates?.value;
  const where = [b.district?.value, b.canton?.value, b.province?.value].filter(Boolean).join(', ');
  const actions = [
    phone ? el('a', { class: 'chip', href: `tel:${phone.replace(/[^\d+]/g, '')}` }, 'Llamar') : null,
    coords ? el('a', { class: 'chip', href: b.links?.directions ?? `https://www.google.com/maps/dir/?api=1&destination=${coords.lat},${coords.lon}`, target: '_blank', rel: 'noopener noreferrer' }, 'Ruta') : null,
    b.links?.osm ? el('a', { class: 'chip', href: b.links.osm, target: '_blank', rel: 'noopener noreferrer' }, 'Mapa') : null,
    handlers?.verify ? el('button', { class: 'chip', type: 'button', onClick: () => handlers.verify(b.id) }, 'Verificar') : null,
  ];
  return el('div', { class: 'biz' },
    el('div', { class: 'biz-name' }, b.name?.value ?? 'Sin nombre'),
    el('div', { class: 'biz-cat' }, [b.category?.value, where].filter(Boolean).join(' · ')),
    el('dl', {},
      ...fieldRow('Teléfono', b.phone),
      ...fieldRow('Web', b.website, (v) => link(v.replace(/^https?:\/\//, '').replace(/\/$/, ''), v)),
      ...fieldRow('Correo', b.email),
      ...fieldRow('Dirección', b.address),
      ...(b.openingHours?.value ? fieldRow('Horario', b.openingHours) : [])),
    el('div', { class: 'actions' }, ...actions));
}

function actionButton(a) {
  if (a.href) return el('a', { class: 'chip', href: a.href, target: a.href.startsWith('tel:') ? null : '_blank', rel: 'noopener noreferrer' }, a.label);
  if (a.share) {
    return el('button', {
      class: 'chip',
      type: 'button',
      onClick: async () => {
        if (navigator.share) {
          try {
            await navigator.share({ title: a.share.title, text: a.share.text });
          } catch {
            /* la persona canceló */
          }
        } else {
          await navigator.clipboard?.writeText(a.share.text ?? '');
          toast('Texto copiado: pégalo donde quieras enviarlo.');
        }
      },
    }, a.label);
  }
  return el('span', {}, a.label);
}

function clipsCard(card, handlers) {
  const clips = card.clips ?? [];
  return el('div', { class: 'card' },
    el('h3', {}, `Clips · proyecto ${card.projectId}`),
    clips.length
      ? el('div', { class: 'clips-grid' }, ...clips.map((c) => el('div', { class: 'clip' },
        c.previewUrl && safeHref(c.previewUrl) ? el('video', { src: safeHref(c.previewUrl), controls: true, preload: 'none', playsinline: true }) : null,
        el('span', { class: 't' }, `${c.number}. ${c.title ?? 'Clip'}`),
        el('span', { class: 'muted small' }, [c.durationSec ? `${Math.round(c.durationSec)} s` : null, c.score != null ? `puntaje ${c.score}` : null].filter(Boolean).join(' · ')),
        el('div', { class: 'actions' },
          handlers?.publishClip ? el('button', { class: 'chip', type: 'button', onClick: () => handlers.publishClip(card.projectId, c) }, 'Subir a YouTube') : null,
          c.exportUrl ? el('a', { class: 'chip', href: c.exportUrl, target: '_blank', rel: 'noopener noreferrer' }, 'Descargar') : null))))
      : el('p', { class: 'empty' }, 'Todavía no hay clips listos.'),
    handlers?.refreshClips ? el('div', { class: 'actions' }, el('button', { class: 'chip', type: 'button', onClick: () => handlers.refreshClips(card.projectId) }, 'Actualizar estado')) : null);
}

/** Aviso breve en la esquina. */
export function toast(message, ms = 4500) {
  const box = document.getElementById('toasts');
  const t = el('div', { class: 'toast', role: 'status' }, message);
  box.append(t);
  setTimeout(() => t.remove(), ms);
}

/** Diálogo de confirmación. Devuelve true solo si la persona toca «Confirmar». */
export function confirmDialog(title, lines = [], okLabel = 'Confirmar') {
  const dlg = document.getElementById('confirm');
  document.getElementById('confirm-title').textContent = title;
  document.getElementById('confirm-ok').textContent = okLabel;
  document.getElementById('confirm-lines').replaceChildren(...lines.map((l) => el('li', {}, l)));
  return new Promise((resolve) => {
    dlg.addEventListener('close', () => resolve(dlg.returnValue === 'ok'), { once: true });
    dlg.returnValue = '';
    dlg.showModal();
  });
}
