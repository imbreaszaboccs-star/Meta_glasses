'use strict';
/* Fake phone for ?mock=1 (loaded by app.js only then). Plays the 11 scenarios (spec §2-§3) with no network.
 * Uses app.js globals: P, ymd, refresh. State is read back through mockApi('/state') like the real relay. */

let mock = null, mockAcks = [], mockSeq = 0, mockTimer = null, mockListening = false;
const QUEST = { title: '2x + 3 = 11', steps: ['Line 2 has a sign error: 2x = 11 + 3 should be 2x = 11 − 3.', 'Write 2x = 8.', 'Divide both sides by 2: x = 4.'], fb: ['', 'Sign fixed, nice.', 'Nice, 2x = 8.'] };
const RECAP = 'The lecturer derived the product rule for derivatives.';
const RULE = String.raw`(f\cdot g)'=f'g+fg'`;
const NUDGES = {
  stuck_l2: { kind: 'stuck', title: 'Stuck? Pinch = smaller step' }, lapse_l2: { kind: 'lapse', title: 'Lost the thread? Pinch = summary' },
  key_l2: { kind: 'key', title: 'Key point just now · Pinch = summary' }, doze_l2: { kind: 'dozing', title: 'Still with us? Pinch = recap' }
};

function mockInit() {
  const now = Date.now(), iso = (m) => new Date(now - m * 60000).toISOString();
  const runOn = P.get('run') === '1' || P.has('hud') || P.has('sim');
  mock = { v: 2, dev: true,
    focus: { running: false, startedAt: null, hintCount: 0, micPaused: false, phase: 'idle', lastHint: null, audioSource: 'auto', attention: null, situation: null, quest: null },
    glasses: { connected: true, name: 'Ray-Ban Display', battery: 72, displayActive: true },
    config: { mode: 'auto', language: 'en', bufferSec: 120, hudTimeoutSec: 20, keepAudio: 'last60', audioSource: 'auto', nudges: true, wakeWord: true, readAloud: false, dozeSound: false, collect: true, retentionDays: 7 },
    inbox: [{ id: 'i1', text: 'Email Anna about the lab report', done: false, createdAt: iso(30), synced: true }, { id: 'i3', text: 'Check bus time to campus', done: false, createdAt: iso(10), synced: true }, { id: 'i4', text: 'Ask Prof. Novak about office hours', done: false, createdAt: iso(55), synced: false }, { id: 'i2', text: 'Buy milk', done: true, createdAt: iso(90), synced: true }, { id: 'i5', text: 'Print the exam schedule', done: true, createdAt: iso(300), synced: true }],
    cards: [
      { id: 'c1', title: 'Product rule', step: 'Write (fg)\'', situation: 'Lecture', day: ymd(0), at: '10:12', correction: '', summary: 'The derivative of a product is the first factor differentiated times the second, plus the first times the second differentiated.', formula: RULE, reviewed: false, steps: [{ text: 'Write the rule', done: true }, { text: 'Try x·sin x', done: false }] },
      { id: 'c2', title: 'Solve 2x + 3 = 11', step: '', situation: 'Homework', day: ymd(0), at: '09:03', correction: 'Sign error when moving 3 to the right side.', summary: 'Linear equation: isolate x in three steps.', formula: '', reviewed: true, steps: [{ text: 'Fix the sign', done: true }, { text: 'Divide by 2', done: true }] },
      { id: 'c3', title: 'Reading: chapter 4 notes', step: '', situation: 'Desk', day: ymd(0), at: '08:20', correction: '', summary: 'Supply and demand shift, elasticity basics.', formula: '', reviewed: false, steps: [] },
      { id: 'c4', title: 'Integration by parts', step: '', situation: 'Lecture', day: ymd(-1), at: '16:40', correction: 'Choose u so that du is simpler than u.', summary: 'Trade a hard integral for an easier one using the product rule backwards.', formula: String.raw`\int u\,dv = uv - \int v\,du`, reviewed: true, steps: [{ text: 'Pick u and dv', done: true }] },
      { id: 'c5', title: 'Lab report intro', step: '', situation: 'Homework', day: ymd(-1), at: '11:05', correction: '', summary: 'State the question, the hypothesis and the method in one paragraph.', formula: '', reviewed: false, steps: [] }],
    review: { status: 'ready', error: null, cards: [{ q: 'State the product rule.', a: String.raw`(fg)' = f'g + fg'` }, { q: 'How do you solve 2x + 3 = 11?', a: 'Subtract 3, then divide by 2: x = 4.' }, { q: 'When do you use integration by parts?', a: 'When one factor of a product gets simpler on differentiation.' }] },
    imu: { collect: true, label: 'notes' },
    integrations: { todoist: true },
    hud: { s: 'idle' } };
  if (runOn) Object.assign(mock.focus, { running: true, startedAt: now, hintCount: 3, phase: 'lecture', situation: 'lecture', attention: 'ENGAGED', lastHint: 'Write down the product rule.' });
  if (P.get('hud')) mock.hud = mockHud(P.get('hud'));
  if (P.get('sim')) mockSim(P.get('sim'));
}

