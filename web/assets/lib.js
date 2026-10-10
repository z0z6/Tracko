// Tracko – czysta logika strony (bez DOM): geometria, profile „duchów”, formatowanie, GPX, plik odcinka.
// Te same wzory i formaty co w aplikacji (Ghost.kt, Models.kt) – patrz docs/CLOUD_API.md.

// ------------------------------------------------------------------ aktywności i nawierzchnie

export const SPORTS = {
  0: { id: 0, label: 'Rower', color: '#0A84FF', pace: 0, speed: 24, gps: true },
  1: { id: 1, label: 'Bieganie', color: '#FF9F0A', pace: 1, speed: 10, gps: true },
  2: { id: 2, label: 'Pływanie', color: '#32ADE6', pace: 2, speed: 3, gps: false },
  3: { id: 3, label: 'Siłownia', color: '#FF375F', pace: 0, speed: 0, gps: false },
  4: { id: 4, label: 'Bieżnia', color: '#30D158', pace: 1, speed: 10, gps: false },
  5: { id: 5, label: 'Narty biegowe', color: '#5E5CE6', pace: 0, speed: 12, gps: true },
  6: { id: 6, label: 'Spacer', color: '#00C7BE', pace: 1, speed: 5, gps: true },
  7: { id: 7, label: 'Kajakarstwo', color: '#30B0C7', pace: 0, speed: 6, gps: true },
  8: { id: 8, label: 'Rolki', color: '#AF52DE', pace: 0, speed: 18, gps: true },
};
export const sport = (id) => SPORTS[id] || SPORTS[0];

// kolory i nazwy jak w aplikacji (Terrain)
export const TERRAINS = {
  ASPHALT: { label: 'Asfalt', color: '#607D8B' },
  COBBLES: { label: 'Kostka/bruk', color: '#9C27B0' },
  GRAVEL: { label: 'Szuter', color: '#FFA000' },
  DIRT: { label: 'Droga leśna/polna', color: '#8D6E63' },
  SINGLETRACK: { label: 'Singletrack', color: '#2E7D32' },
  MUD: { label: 'Błoto', color: '#3E2723' },
  SAND: { label: 'Piasek', color: '#FDD835' },
  SNOW: { label: 'Śnieg/lód', color: '#03A9F4' },
};

/** "ASPHALT=8200.5;GRAVEL=1100" → [{key,label,color,meters,share}] malejąco po dystansie */
export function parseTerrain(enc) {
  if (!enc) return [];
  const items = [];
  for (const part of String(enc).split(';')) {
    const [k, v] = part.split('=');
    const meters = parseFloat(v);
    if (!TERRAINS[k] || !isFinite(meters) || meters <= 0) continue;
    items.push({ key: k, ...TERRAINS[k], meters });
  }
  const total = items.reduce((s, i) => s + i.meters, 0);
  items.forEach((i) => (i.share = total ? i.meters / total : 0));
  return items.sort((a, b) => b.meters - a.meters);
}

// ikony aktywności: SVG 24×24 (obrys currentColor), rysowane tak jak w aplikacji
const I = {
  0: '<circle cx="5.5" cy="16" r="3.8"/><circle cx="18.5" cy="16" r="3.8"/><path d="M5.5 16 10 8.5h6l2.5 7.5M10 8.5l2.5 7.5h-7M16 8.5l-1-3M8.5 6.5h3"/>',
  1: '<circle cx="14.5" cy="4.5" r="2" fill="currentColor"/><path d="M13 8l-2.5 6M12.5 9 9 10.5 7 8.5M12.8 8.5l3.7 2.5 2-1.5M10.5 14 14 17l-2 4M10.5 14 7.5 17l-3-1"/>',
  2: '<circle cx="16" cy="6" r="1.9" fill="currentColor"/><path d="M5 12l6-3 3.5.5M2.5 15c2-2.2 4-2.2 6 0s4 2.2 6 0 4-2.2 7 0M2.5 19.5c2-2.2 4-2.2 6 0s4 2.2 6 0 4-2.2 7 0"/>',
  3: '<path d="M8 12h8M2.5 10v4M21.5 10v4"/><rect x="5" y="7.5" width="3" height="9" rx="1.2"/><rect x="16" y="7.5" width="3" height="9" rx="1.2"/>',
  4: '<circle cx="9" cy="5" r="1.8" fill="currentColor"/><path d="M9 8v5M9 13l2.5 3M9 13l-2.5 3.5M9 9l3 2M3 19h15M18 19l2-12M15 7h6"/>',
  5: '<circle cx="12" cy="4.5" r="1.8" fill="currentColor"/><path d="M12 7.5V14M12 14l-2.5 5M12 14l3 5M8 7l-3 12M16 7l3 12M2.5 20.5h19"/>',
  6: '<circle cx="11" cy="4.5" r="2" fill="currentColor"/><path d="M11 8v6M11 14l-2.5 6M11 14l3 6M11 9l-3 3.5M11 9l3.5 2.5M17.5 7l-1.5 13.5"/>',
  7: '<path d="M2 15h20c-3 4.5-17 4.5-20 0z"/><circle cx="12" cy="7" r="1.8" fill="currentColor"/><path d="M12 9v5M5 5l14 8M3.8 6.8l2.6-3.4M17.6 14.6l2.8-3.4"/>',
  8: '<path d="M5 4v9.5h13.5v-3L11 8.5V4z"/><path d="M4 16h15.5"/><circle cx="6" cy="19" r="1.6"/><circle cx="9.8" cy="19" r="1.6"/><circle cx="13.6" cy="19" r="1.6"/><circle cx="17.4" cy="19" r="1.6"/>',
};
export function sportIcon(id, size = 20) {
  return `<svg class="ico" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${I[id] || I[0]}</svg>`;
}
export const GHOST_ICON =
  '<svg class="ico" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 20V11c0-7.5 14-7.5 14 0v9l-3-2.5-4 2.5-4-2.5z"/><circle cx="9.5" cy="11" r="1.2" fill="currentColor"/><circle cx="14.5" cy="11" r="1.2" fill="currentColor"/></svg>';

