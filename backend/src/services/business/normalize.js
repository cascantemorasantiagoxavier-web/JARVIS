// Convierte elementos OSM en fichas de negocio. Cada dato lleva su estado y fuente:
//  found        — presente en una fuente
//  verified     — confirmado por una segunda fuente independiente
//  unavailable  — ninguna fuente lo aporta (se muestra "No disponible")
//  conflict     — las fuentes discrepan (se listan alternativas)
//  approximate  — valor aproximado (p. ej. centro de un polígono)
import { labelFromTags, normalizeText } from './categories.js';
import { normalizeCrPhone } from '../search/pageExtractor.js';

export const OSM_SOURCE = 'osm';
export const OSM_ADMIN_SOURCE = 'osm-admin';

export function field(value, status, sources = [], extra = {}) {
  if (value === null || value === undefined || value === '') {
    return { value: null, status: 'unavailable', sources: [] };
  }
  return { value, status, sources, ...extra };
}

const unavailable = () => field(null);

function firstTag(tags, keys) {
  for (const k of keys) if (tags[k]) return String(tags[k]).trim();
  return null;
}

function splitMulti(v) {
  return v ? v.split(/\s*;\s*/).filter(Boolean) : [];
}

function cleanWebsite(v) {
  if (!v) return null;
  const s = v.trim();
  const withScheme = /^https?:\/\//i.test(s) ? s : `https://${s}`;
  try {
    const u = new URL(withScheme);
    return /\./.test(u.hostname) ? u.toString() : null;
  } catch {
    return null;
  }
}

function composeAddress(tags) {
  if (tags['addr:full']) return tags['addr:full'];
  const street = [tags['addr:street'], tags['addr:housenumber']].filter(Boolean).join(' ');
  const parts = [street, tags['addr:place'], tags['addr:suburb'], tags['addr:city']].filter(Boolean);
  return parts.length ? parts.join(', ') : null;
}

export function osmUrl(type, id) {
  return `https://www.openstreetmap.org/${type}/${id}`;
}

export function normalizeElement(el, { retrievedAt, categoryLabel }) {
  const tags = el.tags ?? {};
  const type = el.type;
  const lat = el.lat ?? el.center?.lat;
  const lon = el.lon ?? el.center?.lon;
  const hasCoords = Number.isFinite(lat) && Number.isFinite(lon);

  const phonesRaw = splitMulti(firstTag(tags, ['phone', 'contact:phone', 'contact:mobile', 'phone:mobile']));
  const phones = phonesRaw.map((p) => normalizeCrPhone(p) ?? p);
  const emails = splitMulti(firstTag(tags, ['email', 'contact:email']));
  const website = cleanWebsite(firstTag(tags, ['website', 'contact:website', 'url']));
  const brandWebsite = !website ? cleanWebsite(tags['brand:website']) : null;
  const name = firstTag(tags, ['name', 'name:es', 'brand', 'operator']);

  return {
    id: `osm:${type}:${el.id}`,
    name: field(name, 'found', [OSM_SOURCE]),
    category: field(labelFromTags(tags) ?? categoryLabel, 'found', [OSM_SOURCE]),
    brand: field(tags.brand ?? null, 'found', [OSM_SOURCE]),
    address: field(composeAddress(tags), 'found', [OSM_SOURCE]),
    province: unavailable(),
    canton: unavailable(),
    district: unavailable(),
    phone: field(phones[0] ?? null, 'found', [OSM_SOURCE], phones.length > 1 ? { alternatives: phones.slice(1).map((v) => ({ value: v, source: OSM_SOURCE })) } : {}),
    website: website
      ? field(website, 'found', [OSM_SOURCE])
      : brandWebsite
        ? field(brandWebsite, 'found', [OSM_SOURCE], { note: 'Sitio de la marca, no necesariamente de esta sucursal.' })
        : unavailable(),
    email: field(emails[0] ?? null, 'found', [OSM_SOURCE]),
    openingHours: field(tags.opening_hours ?? null, 'found', [OSM_SOURCE]),
    coordinates: hasCoords
      ? field({ lat, lon }, type === 'node' ? 'found' : 'approximate', [OSM_SOURCE], type === 'node' ? {} : { note: 'Centro del área del edificio o parcela.' })
      : unavailable(),
    links: {
      osm: osmUrl(type, el.id),
      geoUri: hasCoords ? `geo:${lat},${lon}?q=${lat},${lon}(${encodeURIComponent(name ?? 'Negocio')})` : null,
      directions: hasCoords ? `https://www.google.com/maps/dir/?api=1&destination=${lat},${lon}` : null,
    },
    sources: {
      [OSM_SOURCE]: {
        name: 'OpenStreetMap',
        url: osmUrl(type, el.id),
        license: 'ODbL — © colaboradores de OpenStreetMap',
        retrievedAt,
      },
    },
    retrievedAt,
  };
}

