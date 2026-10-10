// Animacja startowa – ta sama co w aplikacji (Splash.kt): ślad rysuje się gradientową linią ze świecącą głowicą,
// za nim z opóźnieniem podąża przerywany „duch”, potem wjeżdża nazwa; całość znika zanikiem z lekkim powiększeniem.

/** funkcja czasowa jak CSS cubic-bezier(x1,y1,x2,y2) */
export function cubicBezierEasing(x1, y1, x2, y2) {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  const sx = (t) => ((ax * t + bx) * t + cx) * t;
  const sy = (t) => ((ay * t + by) * t + cy) * t;
  const dx = (t) => (3 * ax * t + 2 * bx) * t + cx;
  return (x) => {
    if (x <= 0) return 0;
    if (x >= 1) return 1;
    let t = x;
    for (let i = 0; i < 8; i++) {
      const e = sx(t) - x;
      if (Math.abs(e) < 1e-6) return sy(t);
      const d = dx(t);
      if (Math.abs(d) < 1e-6) break;
      t -= e / d;
    }
    let lo = 0, hi = 1;
    t = x;
    while (lo < hi) {
      const e = sx(t);
      if (Math.abs(e - x) < 1e-6) break;
      if (x > e) lo = t; else hi = t;
      t = (hi - lo) / 2 + lo;
      if (hi - lo < 1e-7) break;
    }
    return sy(t);
  };
}

/** punkty krzywej (dwa odcinki Béziera jak w aplikacji) w ramce w×h + dystans narastający */
export function splashPath(w, h, samples = 220) {
  const seg = [
    [[0.04, 0.78], [0.22, 1.02], [0.34, 0.30], [0.50, 0.50]],
    [[0.50, 0.50], [0.66, 0.70], [0.76, 0.02], [0.96, 0.22]],
  ];
  const pts = [];
  for (const [p0, p1, p2, p3] of seg) {
    for (let i = pts.length ? 1 : 0; i <= samples; i++) {
      const t = i / samples, u = 1 - t;
      const x = u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0];
      const y = u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1];
      pts.push([x * w, y * h]);
    }
  }
  const cum = [0];
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
  return { pts, cum, length: cum[cum.length - 1] };
}

/** fragment krzywej od 0 do dystansu `upTo` (z interpolacją ostatniego punktu) */
export function pathUpTo(path, upTo) {
  const out = [];
  for (let i = 0; i < path.pts.length; i++) {
    if (path.cum[i] <= upTo) out.push(path.pts[i]);
    else {
      const prev = path.pts[i - 1], f = (upTo - path.cum[i - 1]) / (path.cum[i] - path.cum[i - 1] || 1);
      if (prev) out.push([prev[0] + f * (path.pts[i][0] - prev[0]), prev[1] + f * (path.pts[i][1] - prev[1])]);
      break;
    }
  }
  return out;
}

const TIMELINE = { draw: 1150, titleDelay: 700, title: 550, hold: 260, exit: 400, ghostLag: 0.17 };

