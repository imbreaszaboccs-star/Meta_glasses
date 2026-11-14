'use strict';
/* FocusLens glasses web app (Meta Ray-Ban Display, 600x600 additive display). Remote + viewer; phone does the sensing.
 *
 * Input (KeyboardEvent.key): ArrowUp/Down move the focused row, ArrowLeft/Right switch pages, Enter (pinch) activates,
 * Escape/Backspace = Back. Sub-screens push a history entry and Back pops it (popstate). At a page root Back is NOT
 * intercepted, so the system shows its native menu. Key repeats of Back belong to the system and are ignored.
 * Text composer: opens only on focus + user activation, so rows focus a hidden <input> inside the Enter keydown.
 * Dev: ?mock=1 runs without network. Screenshot params: page=, run=1, hud=idle|hint|quest|nudge, detail=<id>, banner=start|stop.
 */

const PAGES = ['focus', 'inbox', 'cards', 'review', 'settings'];
const MODES = ['auto', 'lecture', 'homework', 'desk'];
const LANGS = [['en', 'English'], ['cs', 'Čeština'], ['sk', 'Slovenčina'], ['de', 'Deutsch'], ['pl', 'Polski']];
const ACTS = ['board', 'notes', 'switching', 'still', 'looking_around', 'nod', 'walking', 'none'];
const FAST_MS = 1000, POLL_MS = 3000, SLOW_MS = 5000, TIMEOUT_MS = 8000;
const P = new URLSearchParams(location.search);
const MOCK = P.has('mock');
const BACKK = ['Escape', 'Backspace'];

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const lsGet = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };
const lsSet = (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) { /* ignore */ } };
const str = (v, d = '') => (typeof v === 'string' ? v : d);
const cap = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);
const trunc = (s, n) => (s.length > n ? s.slice(0, n - 1) + '…' : s);
const pad2 = (n) => String(n).padStart(2, '0');

// ---------- app state ----------
let token = lsGet('fl_web_token');
let S = null, stateSig = '';
let phoneOnline = false, netError = false;
let pg = 0;                 // current top-level page index
let nav = [];               // pushed history entries: {t:'hud'} | {t:'sub', name, id}
const sel = {};             // focused row per screen key
let goal = lsGet('fl_goal') || '';
let compose = 'goal';       // what the hidden composer is for: goal | note
let rv = { idx: 0, answer: false, requested: false };
const pending = new Map();  // command id -> {label, ok}
let pollTimer = null, failures = 0, lastAction = 0;
let prevRunning = null, lastRun = { startedAt: null, hintCount: 0, mode: 'auto', goal: '' };
let armed = null, armTimer = null;
let last = { rows: [] };
let hudWas = false, hudSig = '', hintTimer = null, bannerTimer = null;

const paired = () => !!token || MOCK;

// ---------- network ----------
async function api(path, opts = {}) {
  if (MOCK) return mockApi(path, opts);
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    const headers = { 'Content-Type': 'application/json' };
    if (token && !opts.noAuth) headers.Authorization = 'Bearer ' + token;
    const res = await fetch(path, { method: opts.method || 'GET', headers, body: opts.body ? JSON.stringify(opts.body) : undefined, signal: ctl.signal, keepalive: !!opts.keepalive });
    let data = null;
    try { data = await res.json(); } catch (e) { /* no body */ }
    if (res.status === 401 && !opts.noAuth) forgetToken();
    return { status: res.status, data };
  } finally { clearTimeout(t); }
}

function forgetToken() { token = null; lsSet('fl_web_token', null); S = null; stateSig = ''; nav = []; render(); }

async function claim(code) {
  code = str(code).trim().toUpperCase();
  if (code.length !== 8) { banner('●', 'The code has 8 characters', '', 'warn'); return; }
  try {
    const r = await api('/api/r/claim', { method: 'POST', body: { code }, noAuth: true });
    if (r.status === 200 && r.data && r.data.web_token) { token = r.data.web_token; lsSet('fl_web_token', token); banner('✓', 'Paired'); }
    else if (r.status === 429) banner('●', 'Slow down, try again', '', 'warn');
    else banner('●', 'Code invalid or expired', '', 'err');
  } catch (e) { banner('●', 'Network error', '', 'err'); }
  render(); poll();
}

async function claimFromUrl() {
  const u = new URL(location.href);
  const code = u.searchParams.get('code');
  if (!code || MOCK) return;
  u.searchParams.delete('code'); // never leave the one-time code in history
  try { history.replaceState(null, '', u.pathname + u.search + u.hash); } catch (e) { /* ignore */ }
  await claim(code);
}

$('codeInput').addEventListener('change', (e) => { const v = e.target.value; e.target.value = ''; e.target.blur(); claim(v); });
$('codeInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') e.target.blur(); e.stopPropagation(); });

async function fetchState() {
  if (!token && !MOCK) return;
  try {
    const r = await api('/api/r/state');
    if (r.status === 200 && r.data) { applyState(r.data); failures = 0; netError = false; }
    else if (r.status !== 401) { failures++; netError = true; }
  } catch (e) { failures++; netError = true; }
}

async function poll() {
  clearTimeout(pollTimer);
  if (document.visibilityState === 'visible') {
    const before = stateSig + '|' + netError + phoneOnline;
    await fetchState();
    if (before !== stateSig + '|' + netError + phoneOnline) render(); // skip identical frames (keeps the HUD image from reloading)
  }
  // Adaptive polling (Upstash/Vercel budget): fast right after input or while the phone thinks.
  const fast = Date.now() - lastAction < 10000 || (hud() && hud().s === 'thinking');
  pollTimer = setTimeout(poll, failures ? SLOW_MS : fast ? FAST_MS : POLL_MS);
}

