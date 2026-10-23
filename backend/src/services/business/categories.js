// Mapeo de categorías en español (incluidos términos de Costa Rica) a filtros de OpenStreetMap.
// `coverage: 'low'` indica categorías que OSM suele registrar poco; se avisa al usuario.
export const CATEGORIES = [
  { id: 'supermarket', label: 'Supermercado', terms: ['supermercado', 'super', 'hipermercado', 'automercado'], filters: ['["shop"="supermarket"]'] },
  { id: 'convenience', label: 'Minisúper / pulpería', terms: ['minisuper', 'mini super', 'pulperia', 'abastecedor', 'tienda de conveniencia', 'conveniencia'], filters: ['["shop"="convenience"]', '["shop"="general"]'] },
  { id: 'hardware', label: 'Ferretería', terms: ['ferreteria', 'materiales de construccion', 'deposito de materiales'], filters: ['["shop"="hardware"]', '["shop"="doityourself"]', '["shop"="trade"]'] },
  { id: 'pharmacy', label: 'Farmacia', terms: ['farmacia', 'botica'], filters: ['["amenity"="pharmacy"]', '["shop"="chemist"]'] },
  { id: 'restaurant', label: 'Restaurante', terms: ['restaurante', 'soda', 'marisqueria', 'pizzeria', 'comida'], filters: ['["amenity"="restaurant"]', '["amenity"="fast_food"]'] },
  { id: 'cafe', label: 'Cafetería', terms: ['cafeteria', 'cafe', 'coffee'], filters: ['["amenity"="cafe"]'] },
  { id: 'bar', label: 'Bar', terms: ['bar', 'cantina', 'pub'], filters: ['["amenity"="bar"]', '["amenity"="pub"]'] },
  { id: 'hotel', label: 'Hotel / hospedaje', terms: ['hotel', 'hostal', 'hospedaje', 'motel', 'cabinas', 'alojamiento'], filters: ['["tourism"="hotel"]', '["tourism"="hostel"]', '["tourism"="guest_house"]', '["tourism"="motel"]'] },
  { id: 'bakery', label: 'Panadería', terms: ['panaderia', 'reposteria', 'pasteleria'], filters: ['["shop"="bakery"]', '["shop"="pastry"]'] },
  { id: 'butcher', label: 'Carnicería', terms: ['carniceria'], filters: ['["shop"="butcher"]'] },
  { id: 'greengrocer', label: 'Verdulería', terms: ['verduleria', 'fruteria', 'feria'], filters: ['["shop"="greengrocer"]'] },
  { id: 'alcohol', label: 'Licorera', terms: ['licorera', 'licores', 'licoreria'], filters: ['["shop"="alcohol"]'] },
  { id: 'electronics', label: 'Tienda de electrónica', terms: ['electronica', 'electronicos', 'celulares', 'computadoras', 'tecnologia'], filters: ['["shop"="electronics"]', '["shop"="mobile_phone"]', '["shop"="computer"]'] },
  { id: 'appliance', label: 'Electrodomésticos', terms: ['electrodomesticos'], filters: ['["shop"="appliance"]'] },
  { id: 'clothes', label: 'Tienda de ropa', terms: ['ropa', 'boutique', 'tienda de ropa'], filters: ['["shop"="clothes"]', '["shop"="boutique"]'] },
  { id: 'shoes', label: 'Zapatería', terms: ['zapateria', 'zapatos', 'calzado'], filters: ['["shop"="shoes"]'] },
  { id: 'furniture', label: 'Mueblería', terms: ['muebleria', 'muebles'], filters: ['["shop"="furniture"]'] },
  { id: 'books', label: 'Librería / papelería', terms: ['libreria', 'papeleria', 'utiles'], filters: ['["shop"="books"]', '["shop"="stationery"]'] },
  { id: 'pet', label: 'Tienda de mascotas', terms: ['mascotas', 'pet shop', 'agroveterinaria', 'veterinaria'], filters: ['["shop"="pet"]', '["amenity"="veterinary"]', '["shop"="agrarian"]'] },
  { id: 'car_repair', label: 'Taller mecánico', terms: ['taller', 'mecanico', 'taller mecanico'], filters: ['["shop"="car_repair"]'] },
  { id: 'car_parts', label: 'Repuestos', terms: ['repuestos', 'autopartes'], filters: ['["shop"="car_parts"]'] },
  { id: 'fuel', label: 'Gasolinera', terms: ['gasolinera', 'bomba', 'estacion de servicio'], filters: ['["amenity"="fuel"]'] },
  { id: 'bank', label: 'Banco', terms: ['banco', 'bancos'], filters: ['["amenity"="bank"]'] },
  { id: 'clinic', label: 'Clínica / consultorio', terms: ['clinica', 'consultorio', 'ebais'], filters: ['["amenity"="clinic"]', '["amenity"="doctors"]', '["healthcare"="clinic"]'] },
  { id: 'dentist', label: 'Dentista', terms: ['dentista', 'odontologo', 'clinica dental'], filters: ['["amenity"="dentist"]'] },
  { id: 'gym', label: 'Gimnasio', terms: ['gimnasio', 'gym'], filters: ['["leisure"="fitness_centre"]'] },
  { id: 'beauty', label: 'Salón de belleza / barbería', terms: ['salon de belleza', 'barberia', 'peluqueria', 'estetica'], filters: ['["shop"="beauty"]', '["shop"="hairdresser"]'] },
  { id: 'department_store', label: 'Tienda por departamentos', terms: ['tienda por departamentos', 'almacen', 'tienda de variedades', 'bazar'], filters: ['["shop"="department_store"]', '["shop"="variety_store"]'] },
  { id: 'wholesale', label: 'Mayorista / distribuidor', terms: ['mayorista', 'mayoreo', 'distribuidor', 'distribuidora', 'distribuidores', 'bodega'], filters: ['["shop"="wholesale"]', '["wholesale"]', '["office"="company"]["name"~"distribu",i]'], coverage: 'low' },
  { id: 'importer', label: 'Importador / exportador', terms: ['importador', 'importadora', 'importadores', 'exportador', 'exportadora', 'exportadores', 'importacion', 'exportacion'], filters: ['["office"="company"]["name"~"import|export",i]', '["office"]["name"~"import|export",i]', '["shop"]["name"~"import",i]'], coverage: 'low' },
  { id: 'manufacturer', label: 'Fabricante / industria', terms: ['fabrica', 'fabricante', 'fabricantes', 'industria', 'manufactura', 'planta'], filters: ['["man_made"="works"]', '["industrial"]', '["craft"]'], coverage: 'low' },
  { id: 'company', label: 'Empresa / oficina', terms: ['empresa', 'empresas', 'oficina', 'compania', 'corporacion'], filters: ['["office"="company"]'], coverage: 'low' },
];