export function runSplash(root, { onDone, reduced = false } = {}) {
  const css = getComputedStyle(document.documentElement);
  const v = (n, d) => (css.getPropertyValue(n).trim() || d);
  const blue = v('--blue', '#0A84FF'), violet = v('--violet', '#5E5CE6'), green = v('--green', '#30D158'), sec = v('--secondary', 'rgba(120,120,130,.6)');

  root.innerHTML = '<canvas class="splash-canvas" aria-hidden="true"></canvas><div class="splash-title">Tracko</div><div class="splash-tag">TRASY&nbsp;&nbsp;·&nbsp;&nbsp;WYSIŁEK&nbsp;&nbsp;·&nbsp;&nbsp;DUCHY</div>';
  root.classList.add('on');
  const canvas = root.querySelector('canvas'), title = root.querySelector('.splash-title'), tag = root.querySelector('.splash-tag');
  const W = 264, H = 132, dpr = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = W * dpr; canvas.height = H * dpr; canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
  const ctx = canvas.getContext && canvas.getContext('2d');
  const path = splashPath(W, H);
  const easeDraw = cubicBezierEasing(0.2, 0.8, 0.25, 1), easeTitle = cubicBezierEasing(0.4, 0, 0.2, 1), easeExit = cubicBezierEasing(0.4, 0, 1, 1);

  let done = false, raf = 0, start = 0;
  const finish = () => {
    if (done) return;
    done = true;
    cancelAnimationFrame(raf);
    root.classList.remove('on');
    root.innerHTML = '';
    onDone && onDone();
  };
  root.addEventListener('pointerdown', finish, { once: true });
  window.addEventListener('keydown', finish, { once: true });

  if (reduced) {   // bez ruchu: krótki, statyczny ekran
    title.style.opacity = 1; tag.style.opacity = 0.9;
    setTimeout(() => { root.style.opacity = 0; setTimeout(finish, 250); }, 500);
    return finish;
  }

  const total = TIMELINE.titleDelay + TIMELINE.title + TIMELINE.hold + TIMELINE.exit;   // linia kończy się przed wejściem tytułu + hold
  function frame(now) {
    if (done) return;
    if (!start) start = now;
    const t = now - start;
    const draw = easeDraw(Math.min(t / TIMELINE.draw, 1));
    const tt = easeTitle(Math.min(Math.max((t - TIMELINE.titleDelay) / TIMELINE.title, 0), 1));
    const exitT = TIMELINE.titleDelay + TIMELINE.title + TIMELINE.hold;
    const ex = easeExit(Math.min(Math.max((t - exitT) / TIMELINE.exit, 0), 1));

    if (ctx) {
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      const sw = 7;
      // duch – opóźniony, przerywany
      const gp = Math.max(0, Math.min(1, draw - TIMELINE.ghostLag));
      if (gp > 0.01) {
        ctx.save();
        ctx.setLineDash([sw * 1.6, sw * 2.2]);
        ctx.strokeStyle = sec; ctx.globalAlpha = 0.4; ctx.lineWidth = sw * 0.7; ctx.lineCap = 'round';
        strokePts(ctx, pathUpTo(path, path.length * gp));
        ctx.restore();
      }
      // właściwy ślad z gradientem
      const grad = ctx.createLinearGradient(0, 0, W, 0);
      grad.addColorStop(0, blue); grad.addColorStop(0.5, violet); grad.addColorStop(1, green);
      ctx.strokeStyle = grad; ctx.lineWidth = sw; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      const cur = pathUpTo(path, Math.max(path.length * draw, 0.01));
      strokePts(ctx, cur);
      // punkt startu
      ctx.fillStyle = blue; ctx.beginPath(); ctx.arc(path.pts[0][0], path.pts[0][1], sw * 0.95, 0, Math.PI * 2); ctx.fill();
      // świecąca głowica
      if (draw > 0.01 && draw < 0.995 && cur.length) {
        const [hx, hy] = cur[cur.length - 1];
        const g = ctx.createRadialGradient(hx, hy, 0, hx, hy, sw * 3.4);
        g.addColorStop(0, 'rgba(255,255,255,0.95)'); g.addColorStop(1, hexA(violet, 0));
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(hx, hy, sw * 3.4, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(hx, hy, sw * 0.7, 0, Math.PI * 2); ctx.fill();
      }
    }
    title.style.opacity = tt;
    title.style.transform = `translateY(${(1 - tt) * 14}px)`;
    title.style.letterSpacing = `${2 + 9 * (1 - tt)}px`;
    tag.style.opacity = tt * 0.9;
    root.style.opacity = 1 - ex;
    root.style.transform = `scale(${1 + 0.06 * ex})`;
    root.style.setProperty('--glow', String(draw));

    if (t >= total) { finish(); return; }
    raf = requestAnimationFrame(frame);
  }
  raf = requestAnimationFrame(frame);
  return finish;
}

function strokePts(ctx, pts) {
  if (pts.length < 2) return;
  ctx.beginPath();
  ctx.moveTo(pts[0][0], pts[0][1]);
  for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
  ctx.stroke();
}
function hexA(c, a) {
  const m = /^#?([0-9a-f]{6})$/i.exec(c);
  if (!m) return `rgba(94,92,230,${a})`;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}
