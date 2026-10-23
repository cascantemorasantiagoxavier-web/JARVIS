// Analiza una respuesta de /api/business/search: estado de cada dato, fuentes y anomalías.
// Uso: node analyze.mjs < respuesta.json   (código 1 si hay anomalías que violan las reglas)
let raw = '';
process.stdin.on('data', (d) => (raw += d)).on('end', () => {
  let r;
  try { r = JSON.parse(raw); } catch { console.log('La respuesta no es JSON.'); process.exit(2); }
  if (r.error) { console.log(`Error del servidor: ${r.error.code} — ${r.error.message}`); process.exit(1); }
  const results = r.results ?? [];
  const fields = ['phone', 'website', 'email', 'address', 'openingHours', 'coordinates'];
  const counts = Object.fromEntries(fields.map((f) => [f, {}]));
  const problems = [];
  const notes = []; // avisos que no violan reglas (p. ej. cadenas con varias sucursales)
  const names = new Map();
  for (const b of results) {
    for (const f of fields) {
      const st = b[f]?.status ?? 'ausente';
      counts[f][st] = (counts[f][st] ?? 0) + 1;
      const v = b[f];
      if (v && v.value != null && (!Array.isArray(v.sources) || v.sources.length === 0) && f !== 'coordinates') {
        problems.push(`${b.name?.value ?? b.id}: ${f} tiene valor pero ninguna fuente`);
      }
      if (v && v.value == null && ['found', 'verified'].includes(v.status)) problems.push(`${b.name?.value ?? b.id}: ${f} dice "${v.status}" sin valor`);
    }
    const phone = b.phone?.value;
    if (phone && /^\+506/.test(phone) && !/^\+506 \d{4}-\d{4}$/.test(phone)) problems.push(`${b.name?.value ?? b.id}: teléfono +506 mal formateado (${phone})`);
    const key = (b.name?.value ?? '').toLowerCase().trim();
    if (key) names.set(key, (names.get(key) ?? 0) + 1);
    const srcs = Object.values(b.sources ?? {});
    if (srcs.some((s) => /openstreetmap/i.test(s.name ?? '') && !/ODbL/.test(s.license ?? ''))) problems.push(`${b.name?.value ?? b.id}: fuente OSM sin atribución ODbL`);
  }
  for (const [n, c] of names) if (c > 1) notes.push(`"${n}" aparece ${c} veces (¿sucursales o duplicado sin fusionar?)`);

  console.log(`Categoría: ${r.category?.label ?? r.category ?? '?'} · zona: ${r.area?.name ?? r.location ?? '?'} · total: ${r.total ?? results.length} · mostrados: ${results.length}`);
  if (r.source) console.log(`Fuente: ${r.source.name ?? ''} ${r.source.license ? `(${r.source.license})` : ''}`);
  (r.warnings ?? []).forEach((w) => console.log(`Aviso del servidor: ${w}`));
  console.log('\nEstado por dato:');
  for (const f of fields) console.log(`  ${f.padEnd(13)} ${Object.entries(counts[f]).map(([k, v]) => `${k}=${v}`).join('  ') || '-'}`);
  console.log('\nMuestra:');
  for (const b of results.slice(0, 8)) {
    console.log(`  ${(b.name?.value ?? b.id).slice(0, 34).padEnd(34)} tel=${b.phone?.value ?? 'no disponible'} [${b.phone?.status}]  web=${b.website?.status}  coord=${b.coordinates?.status}`);
  }
  if (notes.length) console.log('\nRevisar:\n  ' + notes.join('\n  '));
  console.log(problems.length ? `\nAnomalías (${problems.length}):\n  ` + problems.join('\n  ') : '\nSin anomalías: cada dato tiene estado y fuente.');
  process.exit(problems.length ? 1 : 0);
});
