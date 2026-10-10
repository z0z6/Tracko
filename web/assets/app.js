// Tracko – strona www: wyniki z aplikacji, odcinki i wyścigi duchów, aktywności, planer tras.
import * as U from './lib.js';
import { runSplash } from './splash.js';
import { createApi } from './api.js';
import { createDemoApi } from './demo.js';

const cfg = window.TRACKO_CONFIG || {};
const params = new URLSearchParams(location.search);
const DEMO = params.has('demo') || !cfg.url || !cfg.key;
const api = DEMO ? createDemoApi() : createApi(cfg);
const state = { user: null };

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = U.escapeHtml;
const view = document.getElementById('view');
const PALETTE = ['#0A84FF', '#FF9F0A', '#30D158', '#FF375F', '#5E5CE6', '#32ADE6', '#AF52DE', '#FFD60A'];
const SUN = '<svg class="ico" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>';
const MOON = '<svg class="ico" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>';

// ------------------------------------------------------------------ narzędzia UI

function toast(msg, ms = 2600) {
  const wrap = document.getElementById('toasts');
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = msg;
  wrap.appendChild(el);
  setTimeout(() => el.remove(), ms);
}
const sk = (h = 80, w = '100%') => `<div class="sk" style="height:${h}px;width:${w}"></div>`;
const theme = () => (document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
const sportChip = (id) => { const s = U.sport(id); return `<span class="sport-dot" style="--c:${s.color}">${U.sportIcon(id, 16)}${esc(s.label)}</span>`; };
const avatar = (name, cls = '') => `<span class="avatar ${cls}" style="background:${U.colorFromString(name)}">${esc(U.initials(name))}</span>`;

function previewSvg(geomStr) {
  const g = U.decodeGeom(geomStr);
  if (!g) return '';
  const pts = U.thin(U.latlngs(g), 8, 160);
  const d = U.svgPath(pts, 260, 130, 16);
  return `<svg viewBox="0 0 260 130" preserveAspectRatio="xMidYMid meet" aria-hidden="true"><path class="casing" d="${d}"/><path class="line" d="${d}"/></svg>`;
}
function terrainBar(enc) {
  const t = U.parseTerrain(enc);
  if (!t.length) return '';
  return `<div class="terrain-bar" title="Nawierzchnie">${t.map((i) => `<i style="width:${(i.share * 100).toFixed(1)}%;background:${i.color}"></i>`).join('')}</div>`;
}
function countUp(el, to) {
  const t0 = performance.now(), dur = 900;
  const f = (now) => {
    const p = Math.min((now - t0) / dur, 1);
    el.textContent = U.fmtNum(Math.round(to * (1 - Math.pow(1 - p, 3))));
    if (p < 1) requestAnimationFrame(f);
  };
  requestAnimationFrame(f);
}
function errorView(root, err, retry) {
  root.innerHTML = `<div class="card empty view-enter"><div class="big">⚠️</div><p>${esc(err?.message || 'Coś poszło nie tak')}</p><p style="margin-top:14px"><button class="btn" id="retry">Spróbuj ponownie</button></p></div>`;
  $('#retry', root).onclick = retry;
}
const notFound = (root, what = 'Nie znaleziono') => { root.innerHTML = `<div class="card empty view-enter"><div class="big">🧭</div><p>${esc(what)}</p><p style="margin-top:14px"><a class="btn" href="#/">Wróć na start</a></p></div>`; };
function tile(k, v, extra = '', cls = '') { return `<div class="tile ${cls}"><div class="k">${esc(k)}</div><div class="v">${v}${extra}</div></div>`; }

// ------------------------------------------------------------------ mapy (Leaflet + kafelki CARTO zależne od motywu)

const TILES = {
  light: 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',
  dark: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
};
const mapsOpen = new Set();
function makeMap(el, opts = {}) {
  const map = L.map(el, { zoomControl: true, scrollWheelZoom: opts.wheel !== false, zoomSnap: 0.25 });
  map._tl = L.tileLayer(TILES[theme()], { maxZoom: 19, subdomains: 'abcd', attribution: '© OpenStreetMap, © CARTO' }).addTo(map);
  map.setView(opts.center || [50.06, 19.94], opts.zoom || 11);
  mapsOpen.add(map);
  return map;
}
function disposeMaps() {
  for (const m of mapsOpen) { try { m.remove(); } catch (e) { /* mapa już usunięta */ } }
  mapsOpen.clear();
}
function addTrack(map, latlngs, color) {
  const casing = L.polyline(latlngs, { color: theme() === 'dark' ? '#000' : '#fff', weight: 9, opacity: 0.85, lineCap: 'round', lineJoin: 'round' }).addTo(map);
  const line = L.polyline(latlngs, { color, weight: 5, lineCap: 'round', lineJoin: 'round' }).addTo(map);
  return { casing, line, remove() { casing.remove(); line.remove(); } };
}
const fitTo = (map, latlngs, pad = 30) => map.fitBounds(U.bounds(latlngs), { padding: [pad, pad] });
const dot = (color) => L.divIcon({ className: '', html: `<div class="pin-dot" style="--c:${color}"></div>`, iconSize: [16, 16], iconAnchor: [8, 8] });

// ================================================================== WIDOK: WYNIKI (strona główna)

function effortItem(e) {
  const s = e.segment || {};
  const speed = s.length_m ? U.fmtSpeed(s.sport, s.length_m / e.time_sec) : '';
  return `<a class="feed-item" href="#/odcinek/${encodeURIComponent(e.segment_uid)}?e=${e.id}">
    ${avatar(e.athlete)}
    <div class="fi-main">${esc(e.athlete)} <span class="muted" style="font-weight:500">na</span> ${esc(s.name || 'odcinku')}</div>
    <div class="fi-sub">${sportChip(s.sport)}<span>${U.fmtDist(s.length_m || 0)}</span><span>${U.timeAgo(e.created_at || e.started_at)}</span></div>
    <div class="fi-time">${U.fmtDur(e.time_sec)}<small>${esc(speed)}</small></div>
  </a>`;
}
function segCard(s) {
  const sp = U.sport(s.sport);
  return `<a class="seg-card" href="#/odcinek/${encodeURIComponent(s.uid)}" style="--c:${sp.color}">
    <div class="preview">${previewSvg(s.geom)}<span class="corner">${sportChip(s.sport)}</span></div>
    <div class="card-body"><div class="card-title">${esc(s.name)}</div>
      <div class="stats-row"><span><b>${U.fmtDist(s.length_m)}</b></span><span><b>${s.efforts || 0}</b> wyn.</span>${s.best_sec ? `<span>rekord <b>${U.fmtDur(s.best_sec)}</b></span>` : ''}</div></div>
  </a>`;
}

async function viewHome(root) {
  const release = cfg.releasesUrl || '#';
  root.innerHTML = `<div class="view-enter">
    <section class="hero">
      <svg class="hero-art" viewBox="0 0 600 300" aria-hidden="true">
        <defs><linearGradient id="heroGrad" x1="0" x2="1"><stop offset="0" stop-color="#0A84FF"/><stop offset=".5" stop-color="#5E5CE6"/><stop offset="1" stop-color="#30D158"/></linearGradient></defs>
        <path class="ghost" transform="translate(-16 12)" pathLength="1000" d="M24 234C132 306 204 90 300 150C396 210 456 6 576 66"/>
        <path class="main" pathLength="1000" d="M24 234C132 306 204 90 300 150C396 210 456 6 576 66"/>
      </svg>
      <p class="eyebrow">TRASY · WYSIŁEK · DUCHY</p>
      <h1 class="h1">Ścigaj się<br>z własnymi śladami.</h1>
      <p class="lead">Wyniki i duchy z aplikacji Tracko: rankingi odcinków, wyścigi duchów, przejechane aktywności i planer tras – w jednym miejscu.</p>
      <div class="cta">
        <a class="btn primary" href="#/odcinki">Zobacz odcinki</a>
        <a class="btn" href="#/planer">Zaplanuj trasę</a>
        <a class="btn" href="${esc(release)}" rel="noopener">Pobierz aplikację</a>
      </div>
    </section>
    <section class="section"><div class="tiles stagger" id="tiles">${sk(92)}${sk(92)}${sk(92)}</div></section>
    <section class="section" id="mine" hidden></section>
    <section class="section"><div class="section-head"><h2 class="h2">Ostatnie wyniki</h2><a class="link" href="#/odcinki">Wszystkie odcinki →</a></div><div id="feed">${sk(320)}</div></section>
    <section class="section"><div class="section-head"><h2 class="h2">Najpopularniejsze odcinki</h2><a class="link" href="#/odcinki">Zobacz więcej →</a></div><div class="hscroll" id="pop">${sk(210, '260px')}${sk(210, '260px')}${sk(210, '260px')}</div></section>
  </div>`;
  try {
    const [c, latest, pop] = await Promise.all([api.counts(), api.latestEfforts(10), api.segmentList({ limit: 8 })]);
    $('#tiles', root).innerHTML =
      tile('Odcinki', '<span data-n="' + c.segments + '">0</span>') +
      tile('Wyniki i duchy', '<span data-n="' + c.efforts + '">0</span>') +
      tile('Publiczne aktywności', '<span data-n="' + c.rides + '">0</span>') +
      `<a class="tile accent" href="${esc(release)}" rel="noopener" style="display:block"><div class="k">Aplikacja Android</div><div class="v" style="font-size:22px">Pobierz Tracko →</div></a>`;
    $$('[data-n]', root).forEach((el) => countUp(el, Number(el.dataset.n)));
    $('#feed', root).innerHTML = latest.length
      ? `<div class="feed stagger">${latest.map(effortItem).join('')}</div>`
      : `<div class="card empty"><div class="big">🏁</div><p>Brak wyników. Utwórz odcinek w aplikacji i przejedź go – wynik pojawi się tutaj.</p></div>`;
    $('#pop', root).innerHTML = pop.length ? pop.map(segCard).join('') : '<div class="card empty" style="flex:1">Brak odcinków.</div>';
    if (state.user) {
      const mine = await api.myBests();
      if (mine.length) {
        const box = $('#mine', root);
        box.hidden = false;
        box.innerHTML = `<div class="section-head"><h2 class="h2">Twoje ostatnie wyniki</h2></div><div class="feed stagger">${mine.slice(0, 5).map(effortItem).join('')}</div>`;
      }
    }
  } catch (e) { errorView(root, e, () => viewHome(root)); }
}

// ================================================================== WIDOK: ODCINKI

async function viewSegments(root) {
  let q = '', sportF = null, limit = 24, timer = 0;
  root.innerHTML = `<div class="view-enter">
    <div class="section-head"><div><h1 class="h1">Odcinki i duchy</h1><p class="sub" style="margin-top:6px">Rankingi odcinków utworzonych w aplikacji. Wybierz odcinek, aby zobaczyć wyniki i uruchomić wyścig duchów.</p></div></div>
    <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-top:20px">
      <input class="input" id="q" type="search" placeholder="Szukaj odcinka…" style="max-width:340px" aria-label="Szukaj odcinka">
      <div class="chips" id="chips"></div>
    </div>
    <div class="grid section stagger" id="list">${sk(220)}${sk(220)}${sk(220)}</div>
    <p style="text-align:center;margin-top:22px"><button class="btn" id="more" hidden>Pokaż więcej</button></p>
  </div>`;
  const chips = $('#chips', root);
  chips.innerHTML = `<button class="chip on" data-s="">Wszystkie</button>` + [0, 1, 6, 8, 5, 7].map((id) => {
    const s = U.sport(id);
    return `<button class="chip" data-s="${id}" style="--c:${s.color}">${U.sportIcon(id, 16)}${esc(s.label)}</button>`;
  }).join('');
  async function load() {
    const list = $('#list', root);
    try {
      const rows = await api.segmentList({ q, sport: sportF, limit });
      list.innerHTML = rows.length ? rows.map(segCard).join('') : `<div class="card empty" style="grid-column:1/-1"><div class="big">🔍</div><p>Nic nie znaleziono.</p></div>`;
      $('#more', root).hidden = rows.length < limit;
    } catch (e) { list.innerHTML = `<div class="card empty" style="grid-column:1/-1"><p>${esc(e.message)}</p></div>`; }
  }
  $('#q', root).addEventListener('input', (ev) => { clearTimeout(timer); timer = setTimeout(() => { q = ev.target.value.trim(); limit = 24; load(); }, 300); });
  chips.addEventListener('click', (ev) => {
    const b = ev.target.closest('.chip'); if (!b) return;
    sportF = b.dataset.s === '' ? null : Number(b.dataset.s);
    $$('.chip', chips).forEach((c) => c.classList.toggle('on', c === b));
    b.style.setProperty('--c', sportF == null ? 'var(--blue)' : U.sport(sportF).color);
    limit = 24; load();
  });
  $('#more', root).onclick = () => { limit += 24; load(); };
  await load();
  return () => clearTimeout(timer);
}

// ================================================================== WIDOK: ODCINEK (ranking + wyścig duchów)

async function viewSegment(root, uid, query) {
  root.innerHTML = `<div class="view-enter">${sk(56, '60%')}<div style="height:16px"></div>${sk(420)}</div>`;
  let seg, efforts;
  try {
    [seg, efforts] = await Promise.all([api.getSegment(uid), api.getEfforts(uid)]);
  } catch (e) { return errorView(root, e, () => viewSegment(root, uid, query)); }
  const g = seg && U.decodeGeom(seg.geom);
  if (!seg || !g) return notFound(root, 'Nie znaleziono odcinka (może jest prywatny).');

  const sp = U.sport(seg.sport);
  const best = efforts[0];
  const myId = state.user?.id;
  const hlId = Number(query.get('e')) || 0;
  const efs = efforts.map((e) => ({ ...e, prof: U.decodeProfile(e.profile) })).filter((e) => e.prof);
  const colorOf = (e) => PALETTE[efs.indexOf(e) % PALETTE.length];

  root.innerHTML = `<div class="view-enter">
    <a class="link" href="#/odcinki">← Odcinki</a>
    <div class="section-head" style="margin-top:12px;align-items:flex-end">
      <div><h1 class="h1">${esc(seg.name)}</h1>
        <p class="sub" style="margin-top:8px;display:flex;gap:12px;flex-wrap:wrap;align-items:center">${sportChip(seg.sport)}<span>${U.fmtDist(seg.length_m)}</span>${seg.author ? `<span>autor: ${esc(seg.author)}</span>` : ''}</p></div>
      <button class="btn" id="dl" title="Zaimportuj w aplikacji: Odcinki i duchy → Importuj">⬇ Plik odcinka (.ttseg)</button>
    </div>
    <div class="tiles section stagger" style="margin-top:20px">
      ${tile('Rekord', best ? U.fmtDur(best.time_sec) : '–', '', 'accent')}
      ${tile('Najlepsze tempo', best ? esc(U.fmtSpeed(seg.sport, seg.length_m / best.time_sec)) : '–')}
      ${tile('Wyników', String(efforts.length))}
      ${tile('Długość', esc(U.fmtDist(seg.length_m)))}
    </div>
    <div class="split section">
      <div>
        <div class="map tall" id="map" style="height:min(52vh,460px)"></div>
        <div class="card" style="margin-top:16px">
          <div class="section-head" style="margin-bottom:6px"><h2 class="h2" style="font-size:21px">Wyścig duchów</h2><span class="clock" id="clock">0:00</span></div>
          <p class="sub">Zaznacz wyniki w rankingu (do ${PALETTE.length}), a zobaczysz wspólny start na mapie.</p>
          <div class="race-controls">
            <button class="btn primary" id="play">▶ Start</button>
            <button class="btn" id="restart" aria-label="Od początku">↺</button>
            <div class="seg" id="speed"></div>
            <input type="range" id="scrub" min="0" max="100" step="0.1" value="0" aria-label="Postęp wyścigu">
          </div>
          <div class="stand" id="stand"></div>
        </div>
      </div>
      <div class="card">
        <h2 class="h2" style="font-size:21px;margin-bottom:12px">Ranking</h2>
        ${efforts.length ? `<div style="overflow-x:auto"><table class="table"><thead><tr><th>#</th><th>Zawodnik</th><th>Czas</th><th>Strata</th><th>Duch</th></tr></thead><tbody id="rows"></tbody></table></div>`
          : '<div class="empty">Brak wyników na tym odcinku.</div>'}
      </div>
    </div>
  </div>`;

  // mapa
  const map = makeMap($('#map', root), { wheel: false });
  const ll = U.latlngs(g);
  addTrack(map, ll, sp.color);
  L.marker(ll[0], { icon: dot('#30D158') }).addTo(map);
  L.marker(ll[ll.length - 1], { icon: dot('#FF3B30') }).addTo(map);
  fitTo(map, ll);

  // ranking
  const sel = new Set();
  efs.slice(0, 3).forEach((e) => sel.add(e.id));
  const myBest = efs.find((e) => myId && e.owner === myId);
  if (myBest) sel.add(myBest.id);
  const hlEffort = efs.find((e) => e.id === hlId);
  if (hlEffort) sel.add(hlEffort.id);
  while (sel.size > PALETTE.length) sel.delete([...sel].pop());

  $('#rows', root) && ($('#rows', root).innerHTML = efforts.map((e, i) => {
    const ef = efs.find((x) => x.id === e.id);
    const mine = myId && e.owner === myId;
    return `<tr class="${e.id === hlId ? 'hl' : ''}">
      <td class="rank">${i + 1}</td>
      <td><span class="who">${avatar(e.athlete, 'sm')}<span>${esc(e.athlete)} ${mine ? '<span class="me">· Ty</span>' : ''}<br><small class="muted" style="font-weight:500">${esc(U.fmtDate(e.started_at))}</small></span></span></td>
      <td><b>${U.fmtDur(e.time_sec)}</b><br><small class="muted">${esc(U.fmtSpeed(seg.sport, seg.length_m / e.time_sec))}</small></td>
      <td class="muted">${i === 0 ? '–' : '+' + U.fmtDur(e.time_sec - best.time_sec)}</td>
      <td>${ef ? `<input class="ck" type="checkbox" data-id="${e.id}" style="--c:${colorOf(ef)}" ${sel.has(e.id) ? 'checked' : ''} aria-label="Duch: ${esc(e.athlete)}">` : ''}</td>
    </tr>`;
  }).join(''));

  // wyścig
  const markers = new Map();
  const speedOpts = [1, 2, 5, 10, 30];
  let t = 0, playing = false, raf = 0, last = 0, lastDom = 0, speed = 1;
  const maxT = () => { let m = 0; for (const e of efs) if (sel.has(e.id)) m = Math.max(m, e.time_sec); return m || 1; };
  const autoSpeed = () => speedOpts.find((s) => maxT() / s <= 25) || 30;
  const speedBox = $('#speed', root), playBtn = $('#play', root), scrub = $('#scrub', root), clock = $('#clock', root), stand = $('#stand', root);

  function drawSpeed() {
    speedBox.innerHTML = speedOpts.map((s) => `<button type="button" data-s="${s}" class="${s === speed ? 'on' : ''}">${s}×</button>`).join('');
  }
  function syncMarkers() {
    for (const [id, m] of markers) if (!sel.has(id)) { m.remove(); markers.delete(id); }
    for (const e of efs) {
      if (!sel.has(e.id) || markers.has(e.id)) continue;
      const icon = L.divIcon({ className: 'ghost-pin', html: `<span style="--c:${colorOf(e)}"><b>${esc(U.initials(e.athlete))}</b></span>`, iconSize: [34, 34], iconAnchor: [17, 34] });
      markers.set(e.id, L.marker(ll[0], { icon, zIndexOffset: 500 }).addTo(map));
    }
  }
  function update(force = false, now = 0) {
    const mt = maxT();
    scrub.max = mt.toFixed(1);
    scrub.value = String(Math.min(t, mt));
    clock.textContent = U.fmtDur(Math.min(t, mt));
    const rows = [];
    for (const e of efs) {
      if (!sel.has(e.id)) continue;
      const d = U.ghostDistAt(e.prof, g.length, t);
      const m = markers.get(e.id);
      if (m) m.setLatLng(U.geoAt(g, d));
      rows.push({ e, d, done: t >= e.time_sec });
    }
    if (!force && now - lastDom < 50) return;
    lastDom = now;
    rows.sort((a, b) => b.d - a.d || a.e.time_sec - b.e.time_sec);
    stand.innerHTML = rows.map((r, i) => `<div class="stand-row" style="--c:${colorOf(r.e)}"><b>${i + 1}</b><span class="who">${avatar(r.e.athlete, 'sm')} ${esc(r.e.athlete)}</span><span class="num">${r.done ? '🏁 ' + U.fmtDur(r.e.time_sec) : esc(U.fmtM(r.d))}</span><div class="bar"><i style="width:${Math.min(100, (100 * r.d) / g.length).toFixed(1)}%"></i></div></div>`).join('')
      || '<p class="sub">Zaznacz co najmniej jeden wynik w rankingu.</p>';
  }
  function setPlay() { playBtn.textContent = playing ? '❚❚ Pauza' : (t >= maxT() + 1 ? '↺ Jeszcze raz' : '▶ Start'); }
  function frame(now) {
    if (!playing) return;
    const dt = Math.min((now - last) / 1000, 0.1);
    last = now;
    t += dt * speed;
    const end = maxT() + 1;
    if (t >= end) { t = end; playing = false; setPlay(); }
    update(!playing, now);
    if (playing) raf = requestAnimationFrame(frame);
  }
  function play() {
    if (!sel.size) { toast('Zaznacz co najmniej jeden wynik'); return; }
    if (t >= maxT() + 1) t = 0;
    playing = !playing;
    setPlay();
    if (playing) { last = performance.now(); raf = requestAnimationFrame(frame); }
  }
  speed = autoSpeed();
  drawSpeed(); syncMarkers(); update(true); setPlay();
  playBtn.onclick = play;
  $('#restart', root).onclick = () => { t = 0; update(true); setPlay(); };
  speedBox.addEventListener('click', (ev) => { const b = ev.target.closest('button'); if (!b) return; speed = Number(b.dataset.s); drawSpeed(); });
  scrub.addEventListener('input', () => { t = Number(scrub.value); update(true); setPlay(); });
  root.addEventListener('change', (ev) => {
    const c = ev.target.closest('.ck'); if (!c) return;
    const id = Number(c.dataset.id);
    if (c.checked) {
      if (sel.size >= PALETTE.length) { c.checked = false; toast(`Maksymalnie ${PALETTE.length} duchów naraz`); return; }
      sel.add(id);
    } else sel.delete(id);
    syncMarkers(); update(true); setPlay();
  });
  $('#dl', root).onclick = () => {
    const slug = seg.name.toLowerCase().replace(/[^a-z0-9ąćęłńóśźż]+/gi, '_').slice(0, 40) || 'odcinek';
    U.download(`odcinek_${slug}.ttseg`, U.buildTtseg(seg, efforts), 'application/json');
    toast('Zapisano plik – zaimportuj go w aplikacji: Odcinki i duchy → Importuj');
  };
  return () => { playing = false; cancelAnimationFrame(raf); };
}

// ================================================================== WIDOKI: AKTYWNOŚCI

function rideCard(r) {
  const sp = U.sport(r.sport);
  const speed = r.moving_s > 0 ? U.fmtSpeed(r.sport, r.distance_m / r.moving_s) : '–';
  return `<a class="ride-card" href="#/aktywnosc/${encodeURIComponent(r.owner)}/${r.started_at}" style="--c:${sp.color}">
    <div class="preview">${previewSvg(r.polyline)}<span class="corner">${sportChip(r.sport)}</span></div>
    <div class="card-body">
      <div class="card-title">${esc(r.name || sp.label)}</div>
      <div class="muted" style="font-size:13px">${esc(r.athlete || '')}${r.athlete ? ' · ' : ''}${esc(U.fmtDate(r.started_at))}${r.visibility === 'private' ? ' · 🔒' : ''}</div>
      <div class="stats-row"><span><b>${U.fmtDist(r.distance_m)}</b></span><span><b>${U.fmtDur(r.moving_s)}</b></span><span><b>${esc(speed)}</b></span>${r.ascent_m ? `<span>↑<b>${U.fmtM(r.ascent_m)}</b></span>` : ''}</div>
      ${terrainBar(r.terrain_enc)}
    </div>
  </a>`;
}

async function viewRides(root) {
  let tab = 'pub', sportF = null, rows = [];
  root.innerHTML = `<div class="view-enter">
    <div class="section-head"><div><h1 class="h1">Aktywności</h1><p class="sub" style="margin-top:6px">Przejazdy wysłane z aplikacji (uproszczony ślad, początek i koniec przycięte dla prywatności).</p></div>
      <div class="seg" id="tabs"><button data-t="pub" class="on">Publiczne</button><button data-t="mine">Moje</button></div></div>
    <div class="chips" id="chips" style="margin-top:18px"></div>
    <div class="grid section stagger" id="list">${sk(260)}${sk(260)}${sk(260)}</div>
  </div>`;
  const chips = $('#chips', root), list = $('#list', root);
  chips.innerHTML = `<button class="chip on" data-s="">Wszystkie</button>` + [0, 1, 6, 8, 5, 7, 2, 3, 4].map((id) => {
    const s = U.sport(id);
    return `<button class="chip" data-s="${id}" style="--c:${s.color}">${U.sportIcon(id, 16)}${esc(s.label)}</button>`;
  }).join('');
  function paint() {
    const f = rows.filter((r) => sportF == null || r.sport === sportF);
    if (tab === 'mine' && !state.user) {
      list.innerHTML = `<div class="card empty" style="grid-column:1/-1"><div class="big">🔐</div><p>Zaloguj się, aby zobaczyć swoje aktywności wysłane z aplikacji.</p><p style="margin-top:14px"><button class="btn primary" id="go-login">Zaloguj się</button></p></div>`;
      $('#go-login', root).onclick = () => openAuth('in');
      return;
    }
    list.innerHTML = f.length ? f.map(rideCard).join('') : `<div class="card empty" style="grid-column:1/-1"><div class="big">🚴</div><p>${tab === 'mine' ? 'Nie wysłałeś jeszcze aktywności. Włącz „Przejechane aktywności” w aplikacji (Ustawienia → Rywalizacja online).' : 'Brak publicznych aktywności.'}</p></div>`;
  }
  async function load() {
    list.innerHTML = sk(260) + sk(260) + sk(260);
    try { rows = tab === 'pub' ? await api.publicRides(30) : (state.user ? await api.myRides() : []); paint(); }
    catch (e) { list.innerHTML = `<div class="card empty" style="grid-column:1/-1"><p>${esc(e.message)}</p></div>`; }
  }
  $('#tabs', root).addEventListener('click', (ev) => {
    const b = ev.target.closest('button'); if (!b) return;
    tab = b.dataset.t;
    $$('#tabs button', root).forEach((x) => x.classList.toggle('on', x === b));
    load();
  });
  chips.addEventListener('click', (ev) => {
    const b = ev.target.closest('.chip'); if (!b) return;
    sportF = b.dataset.s === '' ? null : Number(b.dataset.s);
    $$('.chip', chips).forEach((c) => c.classList.toggle('on', c === b));
    paint();
  });
  await load();
}

async function viewRide(root, owner, startedAt) {
  root.innerHTML = `<div class="view-enter">${sk(56, '50%')}<div style="height:16px"></div>${sk(420)}</div>`;
  let r;
  try { r = await api.getRide(owner, startedAt); } catch (e) { return errorView(root, e, () => viewRide(root, owner, startedAt)); }
  if (!r) return notFound(root, 'Nie znaleziono aktywności (może jest prywatna).');
  const sp = U.sport(r.sport);
  const g = U.decodeGeom(r.polyline);
  const terr = U.parseTerrain(r.terrain_enc);
  const speed = r.moving_s > 0 ? U.fmtSpeed(r.sport, r.distance_m / r.moving_s) : '–';
  root.innerHTML = `<div class="view-enter">
    <a class="link" href="#/aktywnosci">← Aktywności</a>
    <div class="section-head" style="margin-top:12px;align-items:flex-end">
      <div><h1 class="h1">${esc(r.name || sp.label)}</h1>
        <p class="sub" style="margin-top:8px;display:flex;gap:12px;flex-wrap:wrap;align-items:center">${sportChip(r.sport)}<span>${esc(U.fmtDate(r.started_at))}</span>${r.athlete ? `<span>${esc(r.athlete)}</span>` : ''}${r.visibility === 'private' ? '<span class="badge">🔒 prywatna</span>' : ''}</p></div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">${g ? '<button class="btn" id="plan">Zaplanuj podobną trasę</button><button class="btn" id="gpx">⬇ GPX</button>' : ''}</div>
    </div>
    <div class="tiles section stagger" style="margin-top:20px">
      ${tile('Dystans', esc(U.fmtDist(r.distance_m)), '', 'accent')}
      ${tile('Czas', U.fmtDur(r.moving_s))}
      ${tile(sp.pace === 0 ? 'Prędkość' : 'Tempo', esc(speed))}
      ${r.ascent_m ? tile('Podjazd', esc(U.fmtM(r.ascent_m))) : ''}
      ${r.kcal ? tile('Kalorie', U.fmtNum(r.kcal), '<small>kcal</small>') : ''}
      ${r.avg_hr ? tile('Śr. tętno', String(r.avg_hr), '<small>bpm</small>') : ''}
      ${r.tss ? tile('Obciążenie (TSS)', U.fmtNum(r.tss)) : ''}
    </div>
    <div class="section">${g ? '<div class="map tall" id="map"></div>' : '<div class="card empty"><div class="big">🗺️</div><p>Ślad niedostępny – jest za krótki po przycięciu początku i końca (prywatność) albo aktywność była bez GPS.</p></div>'}</div>
    ${terr.length ? `<div class="card section"><h2 class="h2" style="font-size:21px;margin-bottom:12px">Nawierzchnie</h2>${terrainBar(r.terrain_enc)}
      <div class="legend">${terr.map((t) => `<span style="--c:${t.color}">${esc(t.label)} · ${esc(U.fmtDist(t.meters))} (${Math.round(t.share * 100)}%)</span>`).join('')}</div></div>` : ''}
  </div>`;
  if (g) {
    const map = makeMap($('#map', root));
    const ll = U.latlngs(g);
    addTrack(map, ll, sp.color);
    L.marker(ll[0], { icon: dot('#30D158') }).addTo(map);
    L.marker(ll[ll.length - 1], { icon: dot('#FF3B30') }).addTo(map);
    fitTo(map, ll);
    $('#gpx', root).onclick = () => U.download('aktywnosc.gpx', U.buildGpx(r.name || sp.label, ll.map((p) => [p[0], p[1]]), false), 'application/gpx+xml');
    $('#plan', root).onclick = () => {
      const wp = U.thin(ll, Math.max(60, g.length / 25), 25);
      try { localStorage.setItem('tracko.planner', JSON.stringify({ wp, mode: 'line', loop: false, name: (r.name || sp.label) + ' – wariant', sport: r.sport })); } catch (e) { /* brak miejsca */ }
      location.hash = '#/planer';
    };
  }
}

// ================================================================== WIDOK: PLANER TRAS

const ROUTING = {
  bike: 'https://routing.openstreetmap.de/routed-bike/route/v1/driving/',
  foot: 'https://routing.openstreetmap.de/routed-foot/route/v1/driving/',
};
const eleCache = new Map();
const eleKey = (p) => `${p[0].toFixed(4)},${p[1].toFixed(4)}`;

async function routeVia(mode, pts, signal) {
  const coords = pts.map((p) => `${p[1].toFixed(6)},${p[0].toFixed(6)}`).join(';');
  const res = await fetch(`${ROUTING[mode]}${coords}?overview=full&geometries=geojson&steps=false`, { signal });
  if (!res.ok) throw new Error('routing ' + res.status);
  const j = await res.json();
  if (j.code !== 'Ok' || !j.routes || !j.routes.length) throw new Error('Brak trasy między punktami');
  return j.routes[0].geometry.coordinates.map(([lon, lat]) => [lat, lon]);
}

/** wysokości (Open-Meteo, do 100 punktów na zapytanie, z pamięcią podręczną) */
async function fetchElevations(samples, signal) {
  const missing = [...new Map(samples.filter((p) => !eleCache.has(eleKey(p))).map((p) => [eleKey(p), p])).values()];
  const chunks = [];
  for (let i = 0; i < missing.length; i += 100) chunks.push(missing.slice(i, i + 100));
  await Promise.all(chunks.map(async (chunk) => {
    const url = `https://api.open-meteo.com/v1/elevation?latitude=${chunk.map((p) => p[0].toFixed(4)).join(',')}&longitude=${chunk.map((p) => p[1].toFixed(4)).join(',')}`;
    const res = await fetch(url, { signal });
    if (!res.ok) throw new Error('elevation ' + res.status);
    const j = await res.json();
    chunk.forEach((p, k) => eleCache.set(eleKey(p), j.elevation[k]));
  }));
  return samples.map((p) => eleCache.get(eleKey(p)));
}