// ------------------------------------------------------------------ formatowanie

const nf1 = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 1, minimumFractionDigits: 0 });
const nf0 = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 0 });

export function fmtDur(sec) {
  if (!isFinite(sec) || sec < 0) return '–';
  const t = Math.round(sec);
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}
export const fmtKm = (m) => `${nf1.format((m || 0) / 1000)} km`;
export const fmtM = (m) => `${nf0.format(m || 0)} m`;
export const fmtNum = (n) => nf0.format(n || 0);
export function fmtDist(m) {
  return m >= 1000 ? fmtKm(m) : fmtM(m);
}
/** prędkość/tempo właściwe dla aktywności: km/h, min/km albo min/100 m */
export function fmtSpeed(sportId, ms) {
  const sp = sport(sportId);
  if (!isFinite(ms) || ms <= 0) return '–';
  if (sp.pace === 1) return `${fmtDur(1000 / ms)} /km`;
  if (sp.pace === 2) return `${fmtDur(100 / ms)} /100 m`;
  return `${nf1.format(ms * 3.6)} km/h`;
}
export function fmtDate(ms) {
  return new Date(Number(ms)).toLocaleDateString('pl-PL', { day: 'numeric', month: 'short', year: 'numeric' });
}
export function timeAgo(dateLike, now = Date.now()) {
  const t = typeof dateLike === 'number' ? dateLike : new Date(dateLike).getTime();
  const s = Math.max(0, Math.round((now - t) / 1000));
  if (s < 60) return 'przed chwilą';
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min temu`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} godz. temu`;
  const d = Math.round(h / 24);
  if (d < 30) return `${d} ${d === 1 ? 'dzień' : 'dni'} temu`;
  return fmtDate(t);
}
export function initials(name) {
  const parts = String(name || '?').trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] || '?') + (parts[1]?.[0] || '')).toUpperCase();
}
export function colorFromString(s) {
  let h = 0;
  for (const ch of String(s)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return `hsl(${h % 360} 70% 55%)`;
}
export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
export function estTimeSec(sportId, distM) {
  const kmh = sport(sportId).speed || 15;
  return (distM / 1000 / kmh) * 3600;
}

// ------------------------------------------------------------------ geometria

export function haversine(lat1, lon1, lat2, lon2) {
  const R = 6371000, d2r = Math.PI / 180;
  const dLat = (lat2 - lat1) * d2r, dLon = (lon2 - lon1) * d2r;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * d2r) * Math.cos(lat2 * d2r) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/** "lat,lon[,ele];…" → {lat[],lon[],ele[],n,cum[],length} albo null */
export function decodeGeom(str) {
  if (!str) return null;
  const parts = String(str).split(';').filter((p) => p.trim());
  if (parts.length < 2) return null;
  const lat = [], lon = [], ele = [];
  for (const p of parts) {
    const v = p.split(',');
    const la = parseFloat(v[0]), lo = parseFloat(v[1]);
    if (!isFinite(la) || !isFinite(lo)) return null;
    lat.push(la);
    lon.push(lo);
    ele.push(v.length > 2 && isFinite(parseFloat(v[2])) ? parseFloat(v[2]) : 0);
  }
  const cum = [0];
  for (let i = 1; i < lat.length; i++) cum.push(cum[i - 1] + haversine(lat[i - 1], lon[i - 1], lat[i], lon[i]));
  return { lat, lon, ele, n: lat.length, cum, length: cum[cum.length - 1] };
}
export const encodeGeom = (pts) => pts.map((p) => `${p[0].toFixed(6)},${p[1].toFixed(6)}`).join(';');
export const encodeRouteGeom = (pts) => pts.map((p) => `${p[0].toFixed(6)},${p[1].toFixed(6)},${(p[2] || 0).toFixed(1)}`).join(';');
export const latlngs = (g) => g.lat.map((la, i) => [la, g.lon[i]]);

export function cumulative(pts) {
  const cum = [0];
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + haversine(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]));
  return cum;
}

