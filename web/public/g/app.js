'use strict';
/* FocusLens glasses web app (Meta Ray-Ban Display, 600x600 additive display). Remote + viewer; phone does the sensing.
 *
 * Input (KeyboardEvent.key): ArrowUp/Down move the focused row, ArrowLeft/Right switch pages, Enter (pinch) activates,
 * Escape/Backspace = Back. Sub-screens push a history entry and Back pops it (popstate). At a page root Back is NOT
 * intercepted, so the system shows its native menu. Key repeats of Back belong to the system and are ignored.
 * Notes are dictated: the Notes page row sends `voice_note`; the phone records, transcribes and saves (HUD overlay shows progress).
 * Dev: ?mock=1 runs without network against a fake phone (mock.js). Params: page=, run=1, hud=idle|hint|quest|nudge|message|thinking,
 * sim=<scenario>, detail=<id>, banner=start|stop|err. Settings > Developer > Scenarios plays the scenarios (real phone: command `sim`).
 */

const PAGES = ['focus', 'cards', 'review', 'inbox', 'settings'];
const PAGE_NAME = { focus: 'Session', cards: 'Cards', review: 'Review', inbox: 'Notes', settings: 'Settings' };
const SUB_TITLE = { sheet: 'Note', detail: 'Card', labels: 'Sensor labels', sims: 'Scenarios' };
// [command name, label, spec section it tests]
const SIMS = [['start', 'Start session · 3 s confirm', '§2.1'], ['lecture_hint', 'Lecture pinch · recap + write down', '§2.3'],
  ['homework_quest', 'Homework pinch · quest + check', '§2.3'], ['bookmark', 'Save for later', '§2.3'], ['milestone', '30-min pulse', '§2.2'],
  ['stuck_l1', 'Stuck · L1 dot', '§3.2'], ['stuck_l2', 'Stuck · L2 offer', '§3.2'], ['lapse_l2', 'Lecture lapse · L2', '§3.2'],
  ['key_l2', 'Key phrase · L2', '§3.2'], ['doze_l2', 'Dozing · L2', '§3.2'], ['stop', 'End session', '§2.1']];
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
const pad2 = (n) => String(n).padStart(2, '0');

// ---------- app state ----------
let token = lsGet('fl_web_token');
let S = null, stateSig = '';
let phoneOnline = false, netError = false;
let pg = 0;                 // current top-level page index
let nav = [];               // pushed history entries: {t:'hud'} | {t:'sub', name, id}
const sel = {};             // focused row per screen key
let rv = { idx: 0, answer: false, requested: false };
const pending = new Map();  // command id -> {label, ok}
let pollTimer = null, failures = 0, lastAction = 0;
let prevRunning = null, lastHints = 0;
let armed = null, armTimer = null;
let last = { rows: [] };
let hudSig = '', bannerTimer = null;

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
  const fast = MOCK || Date.now() - lastAction < 10000 || (hud() && (hud().s === 'thinking' || (!running() && hud().s !== 'idle')));
  pollTimer = setTimeout(poll, failures ? SLOW_MS : fast ? FAST_MS : POLL_MS);
}

