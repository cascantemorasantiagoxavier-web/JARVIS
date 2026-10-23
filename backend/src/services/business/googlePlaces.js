// Google Places API (New), solo para VERIFICAR un negocio que ya viene de OpenStreetMap.
// Contrato verificado con el cliente oficial @googlemaps/places 3.1.0 (protos google.maps.places.v1):
//   POST https://places.googleapis.com/v1/places:searchText (body: textQuery, languageCode, regionCode,
//   maxResultCount 1–20, locationBias.circle {center {latitude, longitude}, radius ≤ 50000}).
//   La máscara de campos va en la cabecera X-Goog-FieldMask (obligatoria).
// Condiciones de Google (sin verificar contra la página oficial; ver ADR-016): los datos de Places no
// se muestran sobre un mapa que no sea de Google y no se guardan de forma permanente. El backend solo
// los guarda en la caché de BUSINESS_CACHE_TTL_SECONDS y la app los quita al guardar o mostrar en el mapa.
import { AppError, Errors } from '../../http/errors.js';

export const GOOGLE_SOURCE_ID = 'google';
export const GOOGLE_NOTICE = 'Datos de Google Maps. No se muestran en el mapa de OpenStreetMap ni se guardan en el teléfono.';

const FIELD_MASK = [
  'places.id',
  'places.displayName',
  'places.formattedAddress',
  'places.location',
  'places.nationalPhoneNumber',
  'places.internationalPhoneNumber',
  'places.websiteUri',
  'places.googleMapsUri',
  'places.businessStatus',
].join(',');

export class GooglePlacesClient {
  constructor({ config, http, usage }) {
    this.cfg = config.google;
    this.http = http;
    this.usage = usage;
  }

  /** Busca por texto cerca de un punto. Devuelve lugares normalizados. */
  async searchText({ textQuery, center, radiusM = 300, max = 5 }) {
    if (this.usage.placesToday() >= this.cfg.placesMaxPerDay) {
      throw Errors.rateLimited('Límite diario de consultas a Google Maps alcanzado (control de costos).', 3600);
    }
    const body = { textQuery, languageCode: 'es', regionCode: 'CR', maxResultCount: max };
    if (center) body.locationBias = { circle: { center: { latitude: center.lat, longitude: center.lon }, radius: radiusM } };
    this.usage.recordPlaces();
    const res = await this.http.request('Google Maps', `${this.cfg.placesBaseUrl}/v1/places:searchText`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'X-Goog-Api-Key': this.cfg.mapsKey,
        'X-Goog-FieldMask': FIELD_MASK,
      },
      body: JSON.stringify(body),
      mapError: mapGoogleError,
      timeout: 15_000,
    });
    const places = res.json()?.places;
    return Array.isArray(places) ? places.map(normalizePlace) : [];
  }
}

export function normalizePlace(p) {
  return {
    placeId: p.id ?? null,
    name: p.displayName?.text ?? null,
    address: p.formattedAddress ?? null,
    lat: typeof p.location?.latitude === 'number' ? p.location.latitude : null,
    lon: typeof p.location?.longitude === 'number' ? p.location.longitude : null,
    phone: p.internationalPhoneNumber ?? p.nationalPhoneNumber ?? null,
    website: p.websiteUri ?? null,
    mapsUrl: p.googleMapsUri ?? null,
    businessStatus: p.businessStatus ?? null,
  };
}

const STOP = new Set(['de', 'del', 'la', 'el', 'los', 'las', 'y', 'sa', 's', 'a', 'srl', 'ltda', 'cr', 'costa', 'rica']);
const tokens = (s) =>
  new Set(
    String(s ?? '')
      .normalize('NFD')
      .replace(/\p{M}/gu, '')
      .toLowerCase()
      .replace(/[^a-z0-9 ]/g, ' ')
      .split(/\s+/)
      .filter((t) => t.length > 1 && !STOP.has(t)),
  );

/** Similitud de nombres (Dice sobre palabras significativas). */
export function nameSimilarity(a, b) {
  const x = tokens(a);
  const y = tokens(b);
  if (!x.size || !y.size) return 0;
  let common = 0;
  for (const t of x) if (y.has(t)) common++;
  return (2 * common) / (x.size + y.size);
}

export function distanceM(a, b) {
  const R = 6_371_000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/**
 * Empareja con criterio estricto: mismo nombre (similitud ≥ 0,6) y a ≤ 200 m. Sin coordenadas en OSM
 * no se empareja nunca: los lugares quedan como candidatos para que el usuario decida.
 */
export function matchPlace(name, coords, places) {
  if (!coords) return null;
  const scored = places
    .filter((p) => p.lat != null && p.lon != null)
    .map((p) => ({ p, sim: nameSimilarity(name, p.name), dist: distanceM(coords, { lat: p.lat, lon: p.lon }) }))
    .filter((s) => s.sim >= 0.6 && s.dist <= 200)
    .sort((a, b) => b.sim - a.sim || a.dist - b.dist);
  return scored[0] ? { ...scored[0].p, similarity: scored[0].sim, distanceM: Math.round(scored[0].dist) } : null;
}

export function normalizeUrl(u) {
  try {
    const url = new URL(u);
    return (url.hostname.replace(/^www\./, '') + url.pathname.replace(/\/+$/, '')).toLowerCase();
  } catch {
    return String(u).toLowerCase();
  }
}

function mapGoogleError(status, text) {
  let detail = {};
  try {
    detail = JSON.parse(text)?.error ?? {};
  } catch {
    /* sin JSON */
  }
  const msg = String(detail.message ?? '');
  if (status === 400 && /api key/i.test(msg)) return Errors.upstreamAuth('Google Maps');
  if (status === 401) return Errors.upstreamAuth('Google Maps');
  if (status === 403) {
    return new AppError(
      502,
      'upstream_forbidden',
      'La clave de Google no permite usar Places API (New): actívala en el proyecto de Google Cloud, con facturación, y revisa las restricciones de la clave.',
    );
  }
  if (status === 429) return Errors.upstreamQuota('Google Maps');
  return undefined;
}