function applyState(d) {
  phoneOnline = !!d.phone_online;
  S = d.state && typeof d.state === 'object' ? d.state : null;
  stateSig = JSON.stringify(S);
  for (const a of Array.isArray(d.acks) ? d.acks : []) {
    const p = a && pending.get(a.id);
    if (!p) continue;
    pending.delete(a.id);
    if (!a.ok) banner('●', str(a.error, 'Failed'), '', 'err');
    else if (p.label) banner('✓', p.label);
  }
  const r = running();
  if (r) lastRun = { startedAt: focus().startedAt, hintCount: focus().hintCount | 0, mode: str(focus().mode, 'auto'), goal: str(focus().goal) };
  if (prevRunning !== null && r !== prevRunning) {
    if (r) banner('●', 'Session active', cap(lastRun.mode) + (lastRun.goal ? ' · ' + trunc(lastRun.goal, 30) : ''));
    else {
      const mins = typeof lastRun.startedAt === 'number' ? Math.max(0, Math.round((Date.now() - lastRun.startedAt) / 60000)) : 0;
      banner('✓', 'Session ended', mins + ' min · ' + lastRun.hintCount + ' hints');
    }
    unwindMenu();
  }
  prevRunning = r;
}

// silent: no pending entry, no banner, does not extend the fast-poll window
function seen(visible, keepalive) {
  if (!token || MOCK) return;
  api('/api/r/cmd', { method: 'POST', body: { type: 'web_seen', args: { visible } }, keepalive: !!keepalive }).catch(() => {});
}
setInterval(() => { if (document.visibilityState === 'visible' && token && phoneOnline) seen(true); }, 20000);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') { poll(); if (phoneOnline) seen(true); } else seen(false, true);
});

// label: undefined = command name, null = silent on success, string = banner on success; okBanner false = no banner on success
async function send(type, args, label, okBanner) {
  lastAction = Date.now();
  if (type === 'start_focus') banner('○', 'Starting…');
  else if (type === 'stop_focus') banner('○', 'Stopping…');
  try {
    const r = await api('/api/r/cmd', { method: 'POST', body: { type, args: args || {} } });
    if (r.status === 202 && r.data && r.data.id != null) {
      pending.set(r.data.id, { label: okBanner === false || label === null ? null : label || type });
      setTimeout(() => pending.delete(r.data.id), 60000);
    } else if (r.status === 409) banner('●', 'Phone offline', '', 'err');
    else if (r.status === 422) banner('●', 'Not allowed', '', 'err');
    else if (r.status !== 401) banner('●', 'Error ' + r.status, '', 'err');
  } catch (e) { banner('●', 'Network error', '', 'err'); }
  clearTimeout(pollTimer); pollTimer = setTimeout(poll, 400);
}

// ---------- state helpers ----------
const focus = () => (S && S.focus) || {};
const config = () => (S && S.config) || {};
const running = () => !!focus().running;
const inbox = () => (S && Array.isArray(S.inbox) ? S.inbox : []);
const cards = () => (S && Array.isArray(S.cards) ? S.cards : []);
const review = () => (S && S.review) || { status: 'idle', cards: [], error: null };
const hud = () => (S && S.hud && typeof S.hud === 'object' ? S.hud : null);
const hhmm = (iso) => { const d = new Date(iso); return isNaN(d) ? '' : pad2(d.getHours()) + ':' + pad2(d.getMinutes()); };
const ymd = (off) => { const d = new Date(Date.now() + off * 86400000); return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); };
function dayLabel(day) {
  if (day === ymd(0)) return 'Today';
  if (day === ymd(-1)) return 'Yesterday';
  const d = new Date(str(day) + 'T00:00:00');
  return isNaN(d) ? 'Earlier' : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
}
function elapsed() { const s = focus().startedAt; return typeof s === 'number' ? Math.max(0, Math.floor((Date.now() - s) / 1000)) : 0; }
function fmtTime(sec) {
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  return h ? h + ':' + pad2(m) + ':' + pad2(s) : pad2(m) + ':' + pad2(s);
}
function cycle(list, cur) { const i = list.indexOf(cur); return list[(i + 1) % list.length]; }

function setCfg(patch, label) {
  if (S) S.config = Object.assign({}, S.config, patch);
  if (S && 'collect' in patch) S.imu = Object.assign({}, S.imu, { collect: patch.collect });
  render(); send('set_config', patch, label);
}

function arm(key) {
  if (armed === key) { disarm(); return true; }
  armed = key; clearTimeout(armTimer);
  armTimer = setTimeout(() => { armed = null; render(); }, 3000);
  render(); return false;
}
function disarm() { armed = null; clearTimeout(armTimer); }

// ---------- banner ----------
function banner(glyph, text, sub, kind) {
  const b = $('banner');
  b.className = kind || '';
  $('bg').textContent = glyph; $('bt').textContent = text; $('bs').textContent = sub || '';
  void b.offsetWidth;
  b.classList.add('show'); $('app').classList.add('bannered');
  clearTimeout(bannerTimer); bannerTimer = setTimeout(() => { b.classList.remove('show'); $('app').classList.remove('bannered'); }, 2400);
}