/** przerzedzanie: punkty co ≥ spacing m, maks. maxPts (zwiększa odstęp, aż się zmieści) */
export function thin(pts, spacing = 10, maxPts = 3000) {
  if (pts.length <= 2) return pts.slice();
  let sp = spacing;
  for (;;) {
    const out = [pts[0]];
    for (let i = 1; i < pts.length - 1; i++) {
      const l = out[out.length - 1];
      if (haversine(l[0], l[1], pts[i][0], pts[i][1]) >= sp) out.push(pts[i]);
    }
    out.push(pts[pts.length - 1]);
    if (out.length <= maxPts) return out;
    sp *= 1.5;
  }
}

/** punkt na łamanej po przejechaniu s metrów */
export function geoAt(g, s) {
  const x = Math.min(Math.max(s, 0), g.length);
  let i = 0;
  while (i < g.n - 2 && g.cum[i + 1] < x) i++;
  const seg = g.cum[i + 1] - g.cum[i];
  const f = seg <= 0 ? 0 : Math.min(Math.max((x - g.cum[i]) / seg, 0), 1);
  return [g.lat[i] + f * (g.lat[i + 1] - g.lat[i]), g.lon[i] + f * (g.lon[i + 1] - g.lon[i])];
}

export function bounds(pts) {
  let a = 90, b = -90, c = 180, d = -180;
  for (const [la, lo] of pts) { a = Math.min(a, la); b = Math.max(b, la); c = Math.min(c, lo); d = Math.max(d, lo); }
  return [[a, c], [b, d]];
}

/** ścieżka SVG dopasowana do ramki (podgląd trasy bez mapy); zachowuje proporcje */
export function svgPath(pts, w, h, pad = 8) {
  if (!pts || pts.length < 2) return '';
  const [[minLa, minLo], [maxLa, maxLo]] = bounds(pts);
  const k = Math.cos(((minLa + maxLa) / 2) * Math.PI / 180);
  const spanX = Math.max((maxLo - minLo) * k, 1e-9), spanY = Math.max(maxLa - minLa, 1e-9);
  const sc = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
  const ox = (w - spanX * sc) / 2, oy = (h - spanY * sc) / 2;
  return pts.map(([la, lo], i) => {
    const x = ox + (lo - minLo) * k * sc, y = h - (oy + (la - minLa) * sc);
    return `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`;
  }).join('');
}

/** suma podjazdów/zjazdów z histerezą (domyślnie 3 m), jak w statystykach aplikacji */
export function ascentDescent(ele, hyst = 3) {
  if (!ele.length) return { up: 0, down: 0 };
  let anchor = ele[0], up = 0, down = 0;
  for (const e of ele) {
    if (e - anchor >= hyst) { up += e - anchor; anchor = e; }
    else if (anchor - e >= hyst) { down += anchor - e; anchor = e; }
  }
  return { up, down };
}

// ------------------------------------------------------------------ duchy (profil czasowy odcinka)

export const PROFILE_N = 100;

export function decodeProfile(str) {
  const parts = String(str || '').split(',');
  if (parts.length !== PROFILE_N + 1) return null;
  const out = new Float64Array(parts.length);
  for (let i = 0; i < parts.length; i++) {
    out[i] = parseFloat(parts[i]);
    if (!isFinite(out[i])) return null;
  }
  return out;
}
/** czas ducha po przejechaniu s metrów odcinka o długości length */
export function ghostTimeAt(p, length, s) {
  const x = Math.min(Math.max((s / length) * PROFILE_N, 0), PROFILE_N);
  const i = Math.floor(x), i1 = Math.min(i + 1, PROFILE_N);
  return p[i] + (x - i) * (p[i1] - p[i]);
}
/** dystans przebyty przez ducha po czasie t */
export function ghostDistAt(p, length, t) {
  if (t <= p[0]) return 0;
  if (t >= p[PROFILE_N]) return length;
  let k = 0;
  while (k < PROFILE_N - 1 && p[k + 1] <= t) k++;
  const d = p[k + 1] - p[k];
  const f = d <= 0 ? 0 : (t - p[k]) / d;
  return ((k + f) * length) / PROFILE_N;
}

