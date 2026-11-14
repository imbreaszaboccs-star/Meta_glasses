'use strict';
/* FocusLens glasses web app (Meta Ray-Ban Display). Remote + viewer; phone does the sensing.
 *
 * Key mapping (KeyboardEvent.key), confirmed from
 * https://wearables.developer.meta.com/docs/develop/webapps/build/ :
 *   ArrowUp / ArrowDown  - move focus      (Neural Band swipe / touchpad)
 *   ArrowLeft / ArrowRight - unused (reserved)
 *   Enter                - select / activate (pinch)
 *   Escape, Backspace    - back (shell calls history.back(); at root the native menu / exit)
 * Sub-screens push a history entry (max 5 allowed by platform) and go back via popstate.
 * Text composer: opens only on focus + user activation, so the Goal row focuses a hidden
 * <input> inside the Enter keydown handler; the value arrives via input/change events.
 * Dev: add ?mock=1 to run without network (arrows / Enter / Escape in a desktop browser).
 */

const KEY = { UP: 'ArrowUp', DOWN: 'ArrowDown', OK: 'Enter', BACK: ['Escape', 'Backspace'] };
const MODES = ['auto', 'lecture', 'homework', 'desk'];
const FAST_MS = 1000, POLL_MS = 3000, SLOW_MS = 5000, TIMEOUT_MS = 8000, VISIBLE = 5;
const MOCK = new URLSearchParams(location.search).has('mock');

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const lsGet = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };
const lsSet = (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) { /* ignore */ } };
const str = (v, d = '') => (typeof v === 'string' ? v : d);
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const trunc = (s, n) => (s.length > n ? s.slice(0, n - 1) + '…' : s);

// ---------- app state ----------
let token = lsGet('fl_web_token');
let S = null;              // last STATE from phone
let phoneOnline = false, netError = false, stateAge = null;
let stack = ['home'];      // screen stack
let ctx = null;            // context for pushed screens (card id / inbox item)
const sel = {};            // selected row per screen
let goal = lsGet('fl_goal') || '';
let rv = { idx: 0, answer: false, requested: false };
const pending = new Map(); // command id -> label
let toastTimer = null, pollTimer = null, failures = 0, lastAction = 0;

// ---------- network ----------
async function api(path, opts = {}) {
  if (MOCK) return mockApi(path, opts);
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    const headers = { 'Content-Type': 'application/json' };
    if (token && !opts.noAuth) headers.Authorization = 'Bearer ' + token;
    const res = await fetch(path, { method: opts.method || 'GET', headers, body: opts.body ? JSON.stringify(opts.body) : undefined, signal: ctl.signal });
    let data = null;
    try { data = await res.json(); } catch (e) { /* no body */ }
    if (res.status === 401 && !opts.noAuth) { forgetToken(); }
    return { status: res.status, data };
  } finally { clearTimeout(t); }
}

function forgetToken() { token = null; lsSet('fl_web_token', null); S = null; stack = ['home']; render(); }

async function claim(code) {
  code = str(code).trim().toUpperCase();
  if (code.length !== 8) { toast('The code has 8 characters'); return; }
  try {
    const r = await api('/api/r/claim', { method: 'POST', body: { code }, noAuth: true });
    if (r.status === 200 && r.data && r.data.web_token) { token = r.data.web_token; lsSet('fl_web_token', token); toast('✓ Paired'); }
    else if (r.status === 429) toast('Slow down, try again');
    else toast('Code invalid or expired');
  } catch (e) { toast('Network error'); }
  render(); poll();
}

async function claimFromUrl() {
  const u = new URL(location.href);
  const code = u.searchParams.get('code');
  if (!code || MOCK) return;
  // Drop the one-time code from the address first, so it never lingers in history.
  u.searchParams.delete('code');
  try { history.replaceState(null, '', u.pathname + u.search + u.hash); } catch (e) { /* ignore */ }
  await claim(code);
}

// Fallback when the install link loses ?code=: type the 8-char code shown on the phone.
$('codeInput').addEventListener('change', (e) => { const v = e.target.value; e.target.value = ''; e.target.blur(); claim(v); });
$('codeInput').addEventListener('keydown', (e) => { if (e.key === KEY.OK) e.target.blur(); e.stopPropagation(); });