// ---------- navigation ----------
const sub = () => { const t = nav[nav.length - 1]; return t && t.t === 'sub' ? t : null; };
const hudMenu = () => nav.some((n) => n.t === 'hud');
function push(e) { nav.push(e); try { history.pushState({ d: nav.length }, ''); } catch (err) { /* ignore */ } disarm(); render(); }
function back() {
  if (!nav.length) return;
  try { history.back(); } catch (e) { nav.pop(); render(); }
}
window.addEventListener('popstate', () => { if (nav.length) nav.pop(); disarm(); render(); });
function unwindMenu() {
  const n = nav.length; if (!n || !hudMenu()) return;
  nav = [];
  try { history.go(-n); } catch (e) { /* ignore */ }
}
function openMenu() { pg = 0; push({ t: 'hud' }); }
function switchPage(d) {
  const n = Math.min(Math.max(pg + d, 0), PAGES.length - 1);
  if (n === pg) return;
  pg = n; disarm();
  const c = $('content');
  c.style.transition = 'none'; c.style.opacity = '0'; c.style.transform = 'translateX(' + (d * 12) + 'px)';
  render(); enterPage();
  requestAnimationFrame(() => requestAnimationFrame(() => { c.style.transition = ''; c.style.opacity = ''; c.style.transform = ''; }));
}
function enterPage() {
  if (PAGES[pg] === 'review' && paired() && review().status === 'idle' && !rv.requested) {
    rv = { idx: 0, answer: false, requested: true }; send('review_load', {}, null);
  }
}

function openComposer(target) {
  compose = target;
  const inp = $('goalInput');
  inp.value = target === 'goal' ? goal : '';
  try { inp.focus(); } catch (e) { /* ignore */ }
}
$('goalInput').addEventListener('change', (e) => {
  const v = e.target.value.trim().slice(0, 200); e.target.blur();
  if (compose === 'goal') { goal = v; lsSet('fl_goal', goal || null); render(); }
  else if (v) { send('add_note', { text: v }, 'Saved to inbox'); }
});
$('goalInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') e.target.blur(); e.stopPropagation(); });

// ---------- screens: each returns {top?, rows?, body?, after?, footer, max?} ----------
function focusPage() {
  const f = focus(), c = config(), top = [], rows = [];
  if (running()) {
    const head = el('div', 'timer', fmtTime(elapsed())); head.id = 'timer'; top.push(head);
    const bits = [cap(str(f.mode, 'auto'))]; if (f.goal) bits.push(str(f.goal));
    if (f.micPaused) bits.push('Mic paused');
    top.push(el('div', 'line', bits.join(' · ')));
    if (f.lastHint) top.push(el('div', 'hinttxt clamp2', str(f.lastHint)));
    const q = f.quest;
    if (q && Array.isArray(q.steps) && q.steps.length) {
      const i = Math.min(Math.max(q.index | 0, 0), q.steps.length - 1);
      const d = el('div', 'quest'); d.appendChild(document.createTextNode('▸ ' + (i + 1) + '/' + q.steps.length + ' · ')); d.appendChild(el('b', '', str(q.steps[i])));
      top.push(d);
    } else top.push(el('div', 'gap'));
    rows.push({ l: 'Help now', act: () => send('help', {}, 'Help requested') });
    rows.push({ l: 'Next step', act: () => send('next', {}, 'Next step') });
    rows.push(f.micPaused ? { l: 'Resume mic', act: () => send('resume_mic', {}, 'Mic on') } : { l: 'Pause mic', act: () => send('pause_mic', {}, 'Mic paused') });
    rows.push({ l: armed === 'stop' ? 'Pinch again to stop' : 'Stop session', warn: armed === 'stop', act: () => { if (arm('stop')) send('stop_focus', {}, null, false); } });
    return { top, rows, max: 4, footer: hudMenu() ? 'Back to HUD · pinch select' : 'Pinch select · ← → pages' };
  }
  top.push(el('div', 'title', 'Ready to focus'));
  top.push(el('div', 'meta', 'Set a mode and goal, or just start.'));
  top.push(el('div', 'gap'));
  rows.push({ pill: true, l: 'Start focus', act: () => {
    const args = { mode: str(c.mode, 'auto') }; if (goal) args.goal = goal.slice(0, 200);
    send('start_focus', args, null, false);
  } });
  rows.push({ l: 'Mode', r: cap(str(c.mode, 'auto')), act: () => { const m = cycle(MODES, str(c.mode, 'auto')); setCfg({ mode: m }, 'Mode: ' + cap(m)); } });
  rows.push({ l: 'Goal', r: goal ? trunc(goal, 24) : 'None', act: () => openComposer('goal') });
  const g = S && S.glasses, after = [el('div', 'gap')];
  after.push(el('div', 'meta', g && g.connected ? 'Glasses · ' + str(g.name, 'connected') + (typeof g.battery === 'number' ? ' · ' + g.battery + '%' : '') : 'Glasses not connected'));
  after.push(el('div', 'meta', openCount() + ' open in inbox · ' + todayCount() + ' cards today'));
  return { top, rows, after, footer: 'Pinch select · ← → pages' };
}
const openCount = () => inbox().filter((i) => !i.done).length;
const todayCount = () => cards().filter((c) => c.day === ymd(0)).length;

function inboxPage() {
  const rows = [{ l: 'Add note', r: 'Speak or type', act: () => openComposer('note') }];
  if (S && S.integrations && S.integrations.todoist) rows.push({ l: 'Sync to Todoist', act: () => send('inbox_sync', {}, 'Synced to Todoist') });
  const items = inbox().slice().sort((a, b) => (a.done === b.done ? 0 : a.done ? 1 : -1));
  for (const i of items) {
    rows.push({ sec: i.done ? 'Done' : 'Open', dim: !!i.done, l: (i.done ? '✓ ' : '○ ') + str(i.text), r: hhmm(i.createdAt), act: () => push({ t: 'sub', name: 'sheet', id: i.id }) });
  }
  const after = items.length ? null : [el('div', 'meta', 'Nothing here yet. Notes you add land in this list.')];
  return { rows, after, footer: 'Pinch open · ← → pages' };
}

