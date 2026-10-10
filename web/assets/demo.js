// Dane demonstracyjne – ten sam interfejs co api.js. Pozwalają obejrzeć stronę bez serwera (adres ?demo=1 albo brak konfiguracji).
import { encodeGeom, encodeRouteGeom, decodeGeom, haversine, PROFILE_N } from './lib.js';

function rng(seed) { // mulberry32
  let a = seed >>> 0;
  return () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

/** gładka, „jak z GPS” trasa: błądzenie kierunku z łagodnymi zakrętami */
function wander(r, lat0, lon0, meters, step, turn = 0.22, heading = r() * Math.PI * 2) {
  const pts = [[lat0, lon0]];
  let h = heading, d = 0;
  const kx = 111320 * Math.cos((lat0 * Math.PI) / 180), ky = 110540;
  while (d < meters) {
    h += (r() - 0.5) * turn;
    const l = pts[pts.length - 1];
    pts.push([l[0] + (Math.cos(h) * step) / ky, l[1] + (Math.sin(h) * step) / kx]);
    d += step;
  }
  return pts;
}

const ATHLETES = ['Ola K.', 'Marek W.', 'Kasia P.', 'Tomek R.', 'Ania S.', 'Bartek L.', 'Iga M.', 'Paweł D.', 'Zosia T.', 'Kuba N.'];
const NOW = Date.now();

function profileFor(r, length, timeSec) {
  // niemalejący profil z wahaniami tempa (podjazdy/zjazdy), ostatnia wartość = czas
  const w = Array.from({ length: PROFILE_N }, (_, i) => 1 + 0.35 * Math.sin(i / 7 + r() * 2) + (r() - 0.5) * 0.2);
  const sum = w.reduce((s, x) => s + x, 0);
  const prof = [0];
  for (let i = 0; i < PROFILE_N; i++) prof.push(prof[i] + (w[i] / sum) * timeSec);
  prof[PROFILE_N] = timeSec;
  return prof.map((x) => x.toFixed(1)).join(',');
}

function build() {
  const r = rng(20261010);
  const defs = [
    { name: 'Podjazd na Kopiec Kościuszki', sport: 0, lat: 50.0547, lon: 19.8932, len: 1850, speed: 5.2 },
    { name: 'Błonia – sprint', sport: 0, lat: 50.0612, lon: 19.9179, len: 2400, speed: 9.4 },
    { name: 'Wisła: Dębniki – Bielany', sport: 1, lat: 50.0432, lon: 19.9087, len: 3200, speed: 3.6 },
    { name: 'Las Wolski – pętla biegowa', sport: 1, lat: 50.0525, lon: 19.8602, len: 2800, speed: 3.3 },
    { name: 'Tyniec – droga nad Wisłą', sport: 8, lat: 50.0205, lon: 19.8205, len: 4200, speed: 6.1 },
    { name: 'Planty – spacer dookoła', sport: 6, lat: 50.0614, lon: 19.9372, len: 4000, speed: 1.55 },
    { name: 'Zalew Bagry – kajaki', sport: 7, lat: 50.0108, lon: 19.9752, len: 1900, speed: 2.1 },
  ];
  const segments = [], efforts = [];
  defs.forEach((d, si) => {
    const pts = wander(r, d.lat, d.lon, d.len, 9, d.sport === 7 ? 0.1 : 0.26);
    const geom = encodeGeom(pts);
    const g = decodeGeom(geom);
    const uid = `demo-segment-${si + 1}`;
    segments.push({ uid, name: d.name, sport: d.sport, length_m: g.length, geom, author: ATHLETES[si % ATHLETES.length], created_at: new Date(NOW - (si + 3) * 86400e3 * 2).toISOString(), is_public: true });
    const n = 5 + Math.floor(r() * 5);
    for (let k = 0; k < n; k++) {
      const athlete = ATHLETES[(si + k * 3) % ATHLETES.length];
      const speed = d.speed * (0.8 + r() * 0.5);
      const timeSec = g.length / speed;
      const startedAt = NOW - Math.floor(r() * 25 * 86400e3) - k * 3600e3;
      efforts.push({
        id: si * 100 + k + 1, segment_uid: uid, owner: athlete === 'Ola K.' ? 'demo-user' : `u-${athlete}`, athlete,
        started_at: startedAt, time_sec: Math.round(timeSec * 10) / 10, profile: profileFor(r, g.length, Math.round(timeSec * 10) / 10),
        created_at: new Date(startedAt + timeSec * 1000).toISOString(),
      });
    }
  });

  const terrainSets = ['ASPHALT=11200;GRAVEL=1800;DIRT=900', 'ASPHALT=4200;SINGLETRACK=3100;DIRT=2200;MUD=400', 'GRAVEL=6400;DIRT=2100;SAND=300', 'ASPHALT=14000;COBBLES=800'];
  const rides = [];
  for (let i = 0; i < 9; i++) {
    const sp = [0, 0, 1, 8, 6, 0, 1, 7, 0][i];
    const dist = [32000, 54000, 10500, 21000, 5600, 41000, 7800, 8200, 66000][i];
    const pts = wander(r, 50.04 + r() * 0.05, 19.85 + r() * 0.12, dist * 0.55, 20, 0.2);
    const startedAt = NOW - (i * 1.7 + 0.3) * 86400e3;
    rides.push({
      owner: i % 3 === 0 ? 'demo-user' : `u-${i}`, started_at: Math.floor(startedAt), sport: sp, name: ['Poranny przejazd', 'Długa pętla', 'Bieg interwałowy', 'Przejazd na rolkach', 'Spacer z psem', 'Wieczorny przejazd', 'Bieg nad Wisłą', 'Spływ kajakowy', 'Maraton rowerowy'][i],
      athlete: ATHLETES[i % ATHLETES.length], distance_m: dist, moving_s: dist / (sp === 0 ? 6.4 : sp === 1 ? 3.1 : 4), ascent_m: 80 + Math.round(r() * 500), kcal: Math.round(dist / 1000 * 32),
      avg_hr: 120 + Math.round(r() * 40), tss: Math.round(40 + r() * 120), terrain_enc: sp === 7 ? '' : terrainSets[i % terrainSets.length], polyline: encodeGeom(pts), visibility: i % 3 === 0 ? 'private' : 'public',
      created_at: new Date(startedAt).toISOString(),
    });
  }
  const routes = [
    { id: 'demo-r1', owner: 'demo-user', name: 'Pętla wokół Tyńca', sport: 0, distance_m: 28400, ascent_m: 310, geom: encodeRouteGeom(wander(r, 50.02, 19.82, 14000, 30, 0.15).map((p, i) => [p[0], p[1], 210 + 40 * Math.sin(i / 25)])), is_public: false, source: 'web', author: 'Demo', created_at: new Date(NOW - 5 * 86400e3).toISOString() },
  ];
  return { segments, efforts, rides, routes };
}

export function createDemoApi() {
  const db = build();
  let user = null;
  const listeners = [];
  const emit = () => listeners.forEach((f) => f(user));
  const wait = (v) => new Promise((res) => setTimeout(() => res(v), 120));
  const saved = () => { try { return JSON.parse(localStorage.getItem('tracko.demo.routes') || '[]'); } catch { return []; } };

  return {
    demo: true,
    async getUser() { return user; },
    onAuth(cb) { listeners.push(cb); },
    async signIn(email) { user = { id: 'demo-user', email: email || 'demo@tracko.app' }; emit(); return wait(user); },
    async signUp(email) { return this.signIn(email); },
    async signOut() { user = null; emit(); return wait(); },
    async counts() { return wait({ segments: db.segments.length, efforts: db.efforts.length, rides: db.rides.filter((x) => x.visibility === 'public').length }); },
    async latestEfforts(limit = 12) {
      const rows = [...db.efforts].sort((a, b) => b.created_at.localeCompare(a.created_at)).slice(0, limit)
        .map((e) => ({ ...e, segment: db.segments.find((s) => s.uid === e.segment_uid) }));
      return wait(rows);
    },
    async segmentList({ q = '', sport = null, limit = 24 } = {}) {
      let rows = db.segments.filter((s) => (!q || s.name.toLowerCase().includes(q.toLowerCase())) && (sport == null || s.sport === sport))
        .map((s) => {
          const ef = db.efforts.filter((e) => e.segment_uid === s.uid);
          return { ...s, efforts: ef.length, best_sec: ef.length ? Math.min(...ef.map((e) => e.time_sec)) : null };
        });
      rows.sort((a, b) => b.efforts - a.efforts);
      return wait(rows.slice(0, limit));
    },
    async getSegment(uid) { return wait(db.segments.find((s) => s.uid === uid) || null); },
    async getEfforts(uid) { return wait(db.efforts.filter((e) => e.segment_uid === uid).sort((a, b) => a.time_sec - b.time_sec)); },
    async publicRides(limit = 30) { return wait(db.rides.filter((x) => x.visibility === 'public').slice(0, limit)); },
    async myRides() { return wait(user ? db.rides.filter((x) => x.owner === 'demo-user') : []); },
    async getRide(owner, startedAt) { return wait(db.rides.find((x) => x.owner === owner && String(x.started_at) === String(startedAt)) || null); },
    async myBests() {
      if (!user) return wait([]);
      const mine = db.efforts.filter((e) => e.owner === 'demo-user');
      return wait(mine.map((e) => ({ ...e, segment: db.segments.find((s) => s.uid === e.segment_uid) })));
    },
    async myRoutes() { return wait(user ? [...saved(), ...db.routes] : []); },
    async saveRoute(route) {
      const r = { id: 'demo-' + Date.now(), owner: 'demo-user', source: 'web', created_at: new Date().toISOString(), author: 'Demo', ...route };
      localStorage.setItem('tracko.demo.routes', JSON.stringify([r, ...saved()]));
      return wait(r);
    },
    async deleteRoute(id) { localStorage.setItem('tracko.demo.routes', JSON.stringify(saved().filter((x) => x.id !== id))); return wait(); },
  };
}