async function poll() {
  clearTimeout(pollTimer);
  if (token && document.visibilityState === 'visible') {
    try {
      const r = await api('/api/r/state');
      if (r.status === 200 && r.data) { applyState(r.data); failures = 0; netError = false; }
      else if (r.status !== 401) { failures++; netError = true; }
    } catch (e) { failures++; netError = true; }
    render();
  }
  // Adaptive polling (Upstash/Vercel budget, V2_DIRECTION.md §2): fast right after input or while the phone thinks.
  const fast = Date.now() - lastAction < 10000 || (hud() && hud().s === 'thinking');
  pollTimer = setTimeout(poll, failures ? SLOW_MS : fast ? FAST_MS : POLL_MS);
}

function applyState(d) {
  phoneOnline = !!d.phone_online;
  stateAge = typeof d.state_age_s === 'number' ? d.state_age_s : null;
  S = d.state && typeof d.state === 'object' ? d.state : null;
  for (const a of Array.isArray(d.acks) ? d.acks : []) {
    if (!a || !pending.has(a.id)) continue;
    if (pending.get(a.id) == null) { pending.delete(a.id); if (!a.ok) toast('✗ ' + str(a.error, 'failed')); continue; }
    const label = pending.get(a.id); pending.delete(a.id);
    toast(a.ok ? '✓ ' + label : '✗ ' + str(a.error, 'failed'));
  }
}

async function send(type, args, label) {
  lastAction = Date.now();
  try {
    const r = await api('/api/r/cmd', { method: 'POST', body: { type, args: args || {} } });
    if (r.status === 202 && r.data && r.data.id != null) { pending.set(r.data.id, label === null ? null : label || type); setTimeout(() => pending.delete(r.data.id), 60000); }
    else if (r.status === 409) toast('Phone offline');
    else if (r.status === 422) toast('Not allowed');
    else if (r.status !== 401) toast('Error ' + r.status);
  } catch (e) { toast('Network error'); }
  clearTimeout(pollTimer); pollTimer = setTimeout(poll, 400);
}

// ---------- helpers on state ----------
const focus = () => (S && S.focus) || {};
const config = () => (S && S.config) || {};
const running = () => !!focus().running;
const inbox = () => (S && Array.isArray(S.inbox) ? S.inbox : []);
const todayCards = () => {
  const now = new Date(), all = S && Array.isArray(S.cards) ? S.cards : [];
  const same = (c) => { const d = new Date(c.at); return !isNaN(d) && d.toDateString() === now.toDateString(); };
  return all.filter(same).sort((a, b) => new Date(b.at) - new Date(a.at));
};
const openInbox = () => inbox().filter((i) => !i.done);
const sortedInbox = () => inbox().slice().sort((a, b) => (a.done === b.done ? 0 : a.done ? 1 : -1));
const hhmm = (iso) => { const d = new Date(iso); return isNaN(d) ? '' : d.toTimeString().slice(0, 5); };
const review = () => (S && S.review) || { status: 'idle', cards: [], error: null };

function setCfg(patch, label) {
  if (S) S.config = Object.assign({}, S.config, patch);
  render(); send('set_config', patch, label);
}

// ---------- screens: each returns {title, status?, rows?, body?, footer, top?} ----------
function homeScreen() {
  const f = focus(), c = config(), rows = [];
  if (running()) {
    rows.push({ l: '■ Stop focus', act: () => send('stop_focus', {}, 'Stopped') });
    rows.push({ l: 'Help now', act: () => send('help', {}, 'Help sent') });
    rows.push(f.micPaused ? { l: 'Resume mic', act: () => send('resume_mic', {}, 'Mic on') } : { l: 'Pause mic', act: () => send('pause_mic', {}, 'Mic paused') });
  } else {
    rows.push({ l: '▶ Start focus', act: () => {
      const args = { mode: str(c.mode, 'auto') }; if (goal) args.goal = goal.slice(0, 200);
      send('start_focus', args, 'Started');
    } });
  }
  rows.push({ l: 'Mode: ' + cap(str(c.mode, 'auto')), act: () => {
    const i = MODES.indexOf(str(c.mode, 'auto'));
    setCfg({ mode: MODES[(i + 1) % MODES.length] }, 'Mode set');
  } });
  rows.push({ l: 'Goal: ' + (goal ? trunc(goal, 22) : 'none'), act: openComposer });
  rows.push({ l: 'Blocks today', r: String(todayCards().length), act: () => go('blocks') });
  rows.push({ l: 'Inbox', r: openInbox().length + ' open', act: () => go('inbox') });
  rows.push({ l: 'Evening review', act: () => { rv = { idx: 0, answer: false, requested: true }; send('review_load', {}, 'Loading'); go('review'); } });
  rows.push({ l: 'Settings', act: () => go('settings') });

  const g = S && S.glasses, parts = [phoneOnline ? 'Phone ●' : 'Phone offline'];
  if (g && typeof g.battery === 'number') parts.push('🔋' + g.battery + '%');
  parts.push(running() ? 'Focus on' : 'Focus off');
  let top = null;
  if (running()) {
    const mins = typeof f.startedAt === 'number' ? Math.max(0, Math.floor((Date.now() - f.startedAt) / 60000)) : 0;
    const line = [cap(str(f.mode, 'auto')), f.goal ? '⚓ ' + trunc(str(f.goal), 24) : null, mins + ' min', (f.hintCount | 0) + ' hints'].filter(Boolean).join(' · ');
    top = [el('div', 'live', line)];
    if (f.lastHint) top.push(el('div', 'hint', str(f.lastHint)));
  }
  return { title: 'FocusLens', status: parts.join(' · '), rows, top, footer: '↑↓ move · Pinch select · Back exit' };
}

