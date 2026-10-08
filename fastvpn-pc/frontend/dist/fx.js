// Анимация подключения (как на телефоне): Гиперпрыжок, Спидометр, Радар.
// Один холст на всё окно под содержимым — волны уходят под карточки и не обрезаются.
const FX = (() => {
  const cv = document.getElementById('fx');
  const c = cv.getContext('2d');
  const TAU = Math.PI * 2;
  let W = 0, H = 0, dpr = 1;
  let mode = 'warp', anchor = null, visible = true;
  let connecting = false, running = false, stateAt = 0, connectedAt = 0;
  let col = {};
  const stars = [], waves = [], sparks = [], blips = [];
  let speed = 0, lastFrame = 0, gauge = 0, gaugeTarget = 0, mbit = 0, lock = null, raf = 0;
  const now = () => performance.now();
  const rnd = (a, b) => a + Math.random() * (b - a);

  function readColors() {
    const s = getComputedStyle(document.body);
    const v = k => s.getPropertyValue(k).trim();
    col = { accent: v('--accent'), text: v('--text'), red: v('--red'), top: v('--acc-top'), bot: v('--acc-bot') };
  }
  function alpha(hex, a) {
    const m = hex.replace('#', '');
    const n = parseInt(m.length === 3 ? m.split('').map(x => x + x).join('') : m, 16);
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${Math.max(0, Math.min(1, a))})`;
  }
  function resize() {
    dpr = window.devicePixelRatio || 1;
    W = innerWidth; H = innerHeight;
    cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    kick();
  }
  function newStar(any) {
    const z = any ? rnd(.05, 1) : 1;
    return { x: rnd(-1, 1), y: rnd(-1, 1), z, pz: z, g: Math.random() < .22 };
  }
  for (let i = 0; i < 170; i++) stars.push(newStar(true));

  function center() {
    if (!anchor || !anchor.offsetParent) return null;
    const r = anchor.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, r: anchor.offsetWidth / 2 };
  }

  // ---------------------------- Гиперпрыжок ----------------------------
  function drawSky(t, dt, p) {
    let target = 0;
    if (connecting) target = .0025 + Math.pow(Math.min(1, t / 2.5), 2.2) * .055;
    else if (running) target = t < .25 ? .08 : .0026;
    speed += (target - speed) * (running && t >= .25 ? .04 : .12) * dt;
    if (target === 0 && speed < .0004) speed = 0;
    const cx = p ? p.x : W / 2, cy = p ? p.y : H * .4, F = Math.max(W, H) * .55;
    c.lineCap = 'round';
    for (const s of stars) {
      s.pz = s.z; s.z -= speed * dt;
      if (s.z <= .02) { Object.assign(s, newStar(false)); continue; }
      const x = cx + s.x / s.z * F, y = cy + s.y / s.z * F;
      if (x < -60 || x > W + 60 || y < -60 || y > H + 60) { Object.assign(s, newStar(false)); continue; }
      const px = cx + s.x / s.pz * F, py = cy + s.y / s.pz * F;
      const a = Math.min(1, (1 - s.z) * 1.3) * .9;
      c.strokeStyle = alpha(s.g ? col.accent : col.text, a);
      c.lineWidth = Math.max(.8, (1 - s.z) * 2.6);
      c.beginPath(); c.moveTo(px, py); c.lineTo(x + .01, y); c.stroke();
    }
    if (connecting && p) {
      c.save(); c.strokeStyle = alpha(col.accent, .3 + .18 * Math.sin(t * 7)); c.lineWidth = 2;
      c.shadowColor = col.accent; c.shadowBlur = 14; c.beginPath(); c.arc(p.x, p.y, p.r + 10, 0, TAU); c.stroke(); c.restore();
    }
    return speed > 0;
  }

  // ----------------------------- Спидометр -----------------------------
  function drawGauge(t, p) {
    if (!p) return false;
    const rg = p.r + 15, a0 = Math.PI * .75, span = Math.PI * 1.5;
    const since = (now() - connectedAt) / 1000;
    const prev = gauge;
    if (connecting) gauge = (1 - Math.pow(1 - Math.min(1, t / 2.5), 3)) * .82 + Math.sin(t * 13) * .02;
    else if (running && since < .18) gauge = .82 + .18 * (since / .18);
    else if (running && since < 1.2) { const tg = Math.max(gaugeTarget, .04); gauge = tg + (1 - tg) * Math.exp(-since * 4) * Math.cos(since * 18); }
    else gauge += (gaugeTarget - gauge) * .12;
    gauge = Math.max(0, Math.min(1, gauge));
    c.save();
    for (let i = 0; i <= 30; i++) {
      const f = i / 30, a = a0 + span * f, big = i % 5 === 0, lit = f <= gauge + 1e-4 && gauge > .005, red = i >= 25;
      const cc = lit ? (red ? col.red : col.accent) : alpha(col.text, .14);
      c.strokeStyle = cc; c.lineWidth = big ? 2.4 : 1.4; c.shadowColor = cc; c.shadowBlur = lit ? 7 : 0;
      const r1 = rg + (big ? 4 : 7), r2 = rg + 14;
      c.beginPath(); c.moveTo(p.x + Math.cos(a) * r1, p.y + Math.sin(a) * r1); c.lineTo(p.x + Math.cos(a) * r2, p.y + Math.sin(a) * r2); c.stroke();
    }
    c.shadowBlur = 0; c.lineCap = 'round'; c.lineWidth = 4.5;
    c.strokeStyle = alpha(col.text, .08); c.beginPath(); c.arc(p.x, p.y, rg, a0, a0 + span); c.stroke();
    if (gauge > .005) {
      const g = c.createLinearGradient(p.x - rg, p.y + rg, p.x + rg, p.y - rg);
      g.addColorStop(0, col.bot); g.addColorStop(.7, col.top); g.addColorStop(1, col.red);
      c.strokeStyle = g; c.shadowColor = col.accent; c.shadowBlur = 12;
      c.beginPath(); c.arc(p.x, p.y, rg, a0, a0 + span * gauge); c.stroke();
      const ha = a0 + span * gauge;
      c.fillStyle = '#fff'; c.shadowColor = '#fff'; c.shadowBlur = 12;
      c.beginPath(); c.arc(p.x + Math.cos(ha) * rg, p.y + Math.sin(ha) * rg, 4.5, 0, TAU); c.fill();
    }
    c.shadowBlur = 0;
    if (running && since > .7) {
      c.fillStyle = col.text; c.font = '700 14px "Segoe UI", sans-serif'; c.textAlign = 'center';
      c.fillText(`${mbit >= 10 ? mbit.toFixed(0) : mbit.toFixed(1)} Мбит/с`, p.x, p.y + p.r + 26);
    }
    c.restore();
    return Math.abs(gauge - prev) > .0005 || (running && since < 1.3);
  }

  // ------------------------------- Радар -------------------------------
  function drawRadar(t, p) {
    if (!p) return false;
    const rIn = p.r + 6, rOut = p.r + 34;
    c.save();
    c.strokeStyle = alpha(col.accent, .14); c.lineWidth = 1;
    for (const r of [p.r + 18, rOut]) { c.beginPath(); c.arc(p.x, p.y, r, 0, TAU); c.stroke(); }
    let moving = false;
    if (connecting) {
      const sw = (t * 3.2) % TAU, mid = (rIn + rOut) / 2;
      c.lineWidth = rOut - rIn;
      for (let i = 0; i < 22; i++) {
        c.strokeStyle = alpha(col.accent, .22 * (1 - i / 22));
        const a = sw - i * .05; c.beginPath(); c.arc(p.x, p.y, mid, a - .055, a); c.stroke();
      }
      c.lineWidth = 1.8; c.strokeStyle = col.accent; c.shadowColor = col.accent; c.shadowBlur = 10;
      c.beginPath(); c.moveTo(p.x + Math.cos(sw) * rIn, p.y + Math.sin(sw) * rIn); c.lineTo(p.x + Math.cos(sw) * rOut, p.y + Math.sin(sw) * rOut); c.stroke();
      c.shadowBlur = 0;
      if (Math.random() < .09) blips.push({ a: sw, r: rnd(p.r + 10, rOut - 4), born: now() });
      for (let i = blips.length - 1; i >= 0; i--) {
        const b = blips[i], al = 1 - (now() - b.born) / 1600;
        if (al <= 0) { blips.splice(i, 1); continue; }
        c.fillStyle = alpha(col.accent, al); c.beginPath(); c.arc(p.x + Math.cos(b.a) * b.r, p.y + Math.sin(b.a) * b.r, 3, 0, TAU); c.fill();
      }
    }
    if (running && lock) {
      const a = (now() - connectedAt) / 1000, bx = p.x + Math.cos(lock.a) * lock.r, by = p.y + Math.sin(lock.a) * lock.r;
      const k = Math.min(1, a / .25), ex = p.x + Math.cos(lock.a) * p.r, ey = p.y + Math.sin(lock.a) * p.r;
      c.lineWidth = 2; c.strokeStyle = alpha('#ffffff', Math.max(.35, 1 - a * .6)); c.shadowColor = col.accent; c.shadowBlur = 10;
      c.beginPath(); c.moveTo(bx, by); c.lineTo(bx + (ex - bx) * k, by + (ey - by) * k); c.stroke();
      c.shadowBlur = 0; c.fillStyle = '#fff'; c.beginPath(); c.arc(bx, by, 4, 0, TAU); c.fill();
      c.lineWidth = 1.5; c.strokeStyle = col.accent; c.beginPath(); c.arc(bx, by, 9, 0, TAU); c.stroke();
      if (a < 1.2) { c.strokeStyle = alpha(col.accent, 1 - a / 1.2); c.beginPath(); c.arc(bx, by, 7 + a * 34, 0, TAU); c.stroke(); moving = true; }
    }
    c.restore();
    return moving;
  }

  function drawEffects(p) {
    const maxR = Math.hypot(W, H);
    c.save();
    for (let i = waves.length - 1; i >= 0; i--) {
      const w = waves[i]; w.r += w.v; w.a -= .022;
      if (w.a <= 0 || w.r > maxR) { waves.splice(i, 1); continue; }
      c.strokeStyle = alpha(col.accent, w.a); c.lineWidth = w.w; c.shadowColor = col.accent; c.shadowBlur = 16;
      c.beginPath(); c.arc(w.x, w.y, w.r, 0, TAU); c.stroke();
    }
    c.shadowBlur = 0;
    for (let i = sparks.length - 1; i >= 0; i--) {
      const s = sparks[i]; s.x += s.vx; s.y += s.vy; s.vx *= .95; s.vy *= .95; s.life -= s.dec;
      if (s.life <= 0) { sparks.splice(i, 1); continue; }
      c.fillStyle = alpha(s.c, s.life); c.beginPath(); c.arc(s.x, s.y, 1 + s.life * 2, 0, TAU); c.fill();
    }
    c.restore();
    return waves.length > 0 || sparks.length > 0;
  }

  function frame() {
    raf = 0;
    if (!col.accent) readColors();
    const t = (now() - stateAt) / 1000;
    const dt = lastFrame ? Math.min(3, Math.max(.5, (now() - lastFrame) / 16)) : 1;
    lastFrame = now();
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, W, H);
    const p = visible ? center() : null;
    let more = connecting;
    if (mode === 'warp') more = drawSky(Math.max(0, t), dt, p) || more;
    else if (mode === 'gauge') more = drawGauge(Math.max(0, t), p) || more;
    else if (mode === 'radar') more = drawRadar(Math.max(0, t), p) || more;
    more = drawEffects(p) || more;
    if (more) raf = requestAnimationFrame(frame);
    else lastFrame = 0;
  }
  function kick() { if (!raf) raf = requestAnimationFrame(frame); }

  function connected() {
    connectedAt = now();
    const p = center();
    if (!p) return;
    if (mode === 'gauge') {
      waves.push({ x: p.x, y: p.y, r: p.r + 15, v: 2.6, a: .9, w: 3 });
      const a = Math.PI * .75 + Math.PI * 1.5, sx = p.x + Math.cos(a) * (p.r + 15), sy = p.y + Math.sin(a) * (p.r + 15);
      for (let i = 0; i < 45; i++) { const d = rnd(0, TAU), s = rnd(1.2, 4.5); sparks.push({ x: sx, y: sy, vx: Math.cos(d) * s, vy: Math.sin(d) * s, life: 1, dec: rnd(.015, .035), c: Math.random() < .35 ? '#ffffff' : (Math.random() < .5 ? '#ffd27a' : col.accent) }); }
    } else if (mode === 'radar') {
      const b = blips.slice().sort((x, y) => x.r - y.r)[0] || { a: -0.9, r: p.r + 20 };
      lock = { a: b.a, r: b.r };
      waves.push({ x: p.x, y: p.y, r: p.r, v: 2.2, a: .9, w: 2.5 });
    } else {
      waves.push({ x: p.x, y: p.y, r: p.r, v: 4, a: 1, w: 5 }, { x: p.x, y: p.y, r: p.r, v: 2.6, a: .7, w: 2.5 });
      const f = document.getElementById('flash');
      f.classList.add('on'); setTimeout(() => f.classList.remove('on'), 90);
    }
    kick();
  }

  addEventListener('resize', resize);
  resize();
  return {
    setAnchor(el) { anchor = el; kick(); },
    setVisible(v) { visible = v; kick(); },
    setMode(m) { mode = m; waves.length = 0; sparks.length = 0; kick(); },
    setTheme() { readColors(); kick(); },
    setState(conn, run) {
      if (conn === connecting && run === running) return;
      if (conn && !connecting) blips.length = 0;
      if (!run) { gaugeTarget = 0; lock = null; }
      connecting = conn; running = run; stateAt = now(); kick();
    },
    setSpeed(bps) {
      mbit = bps * 8 / 1e6;
      if (running && now() - connectedAt > 700) {
        gaugeTarget = Math.max(.04, Math.min(1, Math.log10(1 + mbit) / Math.log10(301)));
        kick();
      }
    },
    connected,
    redraw: kick,
  };
})();