function sheetScreen(s) {
  const it = inbox().find((i) => i.id === s.id);
  if (!it) return { top: [el('div', 'title', 'Item gone')], rows: [{ l: 'Close', act: back }], footer: 'Back' };
  return {
    top: [el('div', 'micro meta', it.done ? 'Done' : 'Open'), el('div', 'title', str(it.text)), el('div', 'gap')],
    rows: [
      { l: it.done ? 'Undo' : 'Done', act: () => { it.done = !it.done; send('inbox_done', { id: it.id, done: it.done }, it.done ? 'Marked done' : 'Reopened'); back(); } },
      { l: 'Delete', act: () => { send('inbox_delete', { id: it.id }, 'Deleted'); if (S) S.inbox = inbox().filter((x) => x.id !== it.id); back(); } },
      { l: 'Cancel', act: back }],
    footer: 'Pinch select · Back'
  };
}

function cardsPage() {
  const rows = cards().map((c) => ({ sec: dayLabel(c.day), l: str(c.title, 'Card'), r: (c.reviewed ? '✓ ' : '') + str(c.at), act: () => push({ t: 'sub', name: 'detail', id: c.id }) }));
  return { rows, after: rows.length ? null : [el('div', 'meta', 'No cards yet. They appear as you work.')], footer: 'Pinch open · ← → pages' };
}

function formulaEl(tex, cls) {
  const d = el('div', cls || 'fx');
  try { if (typeof katex === 'undefined') throw new Error('no katex'); katex.render(tex, d, { throwOnError: true, displayMode: true, output: 'html' }); } catch (e) { d.textContent = tex; }
  return d;
}

function readAloud(c) {
  try {
    const ss = window.speechSynthesis; if (!ss) return;
    if (ss.speaking) { ss.cancel(); banner('○', 'Stopped reading'); render(); return; }
    const parts = [c.title, c.summary].concat((Array.isArray(c.steps) ? c.steps : []).map((s) => s.text), [c.correction]).filter(Boolean);
    const u = new SpeechSynthesisUtterance(parts.join('. '));
    u.lang = { en: 'en-US', cs: 'cs-CZ', sk: 'sk-SK', de: 'de-DE', pl: 'pl-PL' }[config().language] || 'en-US';
    u.onend = render;
    ss.speak(u); banner('▸', 'Reading aloud'); render();
  } catch (e) { /* no speech on this device */ }
}

function detailScreen(s) {
  const c = cards().find((x) => x.id === s.id);
  if (!c) return { top: [el('div', 'title', 'Card not found')], rows: [{ l: 'Close', act: back }], footer: 'Back' };
  const info = el('div', 'info');
  info.appendChild(el('div', 'title', str(c.title, 'Card')));
  info.appendChild(el('div', 'meta', [str(c.situation), dayLabel(c.day) + ' ' + str(c.at)].filter(Boolean).join(' · ')));
  if (c.summary) { const d = el('div', 'body clamp2', str(c.summary)); d.style.fontSize = '20px'; d.style.marginTop = '8px'; info.appendChild(d); }
  if (c.formula) info.appendChild(formulaEl(str(c.formula)));
  const steps = Array.isArray(c.steps) ? c.steps : [];
  if (steps.length) { const w = el('div'); w.style.marginTop = '6px'; steps.slice(0, 3).forEach((st) => w.appendChild(el('div', 'step' + (st.done ? ' done' : ''), (st.done ? '✓ ' : '○ ') + str(st.text)))); info.appendChild(w); }
  if (c.correction && !c.formula) { const d = el('div', 'meta clamp2', str(c.correction)); d.style.marginTop = '6px'; info.appendChild(d); }
  const speaking = (() => { try { return !!(window.speechSynthesis && window.speechSynthesis.speaking); } catch (e) { return false; } })();
  return {
    top: [info], max: 3,
    rows: [
      { l: c.reviewed ? 'Mark unreviewed' : 'Mark reviewed', act: () => { c.reviewed = !c.reviewed; send('card_reviewed', { id: c.id, reviewed: c.reviewed }, c.reviewed ? 'Marked reviewed' : 'Marked unreviewed'); render(); } },
      { l: speaking ? 'Stop reading' : 'Read aloud', act: () => readAloud(c) },
      { l: armed === 'del' ? 'Pinch again to delete' : 'Delete', warn: armed === 'del', act: () => { if (arm('del')) { send('card_delete', { id: c.id }, 'Card deleted'); if (S) S.cards = cards().filter((x) => x.id !== c.id); back(); } } }],
    footer: 'Pinch select · Back'
  };
}

function reviewPage() {
  const R = review(), top = [];
  let rows = null, footer = '← → pages';
  if (R.status === 'loading' || (R.status === 'idle' && rv.requested)) { top.push(el('div', 'title', 'Preparing cards…')); top.push(el('div', 'meta', 'Building today\'s flashcards.')); }
  else if (R.status === 'error') {
    top.push(el('div', 'title', 'Could not prepare cards')); top.push(el('div', 'meta clamp3', str(R.error, 'Unknown error')));
    top.push(el('div', 'gap')); rows = [{ l: 'Try again', act: () => { rv = { idx: 0, answer: false, requested: true }; send('review_load', {}, null); } }]; footer = 'Pinch retry · ← → pages';
  } else if (R.status === 'idle') {
    rows = [{ pill: true, l: 'Load today\'s cards', act: () => { rv = { idx: 0, answer: false, requested: true }; send('review_load', {}, null); render(); } }]; footer = 'Pinch load · ← → pages';
  } else {
    const cs = Array.isArray(R.cards) ? R.cards : [];
    if (!cs.length) top.push(el('div', 'title', 'Nothing to review'));
    else if (rv.idx >= cs.length) { top.push(el('div', 'title', 'Done for today')); top.push(el('div', 'meta', cs.length + ' cards reviewed.')); footer = '← → pages'; }
    else {
      const c = cs[rv.idx] || {};
      top.push(el('div', 'micro meta', 'Card ' + (rv.idx + 1) + ' of ' + cs.length));
      top.push(el('div', 'q clamp3', str(c.q, '?')));
      if (rv.answer) top.push(el('div', 'a clamp3', str(c.a)));
      footer = rv.answer ? 'Pinch next · ← → pages' : 'Pinch show answer · ← → pages';
    }
  }
  return { top, rows, footer };
}

