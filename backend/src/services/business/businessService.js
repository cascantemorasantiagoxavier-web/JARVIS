// Búsqueda de negocios reales: Nominatim (zona) + Overpass (POIs y límites
// administrativos) + verificación opcional con el sitio web oficial y búsqueda web.
import { AppError, Errors } from '../../http/errors.js';
import { TtlCache } from '../cache.js';
import { extractPage } from '../search/pageExtractor.js';
import { normalizeCrPhone } from '../search/pageExtractor.js';
import { resolveCategory } from './categories.js';
import { GOOGLE_NOTICE, GOOGLE_SOURCE_ID, matchPlace, normalizeUrl } from './googlePlaces.js';
import { buildAdminQuery, buildByIdQuery, buildPoiQuery, parseAdminOutput, pickArea } from './osmClients.js';
import { applyAdmin, dedupeBusinesses, field, normalizeElement, sortBusinesses } from './normalize.js';

const COUNTRY_NAMES = { cr: 'Costa Rica' };

export class BusinessSearchService {
  constructor({ nominatim, overpass, pageFetcher, webSearch, googlePlaces = null, logger, config, now = () => new Date() }) {
    this.nominatim = nominatim;
    this.overpass = overpass;
    this.pageFetcher = pageFetcher;
    this.webSearch = webSearch;
    this.googlePlaces = googlePlaces;
    this.logger = logger;
    this.country = config.osm.defaultCountry;
    this.now = now;
    const ttl = config.osm.cacheTtlSeconds * 1000;
    this.searchCache = new TtlCache(100, ttl);
    this.businessCache = new TtlCache(3000, ttl);
  }

  get googlePlacesEnabled() {
    return Boolean(this.googlePlaces);
  }

  async resolveArea(location) {
    const countryName = COUNTRY_NAMES[this.country];
    const q = countryName && !new RegExp(countryName, 'i').test(location) ? `${location}, ${countryName}` : location;
    const results = await this.nominatim.search(q, { countryCode: this.country, limit: 5 });
    const area = pickArea(results);
    if (!area) throw new AppError(404, 'location_not_found', `No encontré la ubicación "${location}".`);
    return area;
  }

  /**
   * @param {{category:string, location?:string, near?:{lat:number,lon:number,radiusM?:number}, limit?:number, offset?:number}} p
   */
  async search({ category, location, near, limit = 20, offset = 0 }) {
    const cat = resolveCategory(category);
    if (!cat) throw Errors.badRequest('Indica qué tipo de negocio buscar.');
    const retrievedAt = this.now().toISOString();
    const warnings = [];

    let area = null;
    let around = null;
    if (near) {
      around = { lat: near.lat, lon: near.lon, radiusM: near.radiusM ?? 2000 };
    } else {
      if (!location) throw Errors.badRequest('Indica la ubicación (por ejemplo "San José").');
      area = await this.resolveArea(location);
      if (!area.areaId) {
        if (!area.center) throw new AppError(404, 'location_not_found', `No pude delimitar la zona "${location}".`);
        around = { ...area.center, radiusM: 4000 };
        warnings.push(`"${area.name}" no tiene un límite administrativo en OSM; busqué en un radio de 4 km alrededor de su centro.`);
      }
    }

    const cacheKey = JSON.stringify({ c: cat.filters, a: area?.areaId, r: around });
    let all = this.searchCache.get(cacheKey);
    if (!all) {
      const elements = await this.overpass.run(buildPoiQuery({ filters: cat.filters, areaId: area?.areaId, around }));
      all = sortBusinesses(
        dedupeBusinesses(elements.map((el) => normalizeElement(el, { retrievedAt, categoryLabel: cat.label }))),
      );
      this.searchCache.set(cacheKey, all);
    }

    const page = all.slice(offset, offset + limit);
    const enriched = await this.attachAdmin(page, retrievedAt, warnings);
    for (const b of enriched) this.businessCache.set(b.id, b);

    if (cat.coverage === 'low') {
      warnings.push(`OpenStreetMap suele tener poca cobertura de "${cat.label}". Conviene complementar con búsqueda web.`);
    }
    if (all.length === 0) warnings.push('La fuente no devolvió negocios para esa categoría y zona.');

    return {
      query: { category, location: location ?? null, near: near ?? null },
      category: { id: cat.id, label: cat.label, kind: cat.kind },
      area: area ? { name: area.name, displayName: area.displayName, osm: `${area.osmType}/${area.osmId}` } : null,
      around,
      total: all.length,
      offset,
      limit,
      nextOffset: offset + limit < all.length ? offset + limit : null,
      results: enriched,
      source: { name: 'OpenStreetMap (Overpass API + Nominatim)', license: 'ODbL — © colaboradores de OpenStreetMap' },
      retrievedAt,
      warnings,
    };
  }

