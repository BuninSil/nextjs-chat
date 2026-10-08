// Интерфейс Fast VPN для ПК. Логика — в Go (window.go.main.App), здесь только экраны.
const $ = id => document.getElementById(id);
// В браузере без приложения (превью) — заглушка с примерными данными
const api = new Proxy({}, {
  get: (_, name) => (...args) => {
    const app = (window.go && window.go.main && window.go.main.App) || window.MockApp;
    return app[name](...args);
  },
});

const THEMES = [
  { id: 'dark', title: 'Тёмная', bg: '#121417', card: '#1a1d22', a: '#43d17a', b: '#1f8f4c' },
  { id: 'light', title: 'Светлая', bg: '#f3f5f7', card: '#ffffff', a: '#34c759', b: '#178a3d' },
  { id: 'neon', title: 'Неон', bg: '#07070f', card: '#12122a', a: '#00e5ff', b: '#7a1fff' },
  { id: 'amoled', title: 'AMOLED (чёрная)', bg: '#000000', card: '#0e0f11', a: '#43d17a', b: '#1f8f4c' },
];
const ANIMS = [
  { id: 'warp', title: 'Гиперпрыжок', sub: 'Звёзды на всё окно срываются в лучи, при подключении — вспышка' },
  { id: 'gauge', title: 'Спидометр', sub: 'Шкала вокруг кнопки, стрелка улетает в красную зону, потом показывает скорость' },
  { id: 'radar', title: 'Радар', sub: 'Луч ищет серверы, при подключении лучший берётся в прицел' },
];
const MIN_ANIM_MS = 1800;
const BOLT = '<svg viewBox="0 0 24 24"><path d="M13,2L3,14h9l-1,8 10,-12h-9l1,-8z" fill="currentColor"/></svg>';

let screen = 'main', state = null, theme = '', anim = '';
let tapAt = 0, afterStop = false, wasRunning = null, showHidden = false, pollTimer = 0;

// ------------------------------- общее -------------------------------

function flagHtml(code) {
  return code ? `<img src="flags/${code.toLowerCase()}.svg" alt="" onerror="this.replaceWith(globeIcon())">` : globeSvg();
}
function globeSvg() { return '<svg viewBox="0 0 24 24"><use href="#i-globe"/></svg>'; }
window.globeIcon = () => { const s = document.createElement('span'); s.innerHTML = globeSvg(); return s.firstChild; };
function pingColor(ms) { return !ms ? 'var(--muted)' : ms <= 60 ? 'var(--accent)' : ms <= 100 ? 'var(--yellow)' : 'var(--red)'; }
function countryName(code) {
  try { return new Intl.DisplayNames(['ru'], { type: 'region' }).of(code.toUpperCase()); } catch (e) { return code; }
}
function fmtBytes(b) {
  if (b >= 1048576) return (b / 1048576).toFixed(1) + ' МБ/с';
  return Math.round(b / 1024) + ' КБ/с';
}