function settingsScreen() {
  const c = config();
  const tog = (k, name) => ({ l: name, r: c[k] ? 'ON' : 'OFF', act: () => setCfg({ [k]: !c[k] }, name + (c[k] ? ' off' : ' on')) });
  return { title: 'Settings', rows: [tog('nudges', 'Nudges'), tog('wakeWord', 'Wake word "hey lens"'), tog('readAloud', 'Read aloud')], footer: '↑↓ move · Pinch toggle · Back' };
}

function blocksScreen() {
  const cards = todayCards();
  return { title: 'Blocks today', rows: cards.map((c) => ({ l: trunc(str(c.title, 'Block'), 26), r: hhmm(c.at), act: () => { ctx = c.id; go('detail'); } })),
    empty: 'No blocks yet today.', footer: '↑↓ move · Pinch open · Back' };
}

function detailScreen() {
  const c = todayCards().concat(S && Array.isArray(S.cards) ? S.cards : []).find((x) => x.id === ctx);
  const body = el('div', 'text');
  if (!c) body.appendChild(el('div', 'big', 'Block not found'));
  else {
    body.appendChild(el('div', 'big', trunc(str(c.title, 'Block'), 60)));
    if (c.step) body.appendChild(el('div', 'muted', 'Step: ' + trunc(str(c.step), 80)));
    for (const s of (Array.isArray(c.steps) ? c.steps : []).slice(0, 6)) body.appendChild(el('div', 's' + (s.done ? ' done' : ''), (s.done ? '✓ ' : '○ ') + trunc(str(s.text), 50)));
    if (c.correction) body.appendChild(el('p', 'muted', str(c.correction)));
  }
  return { title: 'Block', body, footer: 'Back to list' };
}

function inboxScreen() {
  const items = sortedInbox();
  return { title: 'Inbox', rows: items.map((i) => ({ l: (i.done ? '✓ ' : '○ ') + trunc(str(i.text), 28), dim: !!i.done, act: () => { ctx = i.id; go('sheet'); } })),
    empty: 'Swipe down on the glasses during focus to note something.', footer: '↑↓ move · Pinch actions · Back' };
}

function sheetScreen() {
  const it = inbox().find((i) => i.id === ctx);
  if (!it) return { title: 'Inbox', body: el('div', 'text', 'Item gone.'), footer: 'Back' };
  return { title: trunc(str(it.text), 28), rows: [
    { l: it.done ? 'Undo' : 'Done', act: () => { send('inbox_done', { id: it.id, done: !it.done }, it.done ? 'Reopened' : 'Done'); it.done = !it.done; back(); } },
    { l: 'Delete', act: () => { send('inbox_delete', { id: it.id }, 'Deleted'); if (S) S.inbox = inbox().filter((x) => x.id !== it.id); back(); } },
    { l: 'Cancel', act: back } ], footer: '↑↓ move · Pinch select · Back' };
}