  async attachAdmin(page, retrievedAt, warnings) {
    const ids = page.filter((b) => b.province.status === 'unavailable').map((b) => b.id);
    const q = buildAdminQuery(ids);
    if (!q) return page;
    try {
      const adminMap = parseAdminOutput(await this.overpass.run(q));
      return page.map((b) => applyAdmin(b, adminMap.get(b.id), retrievedAt));
    } catch (err) {
      warnings.push('No se pudo determinar provincia/cantón/distrito en este momento.');
      this.logger.warn('admin_lookup_failed', { code: err?.code });
      return page;
    }
  }

  async getById(id) {
    if (!/^osm:(node|way|relation):\d+$/.test(id)) throw Errors.badRequest('Identificador de negocio inválido.');
    const cached = this.businessCache.get(id);
    if (cached) return cached;
    const elements = await this.overpass.run(buildByIdQuery(id));
    if (!elements.length) throw Errors.notFound('El negocio ya no existe en la fuente.');
    const retrievedAt = this.now().toISOString();
    const [b] = await this.attachAdmin([normalizeElement(elements[0], { retrievedAt })], retrievedAt, []);
    this.businessCache.set(id, b);
    return b;
  }

  /**
   * Verificación con una segunda fuente:
   *  - Si hay sitio web, se descarga (con protección SSRF) y se comparan teléfonos y correos.
   *  - Si no hay sitio web y hay búsqueda web, se devuelven candidatos SIN fusionarlos.
   */
  async verify(id) {
    const original = await this.getById(id);
    const b = { ...original, sources: { ...original.sources } };
    const report = { checked: [], candidates: [], warnings: [] };
    const retrievedAt = this.now().toISOString();

    if (b.website.value) {
      try {
        const page = await this.pageFetcher(b.website.value);
        const extracted = extractPage(page.body);
        const srcId = 'website';
        b.sources[srcId] = { name: 'Sitio web del negocio', url: page.finalUrl, retrievedAt };
        report.checked.push({ source: page.finalUrl, phones: extracted.contacts.phones, emails: extracted.contacts.emails });
        b.phone = crossCheck(b.phone, extracted.contacts.phones, srcId, (v) => normalizeCrPhone(v) ?? v);
        b.email = crossCheck(b.email, extracted.contacts.emails, srcId, (v) => v.toLowerCase());
        b.website = { ...b.website, status: 'verified', sources: [...new Set([...b.website.sources, srcId])] };
      } catch (err) {
        report.warnings.push(`No se pudo consultar el sitio web: ${err instanceof AppError ? err.message : 'error'}`);
      }
    } else if (this.webSearch?.providers?.length) {
      const place = [b.canton.value, b.province.value, 'Costa Rica'].filter(Boolean).join(' ');
      try {
        const r = await this.webSearch.search({ query: `"${b.name.value}" ${place}`, maxResults: 5 });
        report.candidates = r.results.map((x) => ({ ...x, status: 'candidate', note: 'No verificado: revísalo antes de usarlo.' }));
        if (!r.results.length) report.warnings.push('La búsqueda web no encontró candidatos.');
      } catch (err) {
        report.warnings.push(`Búsqueda web no disponible: ${err instanceof AppError ? err.message : 'error'}`);
      }
    } else {
      report.warnings.push('Sin sitio web en la fuente y sin proveedor de búsqueda web configurado.');
    }
    if (this.googlePlaces) await this.checkWithGoogle(b, report, retrievedAt);
    this.businessCache.set(id, b);
    return { business: b, report, retrievedAt };
  }