function settingsPage() {
  const c = config(), g = S && S.glasses;
  const onoff = (v) => (v ? 'On' : 'Off');
  const tog = (sec, k, name) => ({ sec, l: name, r: onoff(c[k]), act: () => setCfg({ [k]: !c[k] }, name + ': ' + onoff(!c[k])) });
  const pick = (sec, k, name, vals, fmt) => ({ sec, l: name, r: fmt(c[k]), act: () => { const v = cycle(vals, c[k]); setCfg({ [k]: v }, name + ': ' + fmt(v)); } });
  const langName = (v) => (LANGS.find((x) => x[0] === v) || [v, str(v, 'English')])[1];
  const rows = [
    pick('Session', 'mode', 'Mode', MODES, (v) => cap(str(v, 'auto'))),
    pick('Session', 'language', 'Language', LANGS.map((x) => x[0]), langName),
    pick('Session', 'bufferSec', 'Memory', [60, 120, 150], (v) => (v || 120) + ' s'),
    pick('Session', 'hudTimeoutSec', 'Hint hides after', [10, 20, 40, 0], (v) => (v === 0 ? 'Never' : (v || 20) + ' s')),
    tog('Audio', 'readAloud', 'Read aloud'),
    tog('Audio', 'wakeWord', 'Wake word'),
    tog('Audio', 'nudges', 'Nudges'),
    tog('Audio', 'dozeSound', 'Doze sound'),
    pick('Audio', 'keepAudio', 'Keep audio', ['none', 'last60', 'all'], (v) => ({ none: 'None', last60: 'Last 60 s', all: 'All' }[v] || 'None')),
    pick('Audio', 'audioSource', 'Audio source', ['auto', 'phone', 'glasses'], (v) => cap(str(v, 'auto'))),
    tog('Sensors', 'collect', 'Sensor data collection')
  ];
  if (c.collect) rows.push({ sec: 'Sensors', l: 'Sensor labels', r: S && S.imu && S.imu.label ? str(S.imu.label).replace('_', ' ') : 'None', act: () => push({ t: 'sub', name: 'labels' }) });
  rows.push({ sec: 'Device', l: 'Reconnect glasses', act: () => send('reconnect', {}, 'Reconnecting…') });
  rows.push({ sec: 'Device', l: 'About', r: (phoneOnline ? 'Phone on' : 'Phone off') + ' · ' + (g && g.connected ? 'Glasses' + (typeof g.battery === 'number' ? ' ' + g.battery + '%' : '') : 'Glasses off'), act: null });
  return { rows, footer: 'Pinch change · ← → pages' };
}

function labelsScreen() {
  const cur = S && S.imu && S.imu.label ? S.imu.label : 'none';
  const rows = ACTS.map((a) => ({ l: cap(a.replace('_', ' ')), r: a === cur ? '●' : '', act: () => {
    if (S) S.imu = Object.assign({}, S.imu, { label: a === 'none' ? null : a });
    send('imu_label', { label: a }, 'Label: ' + cap(a.replace('_', ' '))); render();
  } }));
  return { top: [el('div', 'title', 'Sensor labels'), el('div', 'meta', 'Mark what you are doing now.'), el('div', 'gap')], rows, max: 4, footer: 'Pinch label · Back' };
}

const PAGE_FN = { focus: focusPage, inbox: inboxPage, cards: cardsPage, review: reviewPage, settings: settingsPage };
const SUB_FN = { sheet: sheetScreen, detail: detailScreen, labels: labelsScreen };
const curKey = () => (sub() ? sub().name + (sub().id || '') : PAGES[pg]);

// ---------- rendering ----------
const rowEl = (r, focused) => {
  const d = el('div', 'row' + (focused ? ' sel' : '') + (r.dim ? ' dim' : '') + (r.warn ? ' warn' : '') + (r.pill ? ' pill' : ''));
  d.appendChild(el('div', 'l', r.l)); if (r.r) d.appendChild(el('div', 'r', r.r));
  return d;
};

function render() {
  renderChrome();
  if (!paired()) return renderPairing();
  if (renderHud()) return;
  const s = sub();
  const sc = s ? SUB_FN[s.name](s) : PAGE_FN[PAGES[pg]]();
  last = sc;
  const key = curKey(), rows = sc.rows || [], n = rows.length;
  sel[key] = Math.min(Math.max(sel[key] | 0, 0), Math.max(0, n - 1));
  const box = $('content'); box.textContent = '';
  (sc.top || []).forEach((e) => box.appendChild(e));
  if (n) {
    const max = sc.max || 5, i = sel[key];
    const start = Math.min(Math.max(0, i - Math.floor(max / 2)), Math.max(0, n - max));
    const vis = rows.slice(start, start + max);
    if (start > 0) box.appendChild(el('div', 'more', '↑ +' + start));
    vis.forEach((r, k) => {
      if (r.sec && (k === 0 || vis[k - 1].sec !== r.sec)) box.appendChild(el('div', 'sec', r.sec));
      box.appendChild(rowEl(r, start + k === i));
    });
    const below = n - start - max; if (below > 0) box.appendChild(el('div', 'more', '↓ +' + below));
  }
  (sc.after || []).forEach((e) => box.appendChild(e));
  $('footer').textContent = sc.footer || '';
}