function reviewScreen() {
  const R = review(), body = el('div', 'text');
  let footer = 'Back', rows = null;
  if (R.status === 'loading' || (R.status === 'idle' && rv.requested)) body.appendChild(el('div', 'big', 'Preparing cards…'));
  else if (R.status === 'error') {
    body.appendChild(el('div', 'big', 'Could not prepare cards'));
    body.appendChild(el('p', 'muted', str(R.error, 'Unknown error')));
    rows = [{ l: 'Try again', act: () => { send('review_load', {}, 'Loading'); } }]; footer = 'Pinch retry · Back';
  } else {
    const cards = Array.isArray(R.cards) ? R.cards : [];
    if (!cards.length) body.appendChild(el('div', 'big', 'Nothing to review'));
    else if (rv.idx >= cards.length) body.appendChild(el('div', 'big', 'Done for today ✓'));
    else {
      const c = cards[rv.idx] || {};
      body.appendChild(el('div', 'muted', (rv.idx + 1) + '/' + cards.length));
      body.appendChild(el('div', 'big', str(c.q, '?')));
      if (rv.answer) body.appendChild(el('p', '', str(c.a, '')));
      footer = rv.answer ? 'Pinch next · Back' : 'Pinch show answer · Back';
    }
  }
  return { title: 'Evening review', body, rows, footer };
}

const SCREENS = { home: homeScreen, settings: settingsScreen, blocks: blocksScreen, detail: detailScreen, inbox: inboxScreen, sheet: sheetScreen, review: reviewScreen };
const cur = () => stack[stack.length - 1];

// ---------- navigation ----------
function go(name) { stack.push(name); try { history.pushState({ d: stack.length }, ''); } catch (e) { /* ignore */ } render(); }
function back() {
  if (stack.length <= 1) return;
  try { history.back(); } catch (e) { popStack(); }
}
function popStack() { if (stack.length > 1) { stack.pop(); render(); } }
window.addEventListener('popstate', popStack);

function openComposer() {
  const inp = $('goalInput');
  inp.value = goal;
  try { inp.focus(); } catch (e) { /* ignore */ }
}
$('goalInput').addEventListener('change', (e) => {
  goal = e.target.value.trim().slice(0, 200); lsSet('fl_goal', goal || null); e.target.blur(); render();
});
$('goalInput').addEventListener('keydown', (e) => { if (e.key === KEY.OK) e.target.blur(); e.stopPropagation(); });

// ---------- HUD (view model published by the phone; FocusEngine decides what to show) ----------
// hud = { s: idle|thinking|hint|quest|blocks|detail|message|nudge, anchor?, title?, text?, sub?, formula?, crop?,
//         progress?, rows?: [{title, time}], selected?, footer?, attention? }
const hud = () => (S && S.hud && typeof S.hud === 'object' ? S.hud : null);
const hudActive = () => !!token && running() && !!hud() && stack.length === 1;

function formulaEl(tex) {
  const d = el('div', 'fx');
  try { katex.render(tex, d, { throwOnError: true, displayMode: true, output: 'html' }); } catch (e) { d.textContent = tex; }
  return d;
}

function renderHud() {
  const box = $('hud');
  if (!hudActive()) { box.hidden = true; return false; }
  const h = hud(), pics = !!(h.crop || h.formula);
  box.hidden = false; box.className = h.s === 'idle' ? 'idle' : ''; box.textContent = '';
  if (h.attention) box.appendChild(el('div', 'dot')); // L1 nudge: quiet dot, no sound
  if (h.anchor) box.appendChild(el('div', 'anchor', str(h.anchor)));
  if (h.title) box.appendChild(el('div', h.s === 'idle' ? 'meta' : 'h', str(h.title)));
  if (h.text) {
    const short = str(h.text).trim().split(/\s+/).length <= 12 && !pics;
    box.appendChild(el('div', short ? 'h' : 'b', str(h.text)));
  }
  if (h.sub) box.appendChild(el('div', 'sub', str(h.sub)));
  if (typeof h.crop === 'string' && /^[0-9a-f]{64}$/.test(h.crop)) { const i = el('img'); i.alt = ''; i.src = '/api/r/img/' + h.crop; box.appendChild(i); }
  if (h.formula) box.appendChild(formulaEl(str(h.formula)));
  if (Array.isArray(h.rows)) {
    const sel = h.selected | 0, start = Math.min(Math.max(0, sel - 1), Math.max(0, h.rows.length - 4));
    h.rows.slice(start, start + 4).forEach((r, k) => {
      const row = el('div', 'row' + (start + k === sel ? ' sel' : ''));
      row.appendChild(el('div', 'l', str(r && r.title))); row.appendChild(el('div', 'r', str(r && r.time)));
      box.appendChild(row);
    });
  }
  if (h.s !== 'idle') box.appendChild(el('div', 'grow'));
  if (h.progress) box.appendChild(el('div', 'meta', str(h.progress)));
  if (h.footer) box.appendChild(el('div', 'meta', str(h.footer)));
  $('banner').hidden = !(netError || !phoneOnline);
  return true;
}