async function buildRoute(coords, signal) {
  const pts = U.thin(coords, 10, 3000);
  const cum = U.cumulative(pts);
  let ele, eleOk = true;
  try {
    const idx = U.pickSamples(cum, 380, 40);
    const e = await fetchElevations(idx.map((i) => pts[i]), signal);
    ele = U.interpolateEle(cum, idx, e);
  } catch (err) {
    if (err && err.name === 'AbortError') throw err;
    ele = pts.map(() => 0); eleOk = false;
  }
  const { up, down } = U.ascentDescent(ele);
  return { pts: pts.map((p, i) => [p[0], p[1], ele[i]]), cum, dist: cum[cum.length - 1], up, down, eleOk };
}
function routeFromGeom(geomStr) {
  const g = U.decodeGeom(geomStr);
  if (!g) return null;
  const pts = g.lat.map((la, i) => [la, g.lon[i], g.ele[i]]);
  const { up, down } = U.ascentDescent(g.ele);
  return { pts, cum: g.cum, dist: g.length, up, down, eleOk: g.ele.some((e) => e !== 0) };
}

async function viewPlanner(root) {
  let draft = {};
  try { draft = JSON.parse(localStorage.getItem('tracko.planner') || '{}') || {}; } catch (e) { draft = {}; }
  const valid = (p) => Array.isArray(p) && isFinite(p[0]) && isFinite(p[1]);
  let wp = Array.isArray(draft.wp) ? draft.wp.filter(valid).map((p) => [p[0], p[1]]) : [];
  let mode = ['bike', 'foot', 'line'].includes(draft.mode) ? draft.mode : 'bike';
  let loop = !!draft.loop;
  let name = draft.name || '';
  let sportId = U.SPORTS[draft.sport] ? draft.sport : 0;
  let fixed = null, route = null, seq = 0, timer = 0, ctrl = null, hoverMarker = null, trackLayer = null;
  const PSPORTS = [0, 1, 6, 8];

  root.innerHTML = `<div class="view-enter">
    <div class="section-head"><div><h1 class="h1">Planer tras</h1><p class="sub" style="margin-top:6px">Kliknij mapę, aby dodać punkty. Trasa dopasuje się do dróg, a wysokości policzą się automatycznie.</p></div></div>
    <div class="planner section">
      <div>
        <div class="map tall" id="pmap"></div>
        <div class="card" style="margin-top:16px">
          <div class="tiles" id="pstats" style="grid-template-columns:repeat(auto-fit,minmax(110px,1fr))"></div>
          <div id="chartbox" style="margin-top:14px"></div>
        </div>
      </div>
      <div class="panel">
        <div class="card" style="display:flex;flex-direction:column;gap:14px">
          <div class="seg" id="mode" style="align-self:flex-start"><button data-m="bike">🚴 Rower</button><button data-m="foot">🚶 Pieszo</button><button data-m="line">📏 Prosto</button></div>
          <div class="row"><button class="btn sm" id="undo">↶ Cofnij</button><button class="btn sm" id="reverse">⇄ Odwróć</button><button class="btn sm danger" id="clear">Wyczyść</button></div>
          <label class="switch"><input type="checkbox" id="loop"> Pętla (powrót do startu)</label>
          <div class="row"><input class="input" id="place" placeholder="Szukaj miejsca…" style="flex:1;min-width:0"><button class="btn sm" id="find">Szukaj</button><button class="btn sm" id="locate" aria-label="Moja lokalizacja">◎</button></div>
          <p class="sub" id="status" style="min-height:20px"></p>
        </div>
        <div class="card" style="display:flex;flex-direction:column;gap:12px">
          <h2 class="h2" style="font-size:19px">Zapisz trasę</h2>
          <input class="input" id="name" placeholder="Nazwa trasy" maxlength="80">
          <select class="input" id="sport">${PSPORTS.map((id) => `<option value="${id}">${esc(U.sport(id).label)}</option>`).join('')}</select>
          <label class="switch"><input type="checkbox" id="pub"> Publiczna (widoczna dla innych)</label>
          <div class="row"><button class="btn primary" id="save" style="flex:1;justify-content:center">Zapisz w koncie</button><button class="btn" id="gpx">⬇ GPX</button></div>
          <p class="sub" style="font-size:13px">Zapisane trasy pojawią się w aplikacji: Rywalizacja online → Trasy z internetu.</p>
        </div>
        <div class="card"><h2 class="h2" style="font-size:19px;margin-bottom:6px">Moje trasy</h2><div id="mine"></div></div>
      </div>
    </div>
  </div>`;

  const map = makeMap($('#pmap', root), { center: wp[0] || [50.06, 19.94], zoom: wp.length ? 12 : 11 });
  const markerLayer = L.layerGroup().addTo(map);
  const save = () => { try { localStorage.setItem('tracko.planner', JSON.stringify({ wp, mode, loop, name, sport: sportId })); } catch (e) { /* pełna pamięć */ } };
  const status = (t) => { $('#status', root).textContent = t || ''; };

  function renderControls() {
    $$('#mode button', root).forEach((b) => b.classList.toggle('on', b.dataset.m === mode));
    $('#loop', root).checked = loop;
    $('#name', root).value = name;
    $('#sport', root).value = String(sportId);
    const has = !!route && route.pts.length > 1;
    $('#save', root).disabled = !has;
    $('#gpx', root).disabled = !has;
    $('#undo', root).disabled = !wp.length;
    $('#clear', root).disabled = !wp.length;
    $('#reverse', root).disabled = wp.length < 2;
  }
  function renderMarkers() {
    markerLayer.clearLayers();
    wp.forEach((p, i) => {
      const color = i === 0 ? '#30D158' : (i === wp.length - 1 && !loop ? '#FF3B30' : '#0A84FF');
      const icon = L.divIcon({ className: '', html: `<div class="pin-dot" style="--c:${color};width:22px;height:22px;display:grid;place-items:center;color:#fff;font-size:10px;font-weight:800">${i + 1}</div>`, iconSize: [22, 22], iconAnchor: [11, 11] });
      const m = L.marker(p, { icon, draggable: true, autoPan: true }).addTo(markerLayer);
      m.on('dragend', () => { const ll = m.getLatLng(); wp[i] = [ll.lat, ll.lng]; changed(); });
      const pop = document.createElement('div');
      pop.innerHTML = `<b>Punkt ${i + 1}</b><br><button class="btn sm danger" style="margin-top:8px">Usuń punkt</button>`;
      pop.querySelector('button').onclick = () => { wp.splice(i, 1); map.closePopup(); changed(); };
      m.bindPopup(pop);
    });
  }
  function renderStats() {
    const box = $('#pstats', root);
    if (!route) { box.innerHTML = tile('Dystans', '–') + tile('Podjazd', '–') + tile('Zjazd', '–') + tile('Czas', '–'); return; }
    box.innerHTML = tile('Dystans', esc(U.fmtKm(route.dist)), '', 'accent') +
      tile('Podjazd', route.eleOk ? '↑' + esc(U.fmtM(route.up)) : '–') + tile('Zjazd', route.eleOk ? '↓' + esc(U.fmtM(route.down)) : '–') +
      tile('Czas (szac.)', esc(U.fmtDur(U.estTimeSec(sportId, route.dist))));
  }
  function renderChart() {
    const box = $('#chartbox', root);
    if (!route || !route.eleOk) { box.innerHTML = route ? '<p class="sub">Profil wysokości niedostępny (brak połączenia z usługą wysokości).</p>' : '<p class="sub">Dodaj co najmniej dwa punkty, aby zobaczyć trasę i profil wysokości.</p>'; return; }
    const W = 600, H = 150;
    const ele = route.pts.map((p) => p[2]);
    const c = U.chartPath(route.cum, ele, W, H);
    box.innerHTML = `<svg class="chart" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="Profil wysokości">
      <defs><linearGradient id="eleGrad" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0A84FF" stop-opacity=".35"/><stop offset="1" stop-color="#0A84FF" stop-opacity="0"/></linearGradient></defs>
      <path class="area" d="${c.area}"/><path class="line" d="${c.line}"/>
      <text x="8" y="14">${Math.round(c.max)} m</text><text x="8" y="${H - 6}">${Math.round(c.min)} m</text><text x="${W - 8}" y="${H - 6}" text-anchor="end">${esc(U.fmtKm(c.total))}</text>
      <line id="cur" x1="0" x2="0" y1="${10}" y2="${H - 20}" stroke="var(--secondary)" stroke-width="1" opacity="0"/><text id="curtxt" x="0" y="26" opacity="0" style="font-weight:700;fill:var(--label)"></text></svg>`;
    const svg = $('svg', box);
    const move = (ev) => {
      const r = svg.getBoundingClientRect();
      const x = Math.min(Math.max((ev.clientX - r.left) / r.width, 0), 1);
      const d = x * route.dist;
      const i = U.idxAtDist(route.cum, d);
      const p = route.pts[i];
      const px = c.X(route.cum[i]);
      const cur = $('#cur', svg), txt = $('#curtxt', svg);
      cur.setAttribute('x1', px); cur.setAttribute('x2', px); cur.setAttribute('opacity', 1);
      const grade = i > 0 && route.cum[i] - route.cum[i - 1] > 0 ? ((route.pts[i][2] - route.pts[i - 1][2]) / (route.cum[i] - route.cum[i - 1])) * 100 : 0;
      txt.textContent = `${U.fmtKm(route.cum[i])} · ${Math.round(p[2])} m · ${grade.toFixed(1)}%`;
      txt.setAttribute('x', Math.min(Math.max(px + 6, 6), W - 150)); txt.setAttribute('opacity', 1);
      if (!hoverMarker) hoverMarker = L.circleMarker([p[0], p[1]], { radius: 7, color: '#fff', weight: 3, fillColor: '#0A84FF', fillOpacity: 1 }).addTo(map);
      else hoverMarker.setLatLng([p[0], p[1]]);
    };
    const leave = () => { $('#cur', svg).setAttribute('opacity', 0); $('#curtxt', svg).setAttribute('opacity', 0); if (hoverMarker) { hoverMarker.remove(); hoverMarker = null; } };
    svg.addEventListener('pointermove', move); svg.addEventListener('pointerleave', leave);
  }
  function drawRoute() {
    if (trackLayer) { trackLayer.remove(); trackLayer = null; }
    if (route && route.pts.length > 1) trackLayer = addTrack(map, route.pts.map((p) => [p[0], p[1]]), U.sport(sportId).color);
  }
  async function recompute() {
    const my = ++seq;
    if (ctrl) ctrl.abort();
    ctrl = new AbortController();
    const signal = ctrl.signal;
    if (fixed) {
      route = routeFromGeom(fixed);
    } else if (wp.length < 2) {
      route = null;
    } else {
      status('Wyznaczam trasę…');
      const seqPts = loop ? [...wp, wp[0]] : wp;
      let coords = seqPts.map((p) => [p[0], p[1]]);
      if (mode !== 'line') {
        try { coords = await routeVia(mode, seqPts, signal); }
        catch (e) { if (e && e.name === 'AbortError') return; toast('Nie udało się wyznaczyć trasy po drogach – użyto linii prostych'); }
      }
      if (my !== seq) return;
      try { route = await buildRoute(coords, signal); }
      catch (e) { if (e && e.name === 'AbortError') return; route = null; }
    }
    if (my !== seq) return;
    status('');
    drawRoute(); renderStats(); renderChart(); renderControls();
  }
  const schedule = () => { clearTimeout(timer); timer = setTimeout(recompute, 280); };
  function changed() {
    fixed = null; save(); renderMarkers();
    if (wp.length < 2) {   // bez trasy nie ma na co czekać – czyścimy od razu (i anulujemy trwające obliczenia)
      seq++; clearTimeout(timer); if (ctrl) ctrl.abort();
      route = null; status(''); drawRoute(); renderStats(); renderChart(); renderControls();
      return;
    }
    renderControls(); schedule();
  }

  // zdarzenia
  map.on('click', (e) => { wp.push([e.latlng.lat, e.latlng.lng]); changed(); });
  $('#mode', root).addEventListener('click', (ev) => { const b = ev.target.closest('button'); if (!b) return; mode = b.dataset.m; if (mode !== 'line') fixed = null; save(); renderControls(); schedule(); });
  $('#undo', root).onclick = () => { wp.pop(); changed(); };
  $('#clear', root).onclick = () => { wp = []; name = ''; changed(); renderControls(); };
  $('#reverse', root).onclick = () => { wp.reverse(); changed(); };
  $('#loop', root).onchange = (ev) => { loop = ev.target.checked; changed(); };
  $('#name', root).oninput = (ev) => { name = ev.target.value; save(); };
  $('#sport', root).onchange = (ev) => { sportId = Number(ev.target.value); save(); drawRoute(); renderStats(); };
  $('#gpx', root).onclick = () => { if (route) U.download((name || 'trasa') + '.gpx', U.buildGpx(name || 'Trasa Tracko', route.pts), 'application/gpx+xml'); };
  $('#find', root).onclick = async () => {
    const q = $('#place', root).value.trim(); if (!q) return;
    try {
      const r = await fetch(`https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=pl&q=${encodeURIComponent(q)}`);
      const j = await r.json();
      if (!j.length) { toast('Nie znaleziono miejsca'); return; }
      map.flyTo([parseFloat(j[0].lat), parseFloat(j[0].lon)], 13);
    } catch (e) { toast('Wyszukiwanie niedostępne'); }
  };
  $('#place', root).addEventListener('keydown', (ev) => { if (ev.key === 'Enter') $('#find', root).click(); });
  $('#locate', root).onclick = () => {
    if (!navigator.geolocation) { toast('Przeglądarka nie udostępnia lokalizacji'); return; }
    navigator.geolocation.getCurrentPosition((p) => map.flyTo([p.coords.latitude, p.coords.longitude], 14), () => toast('Nie udało się pobrać lokalizacji'));
  };
  $('#save', root).onclick = async () => {
    if (!route) return;
    if (!state.user) { toast('Zaloguj się, aby zapisać trasę'); openAuth('in'); return; }
    const btn = $('#save', root); btn.disabled = true;
    try {
      await api.saveRoute({
        name: (name || 'Trasa z planera').slice(0, 80), sport: sportId, distance_m: Math.round(route.dist), ascent_m: Math.round(route.up),
        geom: U.encodeRouteGeom(route.pts), is_public: $('#pub', root).checked,
      });
      toast('Trasa zapisana ✓'); loadMine();
    } catch (e) { toast(e.message || 'Nie udało się zapisać'); }
    renderControls();
  };

  async function loadMine() {
    const box = $('#mine', root);
    if (!state.user) { box.innerHTML = '<p class="sub">Zaloguj się, aby zobaczyć zapisane trasy.</p><p style="margin-top:10px"><button class="btn sm primary" id="mlogin">Zaloguj się</button></p>'; $('#mlogin', box).onclick = () => openAuth('in'); return; }
    try {
      const rows = await api.myRoutes();
      box.innerHTML = rows.length ? rows.map((r) => `<div class="route-item" data-id="${esc(r.id)}"><div class="grow"><div class="t">${esc(r.name)}</div><div class="muted" style="font-size:13px">${esc(U.fmtKm(r.distance_m))} · ${esc(U.fmtDate(new Date(r.created_at).getTime()))}${r.source === 'web' ? '' : ' · z aplikacji'}</div></div>
        <button class="btn sm" data-a="load">Wczytaj</button><button class="btn sm danger" data-a="del" aria-label="Usuń">✕</button></div>`).join('') : '<p class="sub">Brak zapisanych tras.</p>';
      box.onclick = async (ev) => {
        const b = ev.target.closest('button'); if (!b) return;
        const id = b.closest('.route-item').dataset.id;
        const r = rows.find((x) => String(x.id) === id);
        if (b.dataset.a === 'del') { try { await api.deleteRoute(id); toast('Usunięto'); loadMine(); } catch (e) { toast(e.message); } return; }
        if (b.dataset.a === 'load' && r) {
          const rt = routeFromGeom(r.geom);
          if (!rt) { toast('Uszkodzona trasa'); return; }
          fixed = r.geom; mode = 'line'; name = r.name; sportId = U.SPORTS[r.sport] ? r.sport : 0; loop = false;
          wp = U.thin(rt.pts.map((p) => [p[0], p[1]]), Math.max(100, rt.dist / 30), 30);
          save(); renderMarkers(); renderControls();
          recompute().then(() => { if (route) fitTo(map, route.pts.map((p) => [p[0], p[1]])); });
        }
      };
    } catch (e) { box.innerHTML = `<p class="sub">${esc(e.message)}</p>`; }
  }

  renderMarkers(); renderControls(); renderStats(); renderChart();
  if (wp.length > 1) { fitTo(map, wp); schedule(); }
  loadMine();
  return () => { clearTimeout(timer); if (ctrl) ctrl.abort(); };
}

