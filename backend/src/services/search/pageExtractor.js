// Extracción de texto y datos de contacto de una página HTML.
// La salida se marca como no confiable: el asistente la trata como datos, nunca como instrucciones.
import { stripHtml } from './normalize.js';

const ENTITY = { '&quot;': '"', '&#39;': "'", '&amp;': '&', '&lt;': '<', '&gt;': '>', '&nbsp;': ' ' };

function decode(s) {
  return s
    .replace(/&(quot|#39|amp|lt|gt|nbsp);/g, (m) => ENTITY[m] ?? m)
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)));
}

function meta(html, names) {
  for (const name of names) {
    const re = new RegExp(
      `<meta[^>]+(?:name|property|itemprop)=["']${name}["'][^>]*content=["']([^"']+)["']|<meta[^>]+content=["']([^"']+)["'][^>]*(?:name|property|itemprop)=["']${name}["']`,
      'i',
    );
    const m = re.exec(html);
    if (m) return decode(m[1] ?? m[2]).trim();
  }
  return null;
}

/** Normaliza teléfonos de Costa Rica (8 dígitos, prefijo 506 opcional). */
export function normalizeCrPhone(raw) {
  const digits = String(raw).replace(/\D/g, '');
  const local = digits.startsWith('506') && digits.length === 11 ? digits.slice(3) : digits;
  if (local.length !== 8 || !/^[2-8]/.test(local)) return null;
  return `+506 ${local.slice(0, 4)}-${local.slice(4)}`;
}

export function extractContacts(html, text) {
  const emails = new Set();
  const phones = new Set();
  for (const m of html.matchAll(/mailto:([^"'?\s>]+)/gi)) emails.add(decode(m[1]).toLowerCase());
  for (const m of text.matchAll(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g)) emails.add(m[0].toLowerCase());
  for (const m of html.matchAll(/tel:([+\d\s().-]{8,20})/gi)) {
    const p = normalizeCrPhone(m[1]);
    if (p) phones.add(p);
  }
  for (const m of text.matchAll(/(?:\+?506[\s-]?)?\b[2-8]\d{3}[\s-]?\d{4}\b/g)) {
    const p = normalizeCrPhone(m[0]);
    if (p) phones.add(p);
  }
  // Descarta nombres de imágenes con "@" (logo@2x.png) y dominios reservados de ejemplo (RFC 2606).
  const isAsset = (e) =>
    /\.(png|jpe?g|gif|webp|svg)$/i.test(e) || /@(example\.(com|org|net)|[^@]*\.example)$/i.test(e);
  return { emails: [...emails].filter((e) => !isAsset(e)).slice(0, 10), phones: [...phones].slice(0, 10) };
}

export function extractPage(html, { maxChars = 6000 } = {}) {
  const titleMatch = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html);
  const cleaned = html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<(nav|footer|header|svg|form)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<\/(p|div|li|h[1-6]|tr|br)>/gi, '\n');
  const text = stripHtml(decode(cleaned));
  const publishedAt =
    meta(html, ['article:published_time', 'datePublished', 'date', 'DC.date.issued']) ??
    /<time[^>]+datetime=["']([^"']+)["']/i.exec(html)?.[1] ??
    null;
  return {
    title: titleMatch ? stripHtml(decode(titleMatch[1])).slice(0, 300) : null,
    description: meta(html, ['description', 'og:description']),
    publishedAt,
    text: text.slice(0, maxChars),
    truncated: text.length > maxChars,
    contacts: extractContacts(html, text),
  };
}