// ---------- rendering ----------
let last = { rows: [] };
function render() {
  if (!token && !MOCK) return renderPairing();
  if (renderHud()) return;
  const name = cur();
  let sc = (SCREENS[name] || homeScreen)();
  last = sc;
  const key = name;
  const n = sc.rows ? sc.rows.length : 0;
  sel[key] = Math.min(Math.max(sel[key] | 0, 0), Math.max(0, n - 1));
  $('title').textContent = sc.title;
  $('status').textContent = sc.status || '';
  const body = $('body'); body.textContent = '';
  if (sc.top) sc.top.forEach((e) => body.appendChild(e));
  if (sc.body) body.appendChild(sc.body);
  if (sc.rows && sc.rows.length) {
    const max = sc.top ? VISIBLE - 1 : VISIBLE, i = sel[key];
    const start = Math.min(Math.max(0, i - Math.floor(max / 2)), Math.max(0, n - max));
    sc.rows.slice(start, start + max).forEach((r, k) => {
      const row = el('div', 'row' + (start + k === i ? ' sel' : '') + (r.dim ? ' dim' : ''));
      row.appendChild(el('div', 'l', r.l));
      if (r.r) row.appendChild(el('div', 'r', r.r));
      body.appendChild(row);
    });
  } else if (sc.empty) body.appendChild(el('div', 'text', sc.empty));
  $('footer').textContent = sc.footer || '';
  $('banner').hidden = !(netError || !phoneOnline);
  $('banner').textContent = netError ? 'offline' : 'phone offline';
}

function renderPairing() {
  $('title').textContent = 'FocusLens'; $('status').textContent = ''; $('banner').hidden = true;
  const b = $('body'); b.textContent = '';
  const t = el('div', 'text'); t.appendChild(el('div', 'big', 'Not paired'));
  t.appendChild(el('p', '', 'Open FocusLens on your phone → Settings → Add to glasses menu.'));
  t.appendChild(el('p', '', 'Got a code? Pinch to type it.'));
  b.appendChild(t); $('footer').textContent = 'Pinch type code · Back exit';
}

function toast(msg) {
  const t = $('toast'); t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, 2500);
}

// ---------- input ----------
document.addEventListener('keydown', (e) => {
  if (e.target === $('goalInput') || e.target === $('codeInput')) return;
  const k = e.key;
  if (hudActive()) {
    const g = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'select', Escape: 'back', Backspace: 'back' }[k];
    if (!g) return;
    e.preventDefault();
    if (g === 'back' && hud().s === 'idle') { go('home'); return; } // HUD idle: Back opens the menu
    send('gesture', { g }, null);
    return;
  }
  if (KEY.BACK.includes(k)) {
    if (stack.length > 1) { e.preventDefault(); back(); }
    return; // at root: let the shell show the native menu / exit
  }
  if (!token && !MOCK) {
    // The composer opens only on focus during user activation, i.e. inside this keydown.
    if (k === KEY.OK) { e.preventDefault(); $('codeInput').focus(); }
    return;
  }
  const rows = last.rows || [], key = cur();
  if (k === KEY.UP || k === KEY.DOWN) {
    e.preventDefault();
    if (rows.length) { sel[key] = ((sel[key] | 0) + (k === KEY.DOWN ? 1 : rows.length - 1)) % rows.length; render(); }
  } else if (k === KEY.OK) {
    e.preventDefault();
    if (key === 'review' && !rows.length) reviewStep();
    else if (rows[sel[key] | 0]) rows[sel[key] | 0].act();
  }
});

function reviewStep() {
  const cards = review().cards || [];
  if (review().status !== 'ready' || !cards.length || rv.idx >= cards.length) return;
  if (!rv.answer) rv.answer = true; else { rv.answer = false; rv.idx++; }
  render();
}

document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') poll(); });