function renderChrome() {
  const top = $('top'), ok = paired();
  if (!top.childNodes.length) PAGES.forEach((p) => top.appendChild(el('span', 'tab', p)));
  top.hidden = !ok;
  Array.prototype.forEach.call(top.childNodes, (t, i) => { t.className = 'tab' + (i === pg ? ' on' : ''); });
  const g = S && S.glasses, chip = $('chip');
  const bad = netError || (ok && !phoneOnline && !MOCK);
  const showHud = hudActiveRaw();
  chip.hidden = !ok || (showHud && !bad);
  chip.className = (netError ? 'red' : !phoneOnline && !MOCK ? 'amber' : '') + (showHud ? ' hudmode' : '');
  $('chipTxt').textContent = netError ? 'Offline' : !phoneOnline && !MOCK ? 'Phone off' : g && typeof g.battery === 'number' ? g.battery + '%' : '';
}

function renderPairing() {
  $('footer').textContent = 'Pinch type code';
  const b = $('content'); b.textContent = '';
  b.appendChild(el('div', 'title', 'Not paired'));
  b.appendChild(el('div', 'gap'));
  b.appendChild(el('div', 'body', 'Open FocusLens on your phone, then Settings, then Add to glasses menu.'));
  b.appendChild(el('div', 'gap'));
  b.appendChild(el('div', 'meta', 'Got a code? Pinch to type it.'));
  $('hud').hidden = true;
}

// ---------- HUD (view model from the phone) ----------
// hud = { s: idle|thinking|hint|quest|blocks|detail|message|nudge, anchor?, title?, text?, sub?, formula?, crop?, progress?, rows?, selected?, footer?, attention? }
const hudActiveRaw = () => paired() && running() && !!hud() && !hudMenu() && !sub();
const hudActive = hudActiveRaw;
const attention = () => { const h = hud(); const a = focus().attention; return !!(h && h.attention) || a === 'STUCK' || a === 'DOZING'; };

function renderHud() {
  const box = $('hud');
  if (!hudActive()) { box.hidden = true; hudWas = false; hudSig = ''; return false; }
  box.hidden = false;
  if (!hudWas) { hudWas = true; showHudHint(); }
  const sig = JSON.stringify(hud()) + attention();
  if (sig !== hudSig) { hudSig = sig; buildHud(); }
  return true;
}

function showHudHint() {
  const h = $('hudHint'); h.hidden = false; h.classList.remove('out');
  clearTimeout(hintTimer);
  hintTimer = setTimeout(() => { h.classList.add('out'); hintTimer = setTimeout(() => { h.hidden = true; }, 900); }, 3000);
}

function buildHud() {
  const box = $('hudMain'), h = hud(), idle = h.s === 'idle', pics = !!(h.crop || h.formula);
  box.textContent = ''; box.className = (idle ? 'idle ' : '') + (h.s === 'nudge' ? 'nudge' : '');
  $('hudHint').hidden = $('hudHint').hidden || !idle;
  if (idle) {
    const t = el('div'); t.id = 'hudTimer';
    if (attention()) t.appendChild(el('i'));
    t.appendChild(el('span', '', fmtTime(elapsed()))); t.lastChild.id = 'hudTime';
    box.appendChild(t);
    return;
  }
  if (h.anchor) box.appendChild(el('div', 'anchor', str(h.anchor)));
  if (h.title) box.appendChild(el('div', 'h', str(h.title)));
  if (h.text) box.appendChild(el('div', str(h.text).trim().split(/\s+/).length <= 12 && !pics ? 'h' : 'b', str(h.text)));
  if (h.sub) box.appendChild(el('div', 'sub', str(h.sub)));
  if (typeof h.crop === 'string' && /^[0-9a-f]{64}$/.test(h.crop)) { const i = el('img'); i.alt = ''; i.src = '/api/r/img/' + h.crop; box.appendChild(i); }
  if (h.formula) box.appendChild(formulaEl(str(h.formula)));
  if (Array.isArray(h.rows)) {
    const s = h.selected | 0, start = Math.min(Math.max(0, s - 1), Math.max(0, h.rows.length - 4));
    h.rows.slice(start, start + 4).forEach((r, k) => {
      const d = el('div', 'row' + (start + k === s ? ' sel' : ''));
      d.appendChild(el('div', 'l', str(r && r.title))); d.appendChild(el('div', 'r', str(r && r.time)));
      box.appendChild(d);
    });
  }
  box.appendChild(el('div', 'grow'));
  if (h.progress) box.appendChild(el('div', 'foot', str(h.progress)));
  if (h.footer) box.appendChild(el('div', 'foot', str(h.footer)));
}

setInterval(() => {
  const t = $('timer'); if (t && running()) t.textContent = fmtTime(elapsed());
  const u = $('hudTime'); if (u && running()) u.textContent = fmtTime(elapsed());
}, 1000);