export function normalizeText(s) {
  return String(s ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9ñ\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function singular(word) {
  if (word.endsWith('es') && word.length > 4) return word.slice(0, -2);
  if (word.endsWith('s') && word.length > 3) return word.slice(0, -1);
  return word;
}

function matchIndex(haystack, term) {
  const m = new RegExp(`(^|\\s)${term}(\\s|$)`).exec(haystack);
  return m ? m.index : -1;
}

/**
 * Resuelve una categoría a partir de texto libre. En español el núcleo del sintagma
 * va primero ("importadores de productos electrónicos" → importador), así que gana la
 * coincidencia más temprana; a igual posición, el término más largo.
 * Si no hay coincidencia, devuelve una búsqueda por nombre.
 */
export function resolveCategory(text) {
  const q = normalizeText(text);
  const qSingular = q.split(' ').map(singular).join(' ');
  let best = null;
  for (const cat of CATEGORIES) {
    for (const term of cat.terms) {
      const t = normalizeText(term);
      const positions = [matchIndex(q, t), matchIndex(qSingular, t)].filter((i) => i >= 0);
      if (!positions.length) continue;
      const pos = Math.min(...positions);
      if (!best || pos < best.pos || (pos === best.pos && t.length > best.matched.length)) {
        best = { cat, matched: t, pos };
      }
    }
  }
  if (best) return { ...best.cat, kind: 'category' };
  const safe = q.replace(/[^a-z0-9ñ ]/g, '').slice(0, 60);
  if (!safe) return null;
  return {
    id: 'name',
    label: `Nombre contiene "${safe}"`,
    kind: 'name',
    coverage: 'variable',
    filters: ['shop', 'amenity', 'office', 'craft', 'tourism', 'industrial'].map((k) => `["${k}"]["name"~"${safe}",i]`),
  };
}

/** Evalúa todas las condiciones de un filtro Overpass (["k"], ["k"="v"], ["k"~"re",i]) sobre unas etiquetas. */
export function filterMatches(filter, tags) {
  const conds = [...filter.matchAll(/\["([^"]+)"(?:(=|~)"([^"]*)"(,i)?)?\]/g)];
  if (!conds.length) return false;
  return conds.every(([, key, op, value, flag]) => {
    const v = tags[key];
    if (v === undefined) return false;
    if (!op) return true;
    if (op === '=') return v === value;
    return new RegExp(value, flag ? 'i' : '').test(v);
  });
}

/** Etiqueta en español a partir de las etiquetas OSM de un elemento. */
export function labelFromTags(tags) {
  for (const cat of CATEGORIES) {
    if (cat.filters.some((f) => filterMatches(f, tags))) return cat.label;
  }
  const raw = tags.shop ?? tags.amenity ?? tags.office ?? tags.tourism ?? tags.craft ?? tags.industrial;
  return raw ? String(raw).replace(/_/g, ' ') : null;
}