function mockHud(name) {
  if (name === 'hint') return { s: 'hint', recap: RECAP, text: 'Write down the product rule.', formula: RULE, footer: '← close' };
  if (name === 'quest') return questHud(1);
  if (name === 'nudge') return { s: 'nudge', kind: 'stuck', title: NUDGES.stuck_l2.title, attention: true, footer: '← dismiss' };
  if (name === 'message') return { s: 'message', title: 'Session started', footer: 'pinch = help now · swipe → = save for later' };
  if (name === 'thinking') return { s: 'thinking', title: 'Looking…' };
  return { s: 'idle' };
}
const questHud = (i) => ({ s: 'quest', text: QUEST.steps[i], sub: QUEST.fb[i], progress: (i + 1) + '/' + QUEST.steps.length, footer: 'pinch = done · ← close' });

// set the HUD (and focus fields); after ms switch to `then` (default idle). A new call supersedes a pending timed change.
function mockShow(h, ms, then, patch) {
  clearTimeout(mockTimer);
  mock.hud = h;
  if (patch) Object.assign(mock.focus, patch);
  if (ms) mockTimer = setTimeout(() => mockShow(then || { s: 'idle' }), ms);
  refresh();
}
const mockIdle = () => mockShow({ s: 'idle' }, 0, null, { attention: 'ENGAGED' });
const mockMessage = (title, ms, footer) => mockShow({ s: 'message', title, footer }, ms);

function mockHint(recap, text, formula) {
  const f = mock.focus;
  mockShow({ s: 'hint', recap, text, formula, footer: '← close' }, 0, null, { attention: 'ENGAGED', hintCount: f.hintCount + 1, lastHint: text });
}
function mockThinking(title, ms, next) {
  mockShow({ s: 'thinking', title });
  mockTimer = setTimeout(next, ms);
}
function mockLecture() {
  mock.focus.situation = 'lecture';
  mockThinking('Looking…', 1500, () => mockHint(RECAP, 'Write down the product rule.', RULE));
}
function mockQuest() {
  mock.focus.situation = 'homework';
  mockThinking('Looking…', 1500, () => {
    mock.focus.quest = { title: QUEST.title, steps: QUEST.steps, index: 0 };
    mockShow(questHud(0), 0, null, { attention: 'ENGAGED', hintCount: mock.focus.hintCount + 1, lastHint: QUEST.steps[0] });
  });
}
function mockQuestStep() {
  const q = mock.focus.quest; if (!q) return mockIdle();
  mockThinking('Checking…', 1200, () => {
    if (q.index >= q.steps.length - 1) { mock.focus.quest = null; mockMessage('✓ Done!', 2000); return; }
    q.index++; mockShow(questHud(q.index));
  });
}

function mockSim(name) {
  const f = mock.focus;
  if (name === 'start') {
    Object.assign(f, { running: true, startedAt: Date.now(), hintCount: 0, micPaused: false, lastHint: null, quest: null, situation: null, attention: 'ENGAGED' });
    return mockMessage('Session started', 3000, 'pinch = help now · swipe → = save for later');
  }
  if (name === 'stop') { Object.assign(f, { running: false, lastHint: null, quest: null, situation: null, attention: null }); return mockShow({ s: 'idle' }); }
  if (!f.running) Object.assign(f, { running: true, startedAt: Date.now(), hintCount: 0, attention: 'ENGAGED' }); // a scenario implies a running session
  if (name === 'lecture_hint') mockLecture();
  else if (name === 'homework_quest') mockQuest();
  else if (name === 'bookmark') { f.hintCount++; mockMessage('✓ Saved for later', 2000); }
  else if (name === 'milestone') mockMessage('30 min in session', 3000);
  else if (name === 'stuck_l1') mockShow({ s: 'idle', attention: true }, 0, null, { attention: 'STUCK' });
  else if (NUDGES[name]) mockShow({ s: 'nudge', kind: NUDGES[name].kind, title: NUDGES[name].title, attention: true, footer: '← dismiss' }, 0, null, { attention: name === 'doze_l2' ? 'DOZING' : 'STUCK' });
}