// ---------- input ----------
document.addEventListener('keydown', (e) => {
  if (e.target === $('goalInput') || e.target === $('codeInput')) return;
  const k = e.key;
  if (BACKK.includes(k)) {
    if (e.repeat) return; // holds belong to the system
    if (hudActive() && hud().s !== 'idle') { e.preventDefault(); send('gesture', { g: 'back' }, null); return; }
    if (nav.length) { e.preventDefault(); back(); }
    return; // at a root: the system shows its menu / exits
  }
  if (!paired()) { if (k === 'Enter') { e.preventDefault(); $('codeInput').focus(); } return; }
  if (hudActive()) {
    const idle = hud().s === 'idle';
    if (idle && (k === 'ArrowLeft' || k === 'ArrowRight')) { e.preventDefault(); openMenu(); return; }
    const g = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'select' }[k];
    if (!g) return;
    e.preventDefault(); if (e.repeat && g === 'select') return;
    send('gesture', { g }, null);
    return;
  }
  const rows = last.rows || [], key = curKey();
  if (k === 'ArrowLeft' || k === 'ArrowRight') { e.preventDefault(); if (!sub()) switchPage(k === 'ArrowRight' ? 1 : -1); }
  else if (k === 'ArrowUp' || k === 'ArrowDown') {
    e.preventDefault();
    if (rows.length) { sel[key] = ((sel[key] | 0) + (k === 'ArrowDown' ? 1 : rows.length - 1)) % rows.length; disarm(); render(); }
  } else if (k === 'Enter') {
    e.preventDefault(); if (e.repeat) return;
    const r = rows[sel[key] | 0];
    if (r && r.act) r.act();
    else if (!sub() && PAGES[pg] === 'review' && !rows.length) reviewStep();
  }
});

function reviewStep() {
  const cs = review().cards || [];
  if (review().status !== 'ready' || !cs.length || rv.idx >= cs.length) return;
  if (!rv.answer) rv.answer = true; else { rv.answer = false; rv.idx++; }
  render();
}

