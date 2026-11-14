'use strict';
/* Fáze 0 (V2_DIRECTION.md §4): answers the hardware questions on the real glasses. Install /g/spike.html as a web app.
 * #1 timers while the display is off / app backgrounded (also visible as "spike beat" lines in the Vercel logs)
 * #2 where pinch goes (keydown log) · Back: keydown or popstate?   #4 DeviceMotion source + Hz
 * #7 fetch latency   #8 EventSource   + getUserMedia, speechSynthesis, Notification, WakeLock presence. */
const $ = (id) => document.getElementById(id);
const lines = [];
function log(s) {
  const t = new Date().toTimeString().slice(0, 8);
  lines.unshift(t + ' ' + s); lines.length = Math.min(lines.length, 9);
  console.log('[spike]', s); render();
}

// #1 timers + visibility
let n = 0, last = Date.now(), maxGap = 0;
setInterval(() => {
  const now = Date.now(), gap = now - last; last = now; n++;
  if (gap > 3000) { maxGap = Math.max(maxGap, gap); log('timer gap ' + (gap / 1000).toFixed(1) + ' s (vis=' + document.visibilityState + ')'); }
  if (n % 10 === 0) fetch('/api/spike/beat?n=' + n + '&vis=' + document.visibilityState, { cache: 'no-store' }).catch(() => {});
  render();
}, 1000);
['visibilitychange', 'freeze', 'resume'].forEach((ev) => document.addEventListener(ev, () => log(ev + ' vis=' + document.visibilityState)));
['pagehide', 'pageshow', 'blur', 'focus'].forEach((ev) => window.addEventListener(ev, () => log(ev + ' vis=' + document.visibilityState)));

// #2 input: keys, pointer, Back
try { history.pushState({ spike: 1 }, ''); } catch (e) { /* ignore */ }
window.addEventListener('popstate', () => {
  log('popstate (Back via history)');
  try { history.pushState({ spike: 1 }, ''); } catch (e) { /* ignore */ }
});
let sel = 0;
document.addEventListener('keydown', (e) => {
  log('key ' + e.key + ' code=' + e.code);
  if (e.key === 'ArrowUp' || e.key === 'ArrowDown') { e.preventDefault(); sel = (sel + (e.key === 'ArrowDown' ? 1 : TESTS.length - 1)) % TESTS.length; render(); }
  else if (e.key === 'Enter') { e.preventDefault(); TESTS[sel][1](); }
  else if (e.key === 'Escape' || e.key === 'Backspace') e.preventDefault();
});
['pointerdown', 'click'].forEach((ev) => document.addEventListener(ev, (e) => log(ev + ' ' + Math.round(e.clientX) + ',' + Math.round(e.clientY))));

// #4 DeviceMotion
let motionN = 0, motionHz = 0, motionInfo = '', orientN = 0;
async function motion() {
  try {
    if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') log('motion permission: ' + await DeviceMotionEvent.requestPermission());
  } catch (e) { log('motion permission error ' + e.name); }
  if (typeof DeviceMotionEvent === 'undefined') return log('DeviceMotion: not supported');
  const f = (v) => (v == null ? 'null' : v.toFixed(1));
  window.addEventListener('devicemotion', (e) => {
    motionN++;
    const a = e.accelerationIncludingGravity || {}, r = e.rotationRate || {};
    motionInfo = 'acc ' + [a.x, a.y, a.z].map(f).join(',') + ' gyro ' + (r.alpha == null ? 'null' : 'yes') + ' int=' + e.interval;
  });
  window.addEventListener('deviceorientation', (e) => { if (orientN++ % 60 === 0) log('orientation a/b/g ' + [e.alpha, e.beta, e.gamma].map(f).join('/')); });
  setInterval(() => { motionHz = motionN; motionN = 0; }, 1000);
  log('DeviceMotion on: tilt head, then tilt phone → which IMU moves the numbers?');
}

// #7 latency
async function latency() {
  const ts = [];
  for (let i = 0; i < 10; i++) {
    const t0 = performance.now();
    try { await fetch('/api/health', { cache: 'no-store' }); } catch (e) { return log('fetch failed ' + e.name); }
    ts.push(performance.now() - t0);
  }
  ts.sort((a, b) => a - b);
  log('fetch ms median ' + ts[5].toFixed(0) + ' p90 ' + ts[9].toFixed(0) + ' min ' + ts[0].toFixed(0));
}

// #8 EventSource
function sse() {
  if (typeof EventSource === 'undefined') return log('EventSource: not supported');
  const es = new EventSource('/api/spike/sse');
  let got = 0;
  es.addEventListener('tick', (e) => { got++; log('SSE tick ' + e.data); if (got === 5) es.close(); });
  es.onerror = () => { log('SSE error/closed after ' + got); es.close(); };
}

async function media(kind) {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return log('getUserMedia: not available');
  try {
    const s = await navigator.mediaDevices.getUserMedia(kind === 'video' ? { video: true } : { audio: true });
    log(kind + ': OK ' + s.getTracks().map((t) => t.label || t.kind).join(', '));
    s.getTracks().forEach((t) => t.stop());
  } catch (e) { log(kind + ': ' + e.name); }
}

function speak() {
  if (!('speechSynthesis' in window)) return log('speechSynthesis: none');
  const u = new SpeechSynthesisUtterance('FocusLens test');
  u.onend = () => log('speech end');
  u.onerror = (e) => log('speech error ' + e.error);
  speechSynthesis.speak(u);
  log('speaking, voices=' + speechSynthesis.getVoices().length);
}

function apis() {
  log('Notification ' + ('Notification' in window ? Notification.permission : 'none') + ' · wakeLock ' + ('wakeLock' in navigator) +
    ' · SW ' + ('serviceWorker' in navigator) + ' · geo ' + ('geolocation' in navigator) + ' · vibrate ' + ('vibrate' in navigator));
}

async function notify() {
  if (!('Notification' in window)) return log('Notification: none');
  try { log('Notification permission: ' + await Notification.requestPermission()); new Notification('FocusLens test'); log('notification sent'); }
  catch (e) { log('notify: ' + e.name); }
}

const TESTS = [
  ['DeviceMotion (#4)', motion], ['Fetch latency x10 (#7)', latency], ['EventSource (#8)', sse],
  ['Camera getUserMedia', () => media('video')], ['Mic getUserMedia', () => media('audio')],
  ['speechSynthesis', speak], ['API presence', apis], ['Notification (#1 surface)', notify],
];

function render() {
  $('status').textContent = 'up ' + n + ' s · max gap ' + (maxGap / 1000).toFixed(1) + ' s · motion ' + motionHz + ' Hz';
  const b = $('body');
  b.textContent = '';
  TESTS.forEach(([name], i) => {
    const r = document.createElement('div');
    r.className = 'row' + (i === sel ? ' sel' : '');
    r.style.height = '34px'; r.style.fontSize = '20px';
    r.textContent = name;
    b.appendChild(r);
  });
  if (motionInfo) { const m = document.createElement('div'); m.className = 'hint'; m.textContent = motionInfo; b.appendChild(m); }
  lines.forEach((l) => { const d = document.createElement('div'); d.style.fontSize = '15px'; d.style.color = '#bbb'; d.textContent = l; b.appendChild(d); });
}
log('ready · vis=' + document.visibilityState);