  /** Segunda fuente: Google Maps. Solo se cruza si el nombre y la posición coinciden con seguridad. */
  async checkWithGoogle(b, report, retrievedAt) {
    const name = b.name.value;
    if (!name) return;
    const coords = b.coordinates.value ? { lat: b.coordinates.value.lat, lon: b.coordinates.value.lon } : null;
    const textQuery = [name, b.canton.value, b.province.value, 'Costa Rica'].filter(Boolean).join(' ');
    let places;
    try {
      places = await this.googlePlaces.searchText({ textQuery, center: coords });
    } catch (err) {
      report.warnings.push(`Google Maps no disponible: ${err instanceof AppError ? err.message : 'error'}`);
      return;
    }
    const match = matchPlace(name, coords, places);
    if (!match) {
      if (places.length) {
        report.candidates.push(
          ...places.slice(0, 3).map((p) => ({
            title: p.name,
            url: p.mapsUrl,
            snippet: p.address,
            source: 'Google Maps',
            status: 'candidate',
            note: coords
              ? 'No coincide con seguridad (nombre o distancia): revísalo antes de usarlo.'
              : 'El negocio no tiene coordenadas en OpenStreetMap: no se puede confirmar que sea el mismo.',
          })),
        );
      } else {
        report.warnings.push('Google Maps no encontró este negocio.');
      }
      return;
    }
    b.sources[GOOGLE_SOURCE_ID] = { name: 'Google Maps', url: match.mapsUrl, license: GOOGLE_NOTICE, retrievedAt };
    report.checked.push({
      source: 'Google Maps',
      placeId: match.placeId,
      distanceM: match.distanceM,
      phones: match.phone ? [match.phone] : [],
      website: match.website,
    });
    b.phone = crossCheck(b.phone, match.phone ? [match.phone] : [], GOOGLE_SOURCE_ID, (v) => normalizeCrPhone(v) ?? v);
    if (match.website) {
      // Se guarda la URL tal cual; la forma normalizada solo sirve para comparar.
      const cur = b.website;
      if (!cur.value) {
        b.website = field(match.website, 'found', [GOOGLE_SOURCE_ID]);
      } else if (normalizeUrl(cur.value) === normalizeUrl(match.website)) {
        b.website = { ...cur, status: 'verified', sources: [...new Set([...cur.sources, GOOGLE_SOURCE_ID])] };
      } else {
        // Si su propio sitio ya lo verificó, no se rebaja: se anota la alternativa.
        b.website = {
          ...cur,
          status: cur.status === 'verified' ? 'verified' : 'conflict',
          alternatives: [...(cur.alternatives ?? []), { value: match.website, source: GOOGLE_SOURCE_ID }],
        };
      }
    }
    if (match.businessStatus === 'CLOSED_PERMANENTLY') report.warnings.push('Google Maps indica que este negocio cerró definitivamente.');
    if (match.businessStatus === 'CLOSED_TEMPORARILY') report.warnings.push('Google Maps indica que este negocio está cerrado temporalmente.');
  }
}

/** Compara un campo con los valores hallados en otra fuente. */
export function crossCheck(current, found, sourceId, norm) {
  const foundNorm = [...new Set(found.map(norm))];
  if (!foundNorm.length) return current;
  if (!current.value) {
    return field(foundNorm[0], 'found', [sourceId], foundNorm.length > 1 ? { alternatives: foundNorm.slice(1).map((v) => ({ value: v, source: sourceId })) } : {});
  }
  const cur = norm(current.value);
  if (foundNorm.includes(cur)) {
    return { ...current, status: 'verified', sources: [...new Set([...current.sources, sourceId])] };
  }
  return {
    ...current,
    status: 'conflict',
    alternatives: [...(current.alternatives ?? []), ...foundNorm.map((v) => ({ value: v, source: sourceId }))],
  };
}