// ---------- mock (dev, ?mock=1) ----------
let mock = null, mockAcks = [], mockSeq = 0;
function mockInit() {
  const now = Date.now(), iso = (m) => new Date(now - m * 60000).toISOString();
  const runOn = P.get('run') === '1' || P.has('hud');
  mock = { v: 2,
    focus: runOn ? { running: true, mode: 'lecture', goal: 'Linear algebra: eigenvalues', startedAt: now - 12 * 60000 - 7000, hintCount: 3, micPaused: false, phase: 'lecture', lastHint: 'Try the next micro-step: take the determinant of the 2x2 matrix.', audioSource: 'glasses', attention: 'ENGAGED', quest: { title: 'Eigenvalue practice', steps: ['Write A - λI', 'Take the determinant', 'Solve for λ', 'Check with a vector'], index: 1 } }
      : { running: false, mode: 'auto', goal: '', startedAt: null, hintCount: 0, micPaused: false, phase: 'idle', lastHint: null, audioSource: 'auto', attention: null, quest: null },
    glasses: { connected: true, name: 'Ray-Ban Display', battery: 72, displayActive: true },
    config: { mode: 'lecture', language: 'en', bufferSec: 120, hudTimeoutSec: 20, keepAudio: 'last60', audioSource: 'auto', nudges: true, wakeWord: true, readAloud: false, dozeSound: false, collect: true, retentionDays: 7 },
    inbox: [{ id: 'i1', text: 'Email Anna about the lab report', done: false, createdAt: iso(30), synced: true }, { id: 'i3', text: 'Check bus time to campus', done: false, createdAt: iso(10), synced: true }, { id: 'i4', text: 'Ask Prof. Novak about office hours', done: false, createdAt: iso(55), synced: false }, { id: 'i2', text: 'Buy milk', done: true, createdAt: iso(90), synced: true }, { id: 'i5', text: 'Print the exam schedule', done: true, createdAt: iso(300), synced: true }],
    cards: [
      { id: 'c1', title: 'Linear algebra: eigenvalues', step: 'Compute det(A - λI)', situation: 'Lecture', day: ymd(0), at: '10:12', correction: 'The characteristic polynomial has degree n, so expect up to n roots counted with multiplicity.', summary: 'An eigenvector keeps its direction under A. Find eigenvalues by solving the characteristic equation.', formula: String.raw`\det(A-\lambda I)=0`, reviewed: false, steps: [{ text: 'Write A - λI', done: true }, { text: 'Take the determinant', done: true }, { text: 'Solve for λ', done: false }] },
      { id: 'c2', title: 'Essay outline', step: 'Draft thesis', situation: 'Homework', day: ymd(0), at: '09:03', correction: '', summary: 'Three-part structure: claim, evidence from two sources, counterpoint.', formula: '', reviewed: true, steps: [{ text: 'State the thesis', done: true }, { text: 'Pick two sources', done: true }] },
      { id: 'c3', title: 'Reading: chapter 4 notes', step: '', situation: 'Desk', day: ymd(0), at: '08:20', correction: '', summary: 'Supply and demand shift, elasticity basics.', formula: '', reviewed: false, steps: [] },
      { id: 'c4', title: 'Integration by parts', step: '', situation: 'Lecture', day: ymd(-1), at: '16:40', correction: 'Choose u so that du is simpler than u.', summary: 'Trade a hard integral for an easier one using the product rule backwards.', formula: String.raw`\int u\,dv = uv - \int v\,du`, reviewed: true, steps: [{ text: 'Pick u and dv', done: true }, { text: 'Differentiate u', done: true }] },
      { id: 'c5', title: 'Lab report intro', step: '', situation: 'Homework', day: ymd(-1), at: '11:05', correction: '', summary: 'State the question, the hypothesis and the method in one paragraph.', formula: '', reviewed: false, steps: [] },
      { id: 'c6', title: 'Spanish past tense', step: '', situation: 'Desk', day: ymd(-2), at: '19:30', correction: '', summary: 'Preterite for completed actions, imperfect for habits.', formula: '', reviewed: false, steps: [] }],
    review: { status: 'ready', error: null, cards: [{ q: 'What is an eigenvector?', a: 'A vector whose direction is unchanged by A; Av = λv.' }, { q: 'How do you find eigenvalues?', a: 'Solve det(A - λI) = 0.' }, { q: 'When do you use integration by parts?', a: 'When the integrand is a product and one factor gets simpler on differentiation.' }] },
    imu: { collect: true, label: 'notes' },
    integrations: { todoist: true },
    hud: mockHud(P.get('hud') || 'idle') };
}
function mockHud(name) {
  if (name === 'hint') return { s: 'hint', anchor: 'Linear algebra: eigenvalues', text: 'Set det(A − λI) = 0 and solve for λ.', formula: String.raw`\det(A-\lambda I)=0`, progress: '1/1', footer: '→ next · ← back' };
  if (name === 'quest') return { s: 'quest', anchor: 'Quest · 2/4', title: 'Take the determinant', text: 'Expand along the first row, then collect terms into a quadratic in λ.', sub: 'Next: solve for λ', progress: '2/4', footer: '→ next · ← back' };
  if (name === 'nudge') return { s: 'nudge', anchor: 'Nudge', text: 'Same line for 4 minutes. Want the next micro-step?', sub: 'Pinch for help', attention: true, footer: 'Pinch help · ← dismiss' };
  return { s: 'idle' };
}
function mockApi(path, opts) {
  if (!mock) mockInit();
  if (path.endsWith('/state')) { const acks = mockAcks; mockAcks = []; return Promise.resolve({ status: 200, data: { phone_online: true, state_age_s: 0.5, state: JSON.parse(JSON.stringify(mock)), acks } }); }
  const { type, args = {} } = opts.body, id = 'm' + (++mockSeq), f = mock.focus;
  if (type === 'web_seen') return Promise.resolve({ status: 202, data: { id } });
  if (type === 'gesture') mockGesture(args.g);
  else if (type === 'start_focus') setTimeout(() => { Object.assign(f, { running: true, mode: args.mode || 'auto', goal: args.goal || '', startedAt: Date.now(), hintCount: 0, micPaused: false, lastHint: null, quest: null }); mock.hud = { s: 'idle' }; }, 900);
  else if (type === 'stop_focus') setTimeout(() => { Object.assign(f, { running: false, lastHint: null, quest: null }); }, 900);
  else if (type === 'pause_mic') f.micPaused = true;
  else if (type === 'resume_mic') f.micPaused = false;
  else if (type === 'help') { f.hintCount++; f.lastHint = 'Look at the sign of each term before expanding.'; }
  else if (type === 'next' && f.quest) f.quest.index = Math.min(f.quest.steps.length - 1, f.quest.index + 1);
  else if (type === 'set_config') { Object.assign(mock.config, args); if ('collect' in args) mock.imu.collect = args.collect; }
  else if (type === 'add_note') mock.inbox.unshift({ id: 'n' + id, text: args.text, done: false, createdAt: new Date().toISOString(), synced: false });
  else if (type === 'inbox_done') { const i = mock.inbox.find((x) => x.id === args.id); if (i) i.done = args.done; }
  else if (type === 'inbox_delete') mock.inbox = mock.inbox.filter((x) => x.id !== args.id);
  else if (type === 'card_reviewed') { const c = mock.cards.find((x) => x.id === args.id); if (c) c.reviewed = args.reviewed; }
  else if (type === 'card_delete') mock.cards = mock.cards.filter((x) => x.id !== args.id);
  else if (type === 'imu_label') mock.imu.label = args.label === 'none' ? null : args.label;
  else if (type === 'review_load') {
    mock.review = { status: 'loading', cards: [], error: null };
    setTimeout(() => { mock.review = { status: 'ready', error: null, cards: [{ q: 'What is an eigenvector?', a: 'A vector whose direction is unchanged by A; Av = λv.' }, { q: 'How do you find eigenvalues?', a: 'Solve det(A - λI) = 0.' }] }; }, 1500);
  }
  mockAcks.push({ id, ok: true, error: null });
  return Promise.resolve({ status: 202, data: { id } });
}
function mockGesture(g) {
  const h = mock.hud;
  if (h.s === 'idle' && g === 'select') {
    mock.hud = { s: 'thinking', title: 'Looking…' };
    setTimeout(() => { mock.hud = mockHud('hint'); }, 1500);
  } else if (h.s === 'idle' && g === 'up') mock.hud = { s: 'blocks', rows: [{ title: 'Eigenvalues', time: '10:12' }, { title: 'Essay outline', time: '09:03' }], selected: 0, footer: 'Pinch open · ← close' };
  else if (g === 'back' || g === 'left') mock.hud = { s: 'idle' };
  else if (h.s === 'blocks' && g === 'up') h.selected = Math.max(0, h.selected - 1);
  else if (h.s === 'blocks' && g === 'down') h.selected = Math.min(h.rows.length - 1, h.selected + 1);
}

// ---------- boot ----------
(async function boot() {
  if (MOCK) mockInit();
  await claimFromUrl();
  await fetchState();
  const want = PAGES.indexOf(P.get('page'));
  if (MOCK) {
    if (want >= 0) pg = want;
    const wantSub = P.get('detail');
    if ((want >= 0 || wantSub) && running() && hud()) { nav.push({ t: 'hud' }); }
    if (wantSub) nav.push({ t: 'sub', name: 'detail', id: wantSub });
    if (P.get('answer') === '1') rv.answer = true;
    const b = P.get('banner');
    if (b === 'start') banner('●', 'Session active', 'Lecture · Linear algebra: eigenvalues');
    else if (b === 'stop') banner('✓', 'Session ended', '12 min · 3 hints');
    else if (b === 'err') banner('●', 'Phone offline', '', 'err');
  }
  render(); enterPage();
  poll();
})();