let toastTimer = 0;
function toast(msg) {
  if (!msg) return;
  const t = $('toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.hidden = true, 3600);
}

function dialog(title, text, buttons) {
  $('mTitle').textContent = title;
  $('mText').textContent = text;
  const b = $('mBtns'); b.innerHTML = '';
  for (const [label, fn] of buttons) {
    const x = document.createElement('button');
    x.textContent = label;
    x.onclick = () => { $('modal').hidden = true; fn && fn(); };
    b.appendChild(x);
  }
  $('modal').hidden = false;
}
const info = (t, m) => dialog(t, m, [['Понятно']]);

function go(name) {
  screen = name;
  for (const s of document.querySelectorAll('.screen')) s.hidden = s.id !== 's-' + name;
  FX.setVisible(name === 'main');
  if (name === 'servers') renderServers();
  if (name === 'sub') renderSub();
  if (name === 'settings') renderSettings();
  if (name === 'speed') renderSpeed();
}
document.addEventListener('click', e => {
  const g = e.target.closest('[data-go]');
  if (g) go(g.dataset.go);
  if (!e.target.closest('#menu')) $('menu').hidden = true;
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') { $('menu').hidden = true; $('modal').hidden = true; if (screen !== 'main') go('main'); }
});

function applyLook(s) {
  if (s.theme !== theme) { theme = s.theme; document.body.dataset.theme = theme; FX.setTheme(); }
  if (s.anim !== anim) { anim = s.anim; FX.setMode(anim); }
}

// ------------------------------ главный ------------------------------

$('play').onclick = async () => {
  const s = state;
  if (s && !s.running && !s.connecting && s.servers === 0) {
    info('Нет серверов', 'Сначала добавь подписку: «Подписка» → вставь ссылку от своего VPN-сервиса.');
    return;
  }
  if (s && (s.running || s.connecting)) { tapAt = 0; afterStop = true; }
  else { tapAt = performance.now(); afterStop = false; }
  await api.Toggle();
  poll();
};
$('ipLine').onclick = () => { api.RefreshIP(); $('ipLine').textContent = 'Узнаю IP…'; };

function renderMain(s) {
  const sinceTap = performance.now() - tapAt;
  const holding = tapAt > 0 && sinceTap < MIN_ANIM_MS;
  if (tapAt > 0 && !holding && (s.running || (!s.busy && !s.connecting))) tapAt = 0;
  if (afterStop && !s.busy && !s.connecting) afterStop = false;
  const running = s.running && !holding;
  const connecting = holding || (!afterStop && s.connecting);

  const st = $('statusLine');
  st.className = 'st' + (running ? ' on' : (s.error && !connecting ? ' err' : ''));
  st.textContent = connecting ? '◌  ' + (s.busyText || 'Подключаюсь…')
    : running ? '●  VPN подключён' + (s.busy && s.busyText ? ' · ' + s.busyText : '')
    : s.error ? '✖  Не подключилось' : '○  Отключено';

  if (s.server) {
    $('srvFlag').innerHTML = flagHtml(s.server.country);
    $('srvName').textContent = s.server.name;
    $('srvSub').textContent = `${s.server.type}${s.autoSelect ? ' · автовыбор' : ''} · ${s.servers} серверов`;
    $('srvPing').textContent = s.server.ping ? s.server.ping + ' ms' : '';
    $('srvPing').style.color = pingColor(s.server.ping);
  } else {
    $('srvFlag').innerHTML = '<svg viewBox="0 0 24 24"><use href="#i-link"/></svg>';
    $('srvName').textContent = 'Нет подписки';
    $('srvSub').textContent = 'Нажми «Подписка» и вставь ссылку';
    $('srvPing').textContent = '';
  }
  const ip = $('ipLine');
  if (connecting) ip.textContent = 'Твой IP: подключаюсь…';
  else if (s.ip) ip.innerHTML = `Твой IP: ${s.ip}` + (s.ipCountry ? ` · <img src="flags/${s.ipCountry.toLowerCase()}.svg" alt=""> ${countryName(s.ipCountry)}` : '');
  else if (!s.busy) ip.textContent = s.ipLoading ? 'Узнаю IP…' : 'IP не определился — нажми, чтобы повторить';
  $('subWarn').hidden = !s.subWarn;
  $('subWarn').textContent = s.subWarn;

  const play = $('play');
  const key = connecting ? 'conn' : running ? 'on' : 'off';
  if (play.dataset.key !== key) {
    play.dataset.key = key;
    play.innerHTML = connecting ? BOLT : running ? 'ОТКЛЮЧИТЬ' : 'ПОДКЛЮЧИТЬ';
    play.classList.toggle('red', running);
    play.classList.toggle('pulse', connecting);
  }
  $('playHint').textContent = connecting ? 'Подключаюсь…' : running
    ? (s.busy ? 'VPN уже работает — пользуйся. Лучший сервер подбираю в фоне.' : 'VPN работает. Нажми, чтобы отключить.')
    : 'Нажми, чтобы включить VPN на весь компьютер';
  $('speedLine').hidden = !running;
  if (running) $('speedLine').innerHTML = `<span>↓ ${fmtBytes(s.down)} · ↑ ${fmtBytes(s.up)}</span><span>за сессию ${s.sessionMb.toFixed(1)} МБ</span>`;

  FX.setState(connecting, running);
  FX.setSpeed(s.down);
  if (wasRunning !== null && wasRunning !== running && running) {
    FX.connected();
    play.classList.remove('bump'); void play.offsetWidth; play.classList.add('bump');
  }
  wasRunning = running;
  return connecting;
}

async function poll() {
  clearTimeout(pollTimer);
  let fast = false;
  try {
    const s = await api.GetState();
    state = s;
    applyLook(s);
    fast = renderMain(s);
    if (s.toast) toast(s.toast);
    if (screen === 'servers') $('srvBusy').textContent = s.busyText && s.busy ? s.busyText : '';
    if (screen === 'servers' && (s.busy || s.testing)) renderServers();
    if (screen === 'settings') renderUpdate(s.update);
    fast = fast || s.busy || tapAt > 0;
  } catch (e) { console.error(e); }
  pollTimer = setTimeout(poll, fast ? 150 : 700);
}

// ------------------------------ серверы ------------------------------

let lastServers = '';
async function renderServers() {
  const list = await api.GetServers(showHidden);
  const sig = JSON.stringify(list);
  $('autoSel').checked = state ? state.autoSelect : true;
  $('btnHidden').classList.toggle('on', showHidden);
  if (sig === lastServers) return;
  lastServers = sig;
  const box = $('srvList');
  box.innerHTML = '';
  if (!list || !list.length) {
    box.innerHTML = '<div class="card muted">Серверов нет — добавь подписку.</div>';
    return;
  }
  for (const n of list) {
    const r = document.createElement('div');
    if (n.sep) { r.className = 'row sep'; r.textContent = n.name; box.appendChild(r); continue; }
    r.className = 'row' + (n.sel ? ' sel' : '') + (n.hidden ? ' hid' : '');
    const sub = [n.type];
    if (n.exit) sub.push('выход ' + n.exit);
    if (n.speed) sub.push(n.speed.toFixed(0) + ' Мбит/с');
    r.innerHTML = `<div class="flag">${flagHtml(n.country)}</div>
      <div class="rc"><div class="rn"></div><div class="rs">${sub.join(' · ')}</div></div>
      ${n.fav ? '<svg class="star" viewBox="0 0 24 24"><use href="#i-star"/></svg>' : ''}
      <div class="rp" style="color:${n.dead ? 'var(--red)' : pingColor(n.ping)}">${n.dead ? '✖' : n.ping ? n.ping + ' ms' : ''}</div>`;
    r.querySelector('.rn').textContent = n.name;
    r.onclick = async () => {
      await api.SelectServer(n.tag);
      lastServers = '';
      toast(state && state.running ? 'Переключено: ' + n.name : 'Выбран: ' + n.name);
      renderServers();
    };
    r.oncontextmenu = e => {
      e.preventDefault();
      const m = $('menu');
      m.innerHTML = '';
      const add = (label, fn) => { const b = document.createElement('button'); b.textContent = label; b.onclick = async () => { m.hidden = true; await fn(); lastServers = ''; renderServers(); }; m.appendChild(b); };
      add(n.fav ? 'Убрать из избранного' : 'В избранное', () => api.ToggleFavorite(n.tag));
      add(n.hidden ? 'Показать' : 'Скрыть', () => api.ToggleHidden(n.tag));
      m.hidden = false;
      m.style.left = Math.min(e.clientX, innerWidth - 200) + 'px';
      m.style.top = Math.min(e.clientY, innerHeight - 100) + 'px';
    };
    box.appendChild(r);
  }
}
$('autoSel').onchange = e => api.SetAutoSelect(e.target.checked);
$('btnPing').onclick = () => api.MeasureAll();
$('btnExits').onclick = async () => { const m = await api.CheckExits(); if (m) info('Выходы', m); };
$('btnHidden').onclick = () => { showHidden = !showHidden; lastServers = ''; renderServers(); };

// ------------------------------ подписка ------------------------------

async function renderSub() {
  const v = await api.GetSubscription();
  if (!$('subInput').value) $('subInput').value = v.url || '';
  const rows = [`<div><b>Серверов:</b> ${v.servers}</div>`];
  if (v.used) rows.push(`<div><b>Трафик:</b> ${v.used}</div>`);
  if (v.expire) rows.push(`<div><b>Действует до:</b> ${v.expire}</div>`);
  if (v.updated) rows.push(`<div class="muted">Обновлена ${v.updated}</div>`);
  if (v.warning) rows.push(`<div class="warn">${v.warning}</div>`);
  $('subInfo').innerHTML = rows.join('');
}
$('subSave').onclick = async () => {
  const b = $('subSave'); b.disabled = true; b.textContent = 'Загружаю…';
  try { toast(await api.SaveSubscription($('subInput').value)); renderSub(); }
  catch (e) { info('Не получилось', String(e)); }
  finally { b.disabled = false; b.textContent = 'Сохранить'; }
};
$('subRefresh').onclick = async () => {
  const b = $('subRefresh'); b.disabled = true;
  try { toast(await api.RefreshSubscription()); renderSub(); }
  catch (e) { info('Не получилось', String(e)); }
  finally { b.disabled = false; }
};
$('subPaste').onclick = async () => {
  try { $('subInput').value = await navigator.clipboard.readText(); }
  catch (e) { toast('Вставь сочетанием Ctrl+V'); $('subInput').focus(); }
};

// --------------------------- тест скорости ---------------------------

let spTimer = 0;
async function renderSpeed() {
  const s = await api.SpeedState();
  $('spPing').textContent = s.ping ? s.ping + ' ms' : '—';
  $('spJit').textContent = s.ping ? s.jitter + ' ms' : '—';
  $('spDown').textContent = s.down ? s.down.toFixed(1) + ' Мбит/с' : '—';
  $('spUp').textContent = s.up ? s.up.toFixed(1) + ' Мбит/с' : '—';
  const phase = { ping: 'Меряю пинг…', down: 'Меряю загрузку…', up: 'Меряю отдачу…' }[s.phase];
  $('spNote').textContent = s.error || (s.running ? phase : (s.ping ? (s.viaVpn ? 'Замер через VPN' : 'Замер без VPN') : 'Пинг до ближайшего узла Cloudflare, загрузка и отдача в 4 потока'));
  $('spStart').disabled = s.running;
  $('spStart').textContent = s.running ? 'Идёт замер…' : (s.ping ? 'Ещё раз' : 'Начать');
  clearTimeout(spTimer);
  if (s.running && screen === 'speed') spTimer = setTimeout(renderSpeed, 250);
}
$('spStart').onclick = async () => { await api.SpeedTest(); renderSpeed(); };

// ------------------------------ настройки ------------------------------

async function renderSettings() {
  const s = await api.GetSettings();
  const th = $('themes'); th.innerHTML = '';
  for (const t of THEMES) {
    const b = document.createElement('button');
    b.className = 'theme' + (s.theme === t.id ? ' sel' : '');
    b.innerHTML = `<div class="pv" style="background:${t.bg}"><i style="background:${t.card}"></i><u style="background:linear-gradient(135deg,${t.a},${t.b})"></u></div><span>${t.title}</span>`;
    b.onclick = async () => { await api.SetOption('theme', t.id); await poll(); renderSettings(); };
    th.appendChild(b);
  }
  const an = $('anims'); an.innerHTML = '';
  for (const a of ANIMS) {
    const b = document.createElement('button');
    b.className = 'anim' + (s.anim === a.id ? ' sel' : '');
    b.innerHTML = `<b>${a.title}</b><span class="muted">${a.sub}</span>`;
    b.onclick = async () => { await api.SetOption('anim', a.id); await poll(); renderSettings(); toast('Посмотри на главном — нажми «Подключить»'); };
    an.appendChild(b);
  }
  for (const el of document.querySelectorAll('[data-opt]')) el.checked = !!s[el.dataset.opt];
  $('footVer').textContent = `Fast VPN · BuninSil · версия ${state ? state.version : ''}`;
  if (state) renderUpdate(state.update);
}
for (const el of document.querySelectorAll('[data-opt]')) {
  el.onchange = async () => {
    const err = await api.SetOption(el.dataset.opt, el.checked);
    if (err) { el.checked = !el.checked; info('Не получилось', err); }
    else if ((el.dataset.opt === 'ruDirect' || el.dataset.opt === 'adBlock') && state && state.running) toast('Переподключаю, чтобы применить…');
  };
}
function renderUpdate(u) {
  if (!u) return;
  $('updStatus').textContent = u.progress >= 0 && u.progress < 100 ? `Скачиваю обновление: ${u.progress}%` : (u.status || '');
  $('updInstall').hidden = !u.available;
  $('updNotes').hidden = !u.available;
  if (u.available) {
    $('updNotes').textContent = 'Что нового в ' + u.available.version + ':\n' + u.available.notes;
    $('updInstall').textContent = 'Обновить до ' + u.available.version;
  }
  $('updCheck').disabled = u.checking;
}
$('updCheck').onclick = async () => { await api.CheckUpdate(); poll(); };
$('updInstall').onclick = () => dialog('Обновить Fast VPN?', 'Приложение закроется, поставит новую версию и откроется само. VPN на пару секунд отключится.',
  [['Отмена'], ['Обновить', async () => { const m = await api.InstallUpdate(); if (m) info('Обновление', m); }]]);
$('bkSave').onclick = async () => { const m = await api.ExportBackup(); if (m) toast(m); };
$('bkLoad').onclick = async () => {
  const b = await api.PickBackup();
  if (!b) return;
  if (b.error) { info('Не получилось', b.error); return; }
  dialog('Загрузить настройки?',
    (b.created ? `Сохранены: ${b.created}\n` : '') + (b.app ? `Версия: ${b.app}\n` : '') +
    (b.servers ? `Серверов в подписке: ${b.servers}` : 'Подписки в файле нет') + '\n\nТекущие настройки и подписка заменятся, VPN отключится.',
    [['Отмена'], ['Загрузить', async () => { const m = await api.ImportBackup(b.path); if (m) info('Ошибка', m); else { toast('Настройки перенесены'); renderSettings(); } }]]);
};
$('report').onclick = async () => info('Отчёт готов', await api.Report());

// ------------------------------- старт -------------------------------

FX.setAnchor($('play'));
go('main');
poll();