// ================================================================== LOGOWANIE

const authDlg = document.getElementById('auth');
function openAuth(mode = 'in') {
  const logged = !!state.user;
  authDlg.innerHTML = logged
    ? `<form method="dialog" style="padding:24px;display:flex;flex-direction:column;gap:14px">
        <div style="display:flex;align-items:center;gap:12px">${avatar(state.user.email || 'U')}<div><b>Zalogowano</b><br><span class="muted" style="font-size:14px">${esc(state.user.email || '')}</span></div></div>
        <div style="display:flex;gap:8px;justify-content:flex-end"><button class="btn" value="cancel">Zamknij</button><button class="btn danger" type="button" id="logout">Wyloguj</button></div></form>`
    : `<form id="auth-form" novalidate style="padding:24px;display:flex;flex-direction:column;gap:14px">
        <div class="seg" id="auth-mode" style="align-self:center"><button type="button" data-m="in" class="${mode === 'in' ? 'on' : ''}">Zaloguj</button><button type="button" data-m="up" class="${mode === 'up' ? 'on' : ''}">Utwórz konto</button></div>
        <p class="sub" style="font-size:14px">Użyj tego samego e-maila, który podałeś w aplikacji (Ustawienia → Rywalizacja online → Konto), aby zobaczyć swoje dane.</p>
        <input class="input" id="auth-email" type="email" placeholder="E-mail" autocomplete="email" required>
        <input class="input" id="auth-pass" type="password" placeholder="Hasło (min. 6 znaków)" autocomplete="${mode === 'in' ? 'current-password' : 'new-password'}" minlength="6" required>
        <div class="err" id="auth-err"></div>
        <div style="display:flex;gap:8px;justify-content:flex-end"><button class="btn" type="button" id="auth-cancel">Anuluj</button><button class="btn primary" type="submit">${mode === 'in' ? 'Zaloguj' : 'Utwórz konto'}</button></div></form>`;
  if (authDlg.showModal) { if (!authDlg.open) authDlg.showModal(); } else authDlg.setAttribute('open', '');
  const close = () => { if (authDlg.close) authDlg.close(); else authDlg.removeAttribute('open'); };
  if (logged) { $('#logout', authDlg).onclick = async () => { await api.signOut(); close(); toast('Wylogowano'); }; return; }
  $('#auth-mode', authDlg).onclick = (ev) => { const b = ev.target.closest('button'); if (b) openAuth(b.dataset.m); };
  $('#auth-cancel', authDlg).onclick = close;
  $('#auth-form', authDlg).addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const email = $('#auth-email', authDlg).value.trim(), pass = $('#auth-pass', authDlg).value, err = $('#auth-err', authDlg);
    if (!/^\S+@\S+\.\S+$/.test(email) || pass.length < 6) { err.textContent = 'Podaj poprawny e-mail i hasło (min. 6 znaków).'; return; }
    err.textContent = '';
    try {
      if (mode === 'in') await api.signIn(email, pass); else await api.signUp(email, pass);
      state.user = await api.getUser();
      updateAccountUI();
      close();
      toast(mode === 'in' ? 'Zalogowano ✓' : 'Konto utworzone. Jeśli serwer wymaga potwierdzenia, sprawdź e-mail.');
      if (state.user) navigate();
    } catch (e) { err.textContent = e.message === 'Invalid login credentials' ? 'Nieprawidłowy e-mail lub hasło.' : (e.message || 'Błąd logowania'); }
  });
}
function updateAccountUI() {
  const b = document.getElementById('account-btn');
  if (state.user) { b.innerHTML = `${avatar(state.user.email || 'U', 'sm')}`; b.className = 'icon-btn'; b.style.background = 'transparent'; b.title = state.user.email || 'Konto'; }
  else { b.textContent = 'Zaloguj'; b.className = 'btn sm'; b.removeAttribute('style'); b.title = ''; }
}