// ---------- mock (dev) ----------
let mock = null, mockAcks = [], mockSeq = 0;
function mockInit() {
  const now = Date.now(), iso = (m) => new Date(now - m * 60000).toISOString();
  mock = { v: 1,
    focus: { running: false, mode: 'auto', goal: '', startedAt: null, hintCount: 0, micPaused: false, phase: 'idle', lastHint: null, audioSource: 'phone' },
    glasses: { connected: true, name: 'Ray-Ban Display', battery: 72, displayActive: true },
    config: { mode: 'auto', nudges: true, wakeWord: false, readAloud: false, language: 'en' },
    inbox: [{ id: 'i1', text: 'Email Anna about the lab report', done: false, createdAt: iso(30), synced: true }, { id: 'i2', text: 'Buy milk', done: true, createdAt: iso(90), synced: true }, { id: 'i3', text: 'Check bus time', done: false, createdAt: iso(10), synced: true }],
    cards: [{ id: 'c1', title: 'Linear algebra: eigenvalues', step: 'Compute det(A - λI)', situation: 'Lecture', at: iso(20), correction: 'Remember the characteristic polynomial is degree n, so expect up to n roots counted with multiplicity.', steps: [{ text: 'Write A - λI', done: true }, { text: 'Take the determinant', done: false }, { text: 'Solve for λ', done: false }] },
      { id: 'c2', title: 'Essay outline', step: 'Draft thesis', situation: 'Homework', at: iso(120), correction: '', steps: [] }],
    review: { status: 'idle', cards: [], error: null },
    hud: { s: 'idle', title: '● FocusLens · pinch = help' } };
}
function mockApi(path, opts) {
  if (!mock) mockInit();
  if (path.endsWith('/state')) { const acks = mockAcks; mockAcks = []; return Promise.resolve({ status: 200, data: { phone_online: true, state_age_s: 0.5, state: JSON.parse(JSON.stringify(mock)), acks } }); }
  const { type, args } = opts.body, id = 'm' + (++mockSeq), f = mock.focus;
  if (type === 'gesture') mockGesture(args.g);
  else if (type === 'start_focus') Object.assign(f, { running: true, mode: args.mode || 'auto', goal: args.goal || '', startedAt: Date.now() - 12 * 60000, hintCount: 3, lastHint: 'Try the next micro-step: take the determinant of the 2x2 matrix.' });
  else if (type === 'stop_focus') Object.assign(f, { running: false, lastHint: null });
  else if (type === 'pause_mic') f.micPaused = true;
  else if (type === 'resume_mic') f.micPaused = false;
  else if (type === 'set_config') Object.assign(mock.config, args);
  else if (type === 'inbox_done') { const i = mock.inbox.find((x) => x.id === args.id); if (i) i.done = args.done; }
  else if (type === 'inbox_delete') mock.inbox = mock.inbox.filter((x) => x.id !== args.id);
  else if (type === 'review_load') {
    mock.review = { status: 'loading', cards: [], error: null };
    setTimeout(() => { mock.review = { status: 'ready', error: null, cards: [{ q: 'What is an eigenvector?', a: 'A vector whose direction is unchanged by A; Av = λv.' }, { q: 'How to find eigenvalues?', a: 'Solve det(A - λI) = 0.' }] }; }, 1500);
  }
  mockAcks.push({ id, ok: true, error: null });
  return Promise.resolve({ status: 202, data: { id } });
}

function mockGesture(g) {
  const h = mock.hud;
  if (h.s === 'idle' && g === 'select') {
    mock.hud = { s: 'thinking', title: 'Looking…' };
    setTimeout(() => { mock.hud = { s: 'hint', anchor: '⚓ ' + (mock.focus.goal || 'Eigenvalues'), text: 'Set det(A − λI) = 0 and solve for λ.', formula: '\det(A-\lambda I)=0', progress: '1/1', footer: '→ next · ← back' }; }, 1500);
  } else if (h.s === 'idle' && g === 'up') mock.hud = { s: 'blocks', rows: [{ title: 'Eigenvalues', time: '10:12' }, { title: 'Essay outline', time: '09:03' }], selected: 0, footer: 'pinch open · ← close' };
  else if (g === 'back' || g === 'left') mock.hud = { s: 'idle', title: '● FocusLens · pinch = help' };
  else if (h.s === 'blocks' && g === 'down') h.selected = Math.max(0, h.selected - 1);
  else if (h.s === 'blocks' && g === 'up') h.selected = Math.min(h.rows.length - 1, h.selected + 1);
}

// ---------- boot ----------
(async function boot() {
  if (MOCK) mockInit();
  await claimFromUrl();
  render();
  poll();
})();