function applyState(d) {
  const wasOnline = phoneOnline;
  phoneOnline = !!d.phone_online;
  // tell the phone at once (not after the 20 s heartbeat) that we draw the HUD, or its first hint goes to the DAT display
  if (phoneOnline && !wasOnline && document.visibilityState === 'visible') seen(true);
  S = d.state && typeof d.state === 'object' ? d.state : null;
  stateSig = JSON.stringify(S);
  for (const a of Array.isArray(d.acks) ? d.acks : []) {
    const p = a && pending.get(a.id);
    if (!p) continue;
    pending.delete(a.id);
    if (p.onAck) p.onAck(a); // a waiting WebMCP tool call (webmcp.js)
    if (!a.ok) banner('●', str(a.error, 'Failed'), '', 'err');
    else if (p.label) banner('✓', p.label);
  }
  const r = running();
  if (r) lastHints = focus().hintCount | 0;
  if (prevRunning !== null && r !== prevRunning) {
    if (!r) banner('✓', 'Session ended', lastHints + ' hints'); // the phone shows the start confirmation itself
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
function cycle(list, cur) { const i = list.indexOf(cur); return list[(i + 1) % list.length]; }

function setCfg(patch, label) {
  if (S) S.config = Object.assign({}, S.config, patch);
  if (S && 'collect' in patch) S.imu = Object.assign({}, S.imu, { collect: patch.collect });
  render(); send('set_config', patch, label);
}
// poll now (used by mock.js after it changes the fake phone)
function refresh() { clearTimeout(pollTimer); poll(); }

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
function popAll() {
  const n = nav.length; if (!n) return;
  nav = []; disarm();
  try { history.go(-n); } catch (e) { /* ignore */ }
  render();
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

// ---------- screens: each returns {top?, rows?, after?, max?} ----------
function focusPage() {
  const f = focus(), top = [], rows = [];
  if (running()) {
    top.push(el('div', 'title', f.situation ? cap(str(f.situation)) : 'In session'));
    const q = f.quest;
    if (q && Array.isArray(q.steps) && q.steps.length) {
      const i = Math.min(Math.max(q.index | 0, 0), q.steps.length - 1);
      top.push(el('div', 'body clamp2', str(q.steps[i])));
    }
    if (f.lastHint) top.push(el('div', 'hinttxt clamp2', str(f.lastHint)));
    top.push(el('div', 'gap'));
    rows.push({ l: 'Help now', act: () => send('help', {}, 'Help requested') });
    rows.push({ l: 'Save for later', act: () => send('bookmark', {}, null) });
    rows.push(f.micPaused ? { l: 'Resume mic', r: 'Mic paused', act: () => send('resume_mic', {}, 'Mic on') } : { l: 'Pause mic', act: () => send('pause_mic', {}, 'Mic paused') });
    rows.push({ l: armed === 'stop' ? 'Pinch again to end' : 'End session', r: armed === 'stop' ? '' : 'pinch twice', warn: armed === 'stop', act: () => { if (arm('stop')) send('stop_focus', {}, null, false); } });
    return { top, rows };
  }
  top.push(el('div', 'spacer'));
  rows.push({ pill: true, l: 'Start session', act: () => send('start_focus', {}, null, false) });
  const g = S && S.glasses;
  return { top, rows, after: [el('div', 'meta mid', (g && g.connected ? str(g.name, 'Glasses connected') : 'Glasses not connected') + ' · ' + todayCount() + ' cards today')] };
}
const todayCount = () => cards().filter((c) => c.day === ymd(0)).length;

function inboxPage() {
  const rows = [{ pill: true, l: 'Pinch to dictate', act: () => send('voice_note', {}, null) }];
  if (S && S.integrations && S.integrations.todoist) rows.push({ l: 'Sync to Todoist', act: () => send('inbox_sync', {}, 'Synced to Todoist') });
  const items = inbox().slice().sort((a, b) => (a.done === b.done ? 0 : a.done ? 1 : -1));
  for (const i of items) {
    rows.push({ sec: i.done ? 'Done' : 'Open', dim: !!i.done, l: (i.done ? '✓ ' : '○ ') + str(i.text), r: hhmm(i.createdAt), act: () => push({ t: 'sub', name: 'sheet', id: i.id }) });
  }
  const after = items.length ? null : [el('div', 'meta', 'Pinch, say it, done. Notes land here.')];
  return { rows, after };
}

function sheetScreen(s) {
  const it = inbox().find((i) => i.id === s.id);
  if (!it) return { top: [el('div', 'title', 'Item gone')], rows: [{ l: 'Close', act: back }] };
  return {
    top: [el('div', 'micro meta', it.done ? 'Done' : 'Open'), el('div', 'title', str(it.text)), el('div', 'gap')],
    rows: [
      { l: it.done ? 'Undo' : 'Done', act: () => { it.done = !it.done; send('inbox_done', { id: it.id, done: it.done }, it.done ? 'Marked done' : 'Reopened'); back(); } },
      { l: 'Delete', act: () => { send('inbox_delete', { id: it.id }, 'Deleted'); if (S) S.inbox = inbox().filter((x) => x.id !== it.id); back(); } },
      { l: 'Cancel', act: back }],
  };
}

function cardsPage() {
  const rows = cards().map((c) => ({ sec: dayLabel(c.day), l: str(c.title, 'Card'), r: (c.reviewed ? '✓ ' : '') + str(c.at), act: () => push({ t: 'sub', name: 'detail', id: c.id }) }));
  return { rows, after: rows.length ? null : [el('div', 'meta', 'No cards yet. They appear as you work.')] };
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
  if (!c) return { top: [el('div', 'title', 'Card not found')], rows: [{ l: 'Close', act: back }] };
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
  };
}

function reviewPage() {
  const R = review(), top = [];
  let rows = null;
  if (R.status === 'loading' || (R.status === 'idle' && rv.requested)) { top.push(el('div', 'title', 'Preparing cards…')); top.push(el('div', 'meta', 'Building today\'s flashcards.')); }
  else if (R.status === 'error') {
    top.push(el('div', 'title', 'Could not prepare cards')); top.push(el('div', 'meta clamp3', str(R.error, 'Unknown error')));
    top.push(el('div', 'gap')); rows = [{ l: 'Try again', act: () => { rv = { idx: 0, answer: false, requested: true }; send('review_load', {}, null); } }];
  } else if (R.status === 'idle') {
    rows = [{ pill: true, l: 'Load today\'s cards', act: () => { rv = { idx: 0, answer: false, requested: true }; send('review_load', {}, null); render(); } }];
  } else {
    const cs = Array.isArray(R.cards) ? R.cards : [];
    if (!cs.length) top.push(el('div', 'title', 'Nothing to review'));
    else if (rv.idx >= cs.length) { top.push(el('div', 'title', 'Done for today')); top.push(el('div', 'meta', cs.length + ' cards reviewed.')); }
    else {
      const c = cs[rv.idx] || {};
      top.push(el('div', 'micro meta', 'Card ' + (rv.idx + 1) + ' of ' + cs.length));
      top.push(el('div', 'q clamp3', str(c.q, '?')));
      if (rv.answer) top.push(el('div', 'a clamp3', str(c.a)));
    }
  }
  return { top, rows };
}

function settingsPage() {
  const c = config(), g = S && S.glasses;
  const onoff = (v) => (v ? 'On' : 'Off');
  const tog = (sec, k, name) => ({ sec, l: name, r: onoff(c[k]), act: () => setCfg({ [k]: !c[k] }, name + ': ' + onoff(!c[k])) });
  const pick = (sec, k, name, vals, fmt) => ({ sec, l: name, r: fmt(c[k]), act: () => { const v = cycle(vals, c[k]); setCfg({ [k]: v }, name + ': ' + fmt(v)); } });
  const langName = (v) => (LANGS.find((x) => x[0] === v) || [v, str(v, 'English')])[1];
  const rows = [
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
  if ((S && S.dev) || MOCK) rows.push({ sec: 'Developer', l: 'Scenarios', r: 'Test the HUD', act: () => push({ t: 'sub', name: 'sims' }) });
  rows.push({ sec: 'Device', l: 'About', r: (phoneOnline ? 'Phone on' : 'Phone off') + ' · ' + (g && g.connected ? 'Glasses' + (typeof g.battery === 'number' ? ' ' + g.battery + '%' : '') : 'Glasses off'), act: null });
  return { rows };
}

function simsScreen() {
  const rows = SIMS.map(([name, label, spec]) => ({ l: label, r: spec, act: () => { send('sim', { name }, null); popAll(); } }));
  return { top: [el('div', 'meta', 'Pick one to play it on the HUD.'), el('div', 'gap')], rows, max: 4 };
}

function labelsScreen() {
  const cur = S && S.imu && S.imu.label ? S.imu.label : 'none';
  const rows = ACTS.map((a) => ({ l: cap(a.replace('_', ' ')), r: a === cur ? '●' : '', act: () => {
    if (S) S.imu = Object.assign({}, S.imu, { label: a === 'none' ? null : a });
    send('imu_label', { label: a }, 'Label: ' + cap(a.replace('_', ' '))); render();
  } }));
  return { top: [el('div', 'meta', 'Mark what you are doing now.'), el('div', 'gap')], rows, max: 4 };
}

const PAGE_FN = { focus: focusPage, inbox: inboxPage, cards: cardsPage, review: reviewPage, settings: settingsPage };
const SUB_FN = { sheet: sheetScreen, detail: detailScreen, labels: labelsScreen, sims: simsScreen };
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
    const max = sc.max || 4, i = sel[key];
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
  renderDots();
}

function renderChrome() {
  const ok = paired(), s = sub(), g = S && S.glasses, chip = $('chip');
  $('pname').textContent = !ok ? 'FocusLens' : s ? SUB_TITLE[s.name] : PAGE_NAME[PAGES[pg]];
  const low = g && typeof g.battery === 'number' && g.battery <= 30;
  const txt = !ok ? '' : netError ? 'Offline' : !phoneOnline && !MOCK ? 'Phone off' : low ? 'Battery ' + g.battery + '%' : '';
  chip.hidden = !txt;
  chip.className = netError ? 'red' : 'amber';
  if (hudActiveRaw()) chip.classList.add('hudmode');
  $('chipTxt').textContent = txt;
}

function renderDots() {
  const d = $('dots'); d.textContent = '';
  if (sub()) d.appendChild(el('span', 'back', '← back'));
  else PAGES.forEach((p, i) => d.appendChild(el('i', i === pg ? 'on' : '')));
}

function renderPairing() {
  $('dots').textContent = '';
  const b = $('content'); b.textContent = '';
  b.appendChild(el('div', 'title', 'Not paired'));
  b.appendChild(el('div', 'gap'));
  b.appendChild(el('div', 'body', 'Open FocusLens on your phone, then Settings, then Add to glasses menu.'));
  b.appendChild(el('div', 'gap'));
  b.appendChild(el('div', 'meta', 'Got a code? Pinch to type it.'));
  $('hud').hidden = true;
}

// ---------- HUD (view model from the phone) ----------
// hud = { s: idle|thinking|hint|quest|blocks|detail|message|nudge, anchor?, recap?, title?, text?, sub?, formula?, crop?, progress?, rows?, selected?, footer?, attention?, kind? }
// Additive display: black = off. Idle is fully black (just the amber L1 dot). No clocks, ever.
// Without a session the overlay shows only while the phone draws something (voice note: listening, saving, saved).
const hudActiveRaw = () => paired() && !!hud() && (running() ? !hudMenu() && !sub() : hud().s !== 'idle');
const hudActive = hudActiveRaw;
const attention = () => { const h = hud(); const a = focus().attention; return !!(h && h.attention) || a === 'STUCK' || a === 'DOZING'; };

function renderHud() {
  const box = $('hud');
  if (!hudActive()) { box.hidden = true; hudSig = ''; return false; }
  box.hidden = false;
  const sig = JSON.stringify(hud()) + attention();
  if (sig !== hudSig) { hudSig = sig; buildHud(); }
  return true;
}

function buildHud() {
  const box = $('hudMain'), h = hud(), t = str(h.s);
  const add = (cls, text) => box.appendChild(el('div', cls, text));
  box.textContent = '';
  box.className = t === 'nudge' ? 'nudge' : t === 'message' || t === 'thinking' ? 'center' : t === 'idle' ? 'idle' : '';
  if (t === 'nudge' && h.kind) box.dataset.kind = str(h.kind); else delete box.dataset.kind;
  if (t === 'idle') { if (attention()) box.appendChild(el('i', 'dot')); return; }
  if (t === 'thinking') { box.appendChild(el('i', 'pulse')); add('h', str(h.title, 'Looking…')); return; }
  if (t === 'message') { add('h', str(h.title)); if (h.footer) add('foot', str(h.footer)); return; }
  const pics = !!(h.crop || h.formula);
  if (h.anchor) add('anchor', str(h.anchor));
  if (h.recap) { add('lbl', 'Just explained'); add('b dim', str(h.recap)); add('lbl', 'Write down'); }
  if (h.title) add('h', str(h.title));
  if (h.text) add(str(h.text).trim().split(/\s+/).length <= 12 && !pics && !h.recap ? 'h' : 'b', str(h.text));
  if (h.sub) add('sub', str(h.sub));
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
  add('grow');
  const m = t === 'quest' && /(\d{1,2})\/(\d{1,2})$/.exec(str(h.progress));
  if (m && +m[2] <= 8) { const p = el('div', 'prog'); p.appendChild(el('span', 'on', '●'.repeat(Math.min(+m[1], +m[2])))); p.appendChild(el('span', '', '○'.repeat(Math.max(0, +m[2] - +m[1])))); p.appendChild(el('span', 'n', ' ' + h.progress)); box.appendChild(p); }
  else if (h.progress && t !== 'hint') add('foot', str(h.progress));
  if (h.footer) add('foot', str(h.footer));
}

// ---------- input ----------
document.addEventListener('keydown', (e) => {
  if (e.target === $('codeInput')) return;
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

// ---------- boot ----------
const loadMock = () => new Promise((res) => { const m = el('script'); m.src = '/g/mock.js'; m.onload = m.onerror = res; document.head.appendChild(m); });

(async function boot() {
  if (MOCK) { await loadMock(); mockInit(); }
  await claimFromUrl();
  await fetchState();
  const want = PAGES.indexOf(P.get('page'));
  if (MOCK) {
    if (want >= 0) pg = want;
    const wantSub = P.get('detail');
    if ((want >= 0 || wantSub || P.get('scenarios') === '1') && running() && hud()) { nav.push({ t: 'hud' }); }
    if (wantSub) nav.push({ t: 'sub', name: 'detail', id: wantSub });
    if (P.get('scenarios') === '1') nav.push({ t: 'sub', name: 'sims' });
    if (P.get('answer') === '1') rv.answer = true;
    const b = P.get('banner');
    if (b === 'stop') banner('✓', 'Session ended', '3 hints');
    else if (b === 'err') banner('●', 'Phone offline', '', 'err');
  }
  render(); enterPage();
  poll();
})();