// voice note: listening (3 s or pinch) -> "Saving…" 1 s -> note added -> "📥 Saved" 1.5 s -> idle. Back/left cancels.
function mockVoiceNote() {
  mockListening = true;
  mockShow({ s: 'message', title: '🎙 Listening…', footer: 'pinch = done · ← cancel' });
  mockTimer = setTimeout(mockSaveNote, 3000);
}
function mockSaveNote() {
  mockListening = false;
  mockThinking('Saving…', 1000, () => {
    mock.inbox.unshift({ id: 'n' + (++mockSeq), text: 'Koupit mléko a vrátit knihy do knihovny', done: false, createdAt: new Date().toISOString(), synced: false });
    mockMessage('📥 Saved', 1500);
  });
}

function mockGesture(g) {
  const h = mock.hud;
  if (mockListening) {
    if (g === 'select') mockSaveNote();
    else if (g === 'back' || g === 'left') { mockListening = false; mockShow({ s: 'idle' }); }
    return;
  }
  if (h.s === 'thinking') return;
  if (g === 'back' || g === 'left') { if (h.s !== 'idle') mockIdle(); return; }
  if (h.s === 'idle') {
    if (g === 'select') mockLecture();
    else if (g === 'right') mockSim('bookmark');
    else if (g === 'up') mockShow({ s: 'blocks', rows: [{ title: 'Product rule', time: '10:12' }, { title: 'Solve 2x + 3 = 11', time: '09:03' }], selected: 0, footer: 'Pinch open · ← close' });
  } else if (h.s === 'quest' && g === 'select') mockQuestStep();
  else if (h.s === 'nudge' && g === 'select') {
    mockThinking('Looking…', 1500, () => (h.kind === 'stuck' ? mockHint('', 'Write 2x = 11 − 3 on the next line.', String.raw`2x=11-3`) : mockHint(RECAP, 'Write down the product rule.', RULE)));
  } else if (h.s === 'hint' && g === 'select') mockIdle();
  else if (h.s === 'blocks' && g === 'up') h.selected = Math.max(0, h.selected - 1);
  else if (h.s === 'blocks' && g === 'down') h.selected = Math.min(h.rows.length - 1, h.selected + 1);
}

function mockApi(path, opts) {
  if (!mock) mockInit();
  if (path.endsWith('/state')) { const acks = mockAcks; mockAcks = []; return Promise.resolve({ status: 200, data: { phone_online: true, state_age_s: 0.5, state: JSON.parse(JSON.stringify(mock)), acks } }); }
  const { type, args = {} } = opts.body, id = 'm' + (++mockSeq), f = mock.focus;
  if (type === 'web_seen') return Promise.resolve({ status: 202, data: { id } });
  if (type === 'gesture') mockGesture(args.g);
  else if (type === 'sim') mockSim(args.name);
  else if (type === 'start_focus') mockSim('start');
  else if (type === 'stop_focus') mockSim('stop');
  else if (type === 'help') mockGesture('select');
  else if (type === 'bookmark') mockSim('bookmark');
  else if (type === 'pause_mic') f.micPaused = true;
  else if (type === 'resume_mic') f.micPaused = false;
  else if (type === 'next' && f.quest) f.quest.index = Math.min(f.quest.steps.length - 1, f.quest.index + 1);
  else if (type === 'set_config') { Object.assign(mock.config, args); if ('collect' in args) mock.imu.collect = args.collect; }
  else if (type === 'voice_note') mockVoiceNote();
  else if (type === 'add_note') mock.inbox.unshift({ id: 'n' + id, text: args.text, done: false, createdAt: new Date().toISOString(), synced: false });
  else if (type === 'inbox_done') { const i = mock.inbox.find((x) => x.id === args.id); if (i) i.done = args.done; }
  else if (type === 'inbox_delete') mock.inbox = mock.inbox.filter((x) => x.id !== args.id);
  else if (type === 'card_reviewed') { const c = mock.cards.find((x) => x.id === args.id); if (c) c.reviewed = args.reviewed; }
  else if (type === 'card_delete') mock.cards = mock.cards.filter((x) => x.id !== args.id);
  else if (type === 'imu_label') mock.imu.label = args.label === 'none' ? null : args.label;
  else if (type === 'review_load') {
    mock.review = { status: 'loading', cards: [], error: null };
    setTimeout(() => { mock.review = { status: 'ready', error: null, cards: [{ q: 'State the product rule.', a: String.raw`(fg)' = f'g + fg'` }, { q: 'How do you solve 2x + 3 = 11?', a: 'Subtract 3, then divide by 2: x = 4.' }] }; refresh(); }, 1500);
  }
  mockAcks.push({ id, ok: true, error: null });
  return Promise.resolve({ status: 202, data: { id } });
}