// ------------------------------------------------------------------ eksport

/** plik odcinka .ttseg – import w aplikacji: Odcinki i duchy → Importuj */
export function buildTtseg(seg, efforts) {
  return JSON.stringify({
    format: 'trailtrack-segment',
    v: 1,
    uid: seg.uid,
    name: seg.name,
    sport: seg.sport,
    author: seg.author || '',
    geom: seg.geom,
    efforts: efforts.slice(0, 30).map((e) => ({
      athlete: e.athlete, startedAt: Number(e.started_at), timeSec: e.time_sec, profile: e.profile,
    })),
  });
}

export function buildGpx(name, pts, withEle = true) {
  const esc = escapeHtml;
  const trkpts = pts.map((p) =>
    `<trkpt lat="${p[0].toFixed(6)}" lon="${p[1].toFixed(6)}">${withEle && isFinite(p[2]) ? `<ele>${p[2].toFixed(1)}</ele>` : ''}</trkpt>`).join('');
  return `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="Tracko" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${esc(name)}</name><trkseg>${trkpts}</trkseg></trk></gpx>`;
}

export function download(filename, text, mime = 'application/octet-stream') {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ------------------------------------------------------------------ planer: wysokości i profil

/** indeksy punktów, dla których pobierzemy wysokość: równomiernie co ≥ minSpacing m, maks. maxPoints (zawsze pierwszy i ostatni) */
export function pickSamples(cum, maxPoints = 380, minSpacing = 40) {
  const n = cum.length;
  if (n <= 2) return [...Array(n).keys()];
  const total = cum[n - 1];
  const step = Math.max(minSpacing, total / (maxPoints - 1));
  const idx = [0];
  let next = step;
  for (let i = 1; i < n - 1; i++) {
    if (cum[i] >= next) { idx.push(i); next = cum[i] + step; }
  }
  idx.push(n - 1);
  return idx;
}

/** wysokości w próbkach → wysokości we wszystkich punktach (interpolacja liniowa po dystansie) */
export function interpolateEle(cum, idxs, eles) {
  const out = new Array(cum.length);
  let j = 0;
  for (let i = 0; i < cum.length; i++) {
    while (j < idxs.length - 2 && cum[idxs[j + 1]] < cum[i]) j++;
    const a = idxs[j], b = idxs[Math.min(j + 1, idxs.length - 1)];
    const span = cum[b] - cum[a];
    const f = span <= 0 ? 0 : Math.min(Math.max((cum[i] - cum[a]) / span, 0), 1);
    out[i] = eles[j] + f * ((eles[Math.min(j + 1, eles.length - 1)]) - eles[j]);
  }
  return out;
}

/** indeks pierwszego punktu o dystansie ≥ d (wyszukiwanie binarne) */
export function idxAtDist(cum, d) {
  let lo = 0, hi = cum.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (cum[mid] < d) lo = mid + 1; else hi = mid;
  }
  return lo;
}

/** ścieżki SVG profilu wysokości (linia i obszar) w ramce w×h; x = dystans, y = wysokość */
export function chartPath(cum, ele, w, h, pad = { l: 6, r: 6, t: 10, b: 20 }) {
  const n = cum.length;
  if (n < 2) return null;
  let mn = Infinity, mx = -Infinity;
  for (const e of ele) { mn = Math.min(mn, e); mx = Math.max(mx, e); }
  if (mx - mn < 10) { const mid = (mx + mn) / 2; mn = mid - 5; mx = mid + 5; }
  const total = cum[n - 1] || 1;
  const X = (d) => pad.l + (d / total) * (w - pad.l - pad.r);
  const Y = (e) => h - pad.b - ((e - mn) / (mx - mn)) * (h - pad.t - pad.b);
  const step = Math.max(1, Math.floor(n / 400));
  let line = '';
  for (let i = 0; i < n; i += step) line += `${line ? 'L' : 'M'}${X(cum[i]).toFixed(1)} ${Y(ele[i]).toFixed(1)}`;
  line += `L${X(cum[n - 1]).toFixed(1)} ${Y(ele[n - 1]).toFixed(1)}`;
  const area = `${line}L${X(total).toFixed(1)} ${h - pad.b}L${X(0).toFixed(1)} ${h - pad.b}Z`;
  return { line, area, min: mn, max: mx, X, Y, total };
}