/** Aplica provincia/cantón/distrito obtenidos de los límites administrativos de OSM. */
export function applyAdmin(business, admin, retrievedAt) {
  if (!admin) return business;
  const b = { ...business, sources: { ...business.sources } };
  if (admin['4']) b.province = field(admin['4'], 'found', [OSM_ADMIN_SOURCE]);
  if (admin['6']) b.canton = field(admin['6'], 'found', [OSM_ADMIN_SOURCE]);
  if (admin['8']) b.district = field(admin['8'], 'found', [OSM_ADMIN_SOURCE]);
  if (admin['4'] || admin['6'] || admin['8']) {
    b.sources[OSM_ADMIN_SOURCE] = {
      name: 'Límites administrativos de OpenStreetMap',
      url: 'https://www.openstreetmap.org',
      license: 'ODbL — © colaboradores de OpenStreetMap',
      retrievedAt,
      note: 'Determinado por la ubicación del punto dentro del límite administrativo.',
    };
  }
  return b;
}

// ----- Deduplicación -----

export function distanceMeters(a, b) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

function richness(b) {
  return ['address', 'phone', 'website', 'email', 'openingHours'].filter((k) => b[k].value).length + (b.coordinates.status === 'found' ? 1 : 0);
}

function mergeField(primary, secondary) {
  if (!primary.value) return secondary;
  if (!secondary.value) return primary;
  const same =
    typeof primary.value === 'string' && typeof secondary.value === 'string'
      ? normalizeText(primary.value) === normalizeText(secondary.value)
      : JSON.stringify(primary.value) === JSON.stringify(secondary.value);
  if (same) return primary;
  return {
    ...primary,
    status: 'conflict',
    alternatives: [...(primary.alternatives ?? []), { value: secondary.value, source: secondary.sources?.[0] ?? 'osm' }],
  };
}

/**
 * Fusiona registros del mismo negocio (mismo nombre normalizado a menos de `radius`
 * metros, p. ej. un nodo y el polígono del edificio). Negocios homónimos más alejados
 * se conservan por separado (sucursales distintas).
 */
export function dedupeBusinesses(list, radius = 75) {
  const out = [];
  for (const b of list) {
    const key = normalizeText(b.name.value ?? '');
    const dup = key
      ? out.find(
          (o) =>
            normalizeText(o.name.value ?? '') === key &&
            o.coordinates.value &&
            b.coordinates.value &&
            distanceMeters(o.coordinates.value, b.coordinates.value) <= radius,
        )
      : null;
    if (!dup) {
      out.push(b);
      continue;
    }
    const [primary, secondary] = richness(b) > richness(dup) ? [b, dup] : [dup, b];
    const merged = { ...primary, mergedIds: [...(primary.mergedIds ?? []), secondary.id, ...(secondary.mergedIds ?? [])] };
    for (const k of ['address', 'phone', 'website', 'email', 'openingHours', 'brand']) {
      merged[k] = mergeField(primary[k], secondary[k]);
    }
    if (primary.coordinates.status === 'approximate' && secondary.coordinates.status === 'found') {
      merged.coordinates = secondary.coordinates;
    }
    out[out.indexOf(dup)] = merged;
  }
  return out;
}

export function sortBusinesses(list) {
  return [...list].sort((a, b) => {
    if (!a.name.value !== !b.name.value) return a.name.value ? -1 : 1;
    return richness(b) - richness(a) || String(a.name.value).localeCompare(String(b.name.value), 'es');
  });
}