// ================================================================== ROUTER I START

const ROUTES = [
  [/^\/?$/, (r) => viewHome(r)],
  [/^\/odcinki$/, (r) => viewSegments(r)],
  [/^\/odcinek\/([^/]+)$/, (r, q, uid) => viewSegment(r, uid, q)],
  [/^\/aktywnosci$/, (r) => viewRides(r)],
  [/^\/aktywnosc\/([^/]+)\/(\d+)$/, (r, q, owner, at) => viewRide(r, owner, at)],
  [/^\/planer$/, (r) => viewPlanner(r)],
];
let cleanup = null, navToken = 0;

function setActive(path) {
  $$('.nav a, .tabbar a').forEach((a) => {
    const r = a.dataset.route;
    a.classList.toggle('active', r === '/' ? path === '/' || path === '' : path.startsWith(r));
  });
}
async function navigate() {
  const token = ++navToken;
  const raw = (location.hash || '#/').replace(/^#/, '') || '/';
  const [path, qs] = raw.split('?');
  if (cleanup) { try { cleanup(); } catch (e) { /* widok już zamknięty */ } cleanup = null; }
  disposeMaps();
  setActive(path);
  const root = document.createElement('div');
  view.replaceChildren(root);
  for (const [re, fn] of ROUTES) {
    const m = path.match(re);
    if (!m) continue;
    let c = null;
    try { c = await fn(root, new URLSearchParams(qs || ''), ...m.slice(1).map(decodeURIComponent)); }
    catch (e) { console.error(e); errorView(root, e, navigate); }
    if (token !== navToken) { if (typeof c === 'function') c(); return; }
    cleanup = typeof c === 'function' ? c : null;
    window.scrollTo(0, 0);
    return;
  }
  location.hash = '#/';
}

function updateThemeBtn() { document.getElementById('theme-btn').innerHTML = theme() === 'dark' ? SUN : MOON; }
function setTheme(t) {
  document.documentElement.dataset.theme = t;
  try { localStorage.setItem('tracko.theme', t); } catch (e) { /* tryb prywatny */ }
  updateThemeBtn();
  for (const m of mapsOpen) if (m._tl) m._tl.setUrl(TILES[t]);
}

async function boot() {
  document.getElementById('foot-app').href = cfg.releasesUrl || '#';
  document.getElementById('foot-repo').href = cfg.repoUrl || '#';
  if (DEMO) {
    document.getElementById('demo').innerHTML = `<div class="demo-banner">Tryb demonstracyjny – dane przykładowe. Aby zobaczyć prawdziwe wyniki z aplikacji, skonfiguruj zaplecze (<a href="${esc((cfg.repoUrl || '#') + '/blob/main/docs/CLOUD_API.md')}" rel="noopener">instrukcja</a>).</div>`;
  }
  updateThemeBtn();
  document.getElementById('theme-btn').onclick = () => setTheme(theme() === 'dark' ? 'light' : 'dark');
  document.getElementById('account-btn').onclick = () => openAuth('in');
  try { state.user = await api.getUser(); } catch (e) { state.user = null; }
  updateAccountUI();
  api.onAuth((u) => {
    const changed = (u && u.id || null) !== (state.user && state.user.id || null);
    state.user = u; updateAccountUI();
    if (changed) navigate();
  });
  window.addEventListener('hashchange', navigate);

  const splash = document.getElementById('splash');
  let skip = params.has('nosplash');
  try { if (sessionStorage.getItem('tracko.splash')) skip = true; else if (!skip) sessionStorage.setItem('tracko.splash', '1'); } catch (e) { /* bez sessionStorage */ }
  if (skip) splash.classList.remove('on');
  else runSplash(splash, { reduced: !!(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches) });
  await navigate();   // treść ładuje się pod ekranem startowym
}

window.__tracko = { navigate, state, api };
boot();
