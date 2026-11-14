/**
 * Relay between the glasses web app (/g) and the phone, on Upstash Redis (V2_DIRECTION.md §2).
 *
 *   web  --POST cmd--->  cmds:{id} (list)  --GET poll (LPOP)-->  phone
 *   web  <--GET state--  view:{id} (JSON)  <--POST view-------   phone
 *
 * Nothing durable lives here: view/cmds expire in 2 min, pairing codes in 10 min. The only
 * long-lived key is gen:{id} (web-token revocation counter, or "dead" after unpair).
 * Auth costs no Redis command: phone_secret = HMAC("p|id"), web_token = id.gen.HMAC("w|id|gen");
 * the web token's gen is checked in the same MGET that reads the view.
 */
import { createHash, createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { clean } from '@/lib/ai';

export interface Kv {
  get(k: string): Promise<string | null>;
  mget(...k: string[]): Promise<(string | null)[]>;
  set(k: string, v: string, o?: { ex: number }): Promise<unknown>;
  getdel(k: string): Promise<string | null>;
  del(...k: string[]): Promise<unknown>;
  incr(k: string): Promise<number>;
  expire(k: string, s: number): Promise<unknown>;
  rpush(k: string, ...v: string[]): Promise<number>;
  lpop(k: string, n: number): Promise<string[] | null>;
}

export interface Ctx { kv: Kv; secret: string; appToken: string; publicUrl: string; now: () => number }

const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // no 0/O, 1/I/L
const CODE_LEN = 8, CODE_TTL = 600, LIVE_TTL = 120, IMG_TTL = 3600;
const STATE_MAX = 64 * 1024, IMG_MAX = 200 * 1024, ONLINE_MS = 60_000, CLAIM_PER_MIN = 10, QUEUE_MAX = 50;
const ID_RE = /^[0-9a-f]{32}$/, HASH_RE = /^[0-9a-f]{64}$/, ITEM_RE = /^[A-Za-z0-9_-]{1,64}$/;

const sha = (s: string | Buffer) => createHash('sha256').update(s).digest('hex');
const mac = (secret: string, s: string) => createHmac('sha256', secret).update(s).digest('base64url');
function same(a: string, b: string) {
  const x = Buffer.from(sha(a), 'hex'), y = Buffer.from(sha(b), 'hex');
  return timingSafeEqual(x, y);
}
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
const err = (status: number, detail: string) => json({ detail }, status);
const empty = (status = 204) => new Response(null, { status, headers: { 'cache-control': 'no-store' } });
const bearer = (req: Request) => (req.headers.get('authorization') ?? '').replace(/^Bearer\s+/i, '').trim();
async function body(req: Request): Promise<unknown> {
  try { return await req.json(); } catch { return undefined; }
}
const obj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

// ---------------------------------------------------------------- auth

export const phoneSecret = (c: Ctx, id: string) => mac(c.secret, `p|${id}`);
const webToken = (c: Ctx, id: string, gen: string) => `${id}.${gen}.${mac(c.secret, `w|${id}|${gen}`)}`;

/** Meta AI connector (MCP) token: `m.id.gen.mac`, revoked by unpair like web tokens. */
export const museToken = (c: Ctx, id: string, gen: string) => `m.${id}.${gen}.${mac(c.secret, `m|${id}|${gen}`)}`;

/** Checks a connector token; one MGET (view + gen). */
export async function museAuth(c: Ctx, req: Request) {
  const t = bearer(req), [m, id, gen] = t.split('.');
  if (m !== 'm' || !id || !gen || !ID_RE.test(id) || !/^\d{1,12}$/.test(gen) || !same(t, museToken(c, id, gen))) return null;
  const [view, cur] = await c.kv.mget(`view:${id}`, `gen:${id}`);
  return (cur ?? '0') === gen ? { id, view } : null;
}

export const sign = (c: Ctx, domain: string, payload: string) => mac(c.secret, `${domain}|${payload}`);
export { same as safeEqual, sha };

function phoneAuth(c: Ctx, req: Request): string | null {
  const id = req.headers.get('x-device-id') ?? '';
  return ID_RE.test(id) && same(bearer(req), phoneSecret(c, id)) ? id : null;
}

/** Token shape is checked here (no Redis); caller must compare `gen` with gen:{id}. */
function webTokenParts(c: Ctx, req: Request): { id: string; gen: string } | null {
  const t = bearer(req), [id, gen] = t.split('.');
  if (!id || !gen || !ID_RE.test(id) || !/^\d{1,12}$/.test(gen)) return null;
  return same(t, webToken(c, id, gen)) ? { id, gen } : null;
}

/** One MGET: the view plus the revocation counter. */
async function webAuth(c: Ctx, req: Request) {
  const w = webTokenParts(c, req);
  if (!w) return null;
  const [view, gen] = await c.kv.mget(`view:${w.id}`, `gen:${w.id}`);
  return (gen ?? '0') === w.gen ? { id: w.id, view } : null;
}

// ---------------------------------------------------------------- commands (whitelist)

const MODES = ['auto', 'lecture', 'homework', 'desk'];
const GESTURES = ['select', 'left', 'right', 'up', 'down', 'back'];
type Args = Record<string, unknown>;
type Validator = (a: Args) => Args | null;

/** Only these keys, each passing its check; returns the cleaned args or null. */
function only(spec: Record<string, (v: unknown) => unknown | undefined>, required: string[] = []): Validator {
  return (a) => {
    const out: Args = {};
    for (const [k, v] of Object.entries(a)) {
      const check = spec[k];
      if (!check) return null;
      if (v === null) continue;
      const ok = check(v);
      if (ok === undefined) return null;
      out[k] = ok;
    }
    return required.every((k) => k in out) ? out : null;
  };
}
const isMode = (v: unknown) => (typeof v === 'string' && MODES.includes(v) ? v : undefined);
const oneOf = <T>(...xs: T[]) => (v: unknown) => (xs.includes(v as T) ? v : undefined);
const SIMS = ['start', 'lecture_hint', 'homework_quest', 'bookmark', 'milestone', 'stuck_l1', 'stuck_l2', 'lapse_l2', 'key_l2', 'doze_l2', 'stop'];
const ACTS = ['board', 'notes', 'switching', 'still', 'looking_around', 'nod', 'walking', 'none'];
const isBool = (v: unknown) => (typeof v === 'boolean' ? v : undefined);
const isItem = (v: unknown) => (typeof v === 'string' && ITEM_RE.test(v) ? v : undefined);
const isGoal = (v: unknown) => (typeof v === 'string' && v.length <= 1000 ? clean(v, 200) : undefined);
const isNote = (v: unknown) => (typeof v === 'string' && v.length <= 2000 && clean(v, 300) ? clean(v, 300) : undefined);

export const COMMANDS: Record<string, Validator> = {
  start_focus: only({ mode: isMode, goal: isGoal }),
  stop_focus: only({}), help: only({}), bookmark: only({}), pause_mic: only({}), resume_mic: only({}),
  review_load: only({}), refresh: only({}), next: only({}), reconnect: only({}), inbox_sync: only({}),
  set_config: only({
    mode: isMode, nudges: isBool, wakeWord: isBool, readAloud: isBool, dozeSound: isBool, collect: isBool,
    language: oneOf('en', 'cs', 'sk', 'de', 'pl'), bufferSec: oneOf(60, 120, 150), hudTimeoutSec: oneOf(0, 10, 20, 40),
    keepAudio: oneOf('none', 'last60', 'all'), audioSource: oneOf('auto', 'phone', 'glasses'), retentionDays: oneOf(0, 7, 30),
  }),
  inbox_done: only({ id: isItem, done: isBool }, ['id', 'done']),
  inbox_delete: only({ id: isItem }, ['id']),
  card_reviewed: only({ id: isItem, reviewed: isBool }, ['id', 'reviewed']),
  card_delete: only({ id: isItem }, ['id']),
  imu_label: only({ label: oneOf(...ACTS) }, ['label']),
  // The web app is visible on the glasses (sent every ~20 s and on hide); the phone shows proactive UI via DAT otherwise.
  web_seen: only({ visible: isBool }, ['visible']),
  // v2: the web app owns the display, so glasses input (arrows/Enter/back) is forwarded to FocusEngine.
  gesture: only({ g: (v) => (typeof v === 'string' && GESTURES.includes(v) ? v : undefined) }, ['g']),
  // v2: Meta AI connector tools (lib/mcp.ts); the phone answers them via POST res.
  sim: only({ name: oneOf(...SIMS) }, ['name']),
  add_note: only({ text: isNote }, ['text']),
  last_hint: only({}),
  // Glasses Notes page: pinch -> phone records, transcribes and saves a voice note.
  voice_note: only({}),
};

/** Queues a validated command; `reply` asks the phone to POST its answer to res. Null if args are invalid. */
export async function enqueue(c: Ctx, id: string, type: string, rawArgs: Args, reply = false) {
  const args = COMMANDS[type]?.(rawArgs);
  if (!args) return null;
  const cmd = { id: randomBytes(8).toString('hex'), type, args, ...(reply ? { reply: true } : {}) };
  const n = await c.kv.rpush(`cmds:${id}`, JSON.stringify(cmd));
  await c.kv.expire(`cmds:${id}`, LIVE_TTL);
  return { cmd, n };
}

/** Long-polls the phone's answer to a `reply` command. ponytail: GET every 500 ms (~2 Redis cmds/s), FCM later. */
export async function awaitResult(c: Ctx, id: string, cmdId: string, timeoutMs: number, sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))) {
  const end = c.now() + timeoutMs;
  while (c.now() < end) {
    const v = await c.kv.get(`res:${id}:${cmdId}`);
    if (v !== null) { try { return JSON.parse(v) as { ok: boolean; text: string }; } catch { return null; } }
    await sleep(500);
  }
  return null;
}

export const isOnline = (c: Ctx, view: string | null) => { const v = parseView(view); return !!v && c.now() - v.ts < ONLINE_MS; };

function cleanAcks(v: unknown) {
  if (!Array.isArray(v)) return [];
  return v.slice(-10).filter(obj).map((a) => ({
    id: typeof a.id === 'string' ? a.id.slice(0, 64) : '',
    ok: a.ok === true,
    error: typeof a.error === 'string' ? clean(a.error, 200) : null,
  }));
}

// ---------------------------------------------------------------- routes

async function pair(c: Ctx, req: Request) {
  if (!same(bearer(req), c.appToken)) return err(401, 'bad token');
  const b = await body(req);
  const p = obj(b) ? b : {};
  let id = typeof p.device_id === 'string' && ID_RE.test(p.device_id)
    && typeof p.phone_secret === 'string' && same(p.phone_secret, phoneSecret(c, p.device_id)) ? p.device_id : null;
  if (id && (await c.kv.get(`gen:${id}`)) === 'dead') id = null; // unpaired ids are never revived
  id ??= randomBytes(16).toString('hex');
  const code = Array.from({ length: CODE_LEN }, () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]).join('');
  const h = sha(code);
  await c.kv.set(`code:${h}`, id, { ex: CODE_TTL });
  await c.kv.set(`pcode:${id}`, h, { ex: CODE_TTL }); // one live code per device: older codes stop working
  const webUrl = `${c.publicUrl.replace(/\/+$/, '')}/g?code=${code}`;
  return json({
    device_id: id, phone_secret: phoneSecret(c, id), code, expires_in: CODE_TTL, web_url: webUrl,
    install_url: `fb-viewapp://web_app_deep_link?appName=FocusLens&appUrl=${encodeURIComponent(webUrl)}`,
  });
}

function clientIp(req: Request) {
  return req.headers.get('x-real-ip') ?? req.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ?? '?';
}

/** Trades a one-time pairing code (rate limited per IP) for the device id + current gen; 404/422/429 otherwise. */
export async function claimCode(c: Ctx, req: Request, raw: unknown): Promise<{ id: string; gen: string } | Response> {
  const code = typeof raw === 'string' && raw.trim().length === CODE_LEN ? raw.trim().toUpperCase() : null;
  if (!code) return err(422, 'bad code');
  const rl = `rl:${sha(clientIp(req)).slice(0, 16)}:${Math.floor(c.now() / 60_000)}`;
  const n = await c.kv.incr(rl);
  if (n === 1) await c.kv.expire(rl, 60);
  if (n > CLAIM_PER_MIN) return err(429, 'too many attempts');
  const h = sha(code);
  const id = await c.kv.getdel(`code:${h}`); // single use, valid or not
  if (!id) return err(404, 'code invalid or expired');
  const [live, gen] = await c.kv.mget(`pcode:${id}`, `gen:${id}`);
  if (live !== h || gen === 'dead') return err(404, 'code invalid or expired');
  await c.kv.del(`pcode:${id}`);
  return { id, gen: gen ?? '0' };
}

async function claim(c: Ctx, req: Request) {
  const b = await body(req);
  const r = await claimCode(c, req, obj(b) ? b.code : null);
  return r instanceof Response ? r : json({ web_token: webToken(c, r.id, r.gen) });
}

async function unpair(c: Ctx, id: string) {
  await c.kv.set(`gen:${id}`, 'dead'); // revokes every web token and blocks re-pairing this id
  await c.kv.del(`view:${id}`, `cmds:${id}`, `pcode:${id}`);
  return empty();
}

async function postView(c: Ctx, id: string, req: Request) {
  const raw = await req.text();
  if (Buffer.byteLength(raw) > STATE_MAX) return err(413, 'state too large');
  let b: unknown;
  try { b = JSON.parse(raw); } catch { return err(422, 'bad json'); }
  if (!obj(b) || !obj(b.state)) return err(422, 'state must be an object');
  await c.kv.set(`view:${id}`, JSON.stringify({ ts: c.now(), state: b.state, acks: cleanAcks(b.acks) }), { ex: LIVE_TTL });
  return empty();
}

async function poll(c: Ctx, id: string) {
  const raw = (await c.kv.lpop(`cmds:${id}`, 10)) ?? [];
  const cmds = raw.flatMap((s) => { try { return [JSON.parse(s)]; } catch { return []; } });
  return json({ cmds });
}

function parseView(v: string | null): { ts: number; state: unknown; acks: unknown[] } | null {
  try { return v ? JSON.parse(v) : null; } catch { return null; }
}

async function state(c: Ctx, req: Request) {
  const w = await webAuth(c, req);
  if (!w) return err(401, 'bad web token');
  const v = parseView(w.view), now = c.now();
  return json({
    phone_online: !!v && now - v.ts < ONLINE_MS,
    state_age_s: v ? Math.round((now - v.ts) / 100) / 10 : null,
    state: v?.state ?? null,
    acks: v?.acks ?? [],
  });
}

async function command(c: Ctx, req: Request) {
  const w = await webAuth(c, req);
  if (!w) return err(401, 'bad web token');
  const b = await body(req);
  if (!obj(b) || typeof b.type !== 'string') return err(422, 'unknown command');
  const validate = COMMANDS[b.type];
  if (!validate) return err(422, 'unknown command');
  const raw = b.args === undefined ? {} : obj(b.args) ? b.args : null;
  const args = raw && validate(raw);
  if (!args) return err(422, 'bad args');
  if (!isOnline(c, w.view)) return err(409, 'phone offline');
  const q = (await enqueue(c, w.id, b.type, args))!;
  if (q.n > QUEUE_MAX) return err(503, 'phone busy'); // ponytail: the extra item still expires with the list
  return json({ id: q.cmd.id }, 202);
}

async function postRes(c: Ctx, id: string, req: Request) {
  const b = await body(req);
  if (!obj(b) || typeof b.id !== 'string' || !/^[0-9a-f]{16}$/.test(b.id) || typeof b.text !== 'string') return err(422, 'bad result');
  await c.kv.set(`res:${id}:${b.id}`, JSON.stringify({ ok: b.ok !== false, text: clean(b.text, 500) }), { ex: 60 });
  return empty();
}

async function postImg(c: Ctx, req: Request) {
  const buf = Buffer.from(await req.arrayBuffer());
  if (buf.length > IMG_MAX) return err(413, 'image too large');
  if (buf.length < 4 || buf[0] !== 0xff || buf[1] !== 0xd8 || buf[2] !== 0xff) return err(422, 'jpeg only');
  const hash = sha(buf);
  await c.kv.set(`img:${hash}`, buf.toString('base64'), { ex: IMG_TTL });
  return json({ hash });
}

/** Unauthenticated on purpose: <img> can't send headers, and the sha256 of the bytes is the capability. */
async function getImg(c: Ctx, hash: string) {
  if (!HASH_RE.test(hash)) return err(404, 'not found');
  const b64 = await c.kv.get(`img:${hash}`);
  if (!b64) return err(404, 'not found');
  return new Response(Buffer.from(b64, 'base64'), {
    headers: { 'content-type': 'image/jpeg', 'cache-control': 'private, max-age=3600, immutable', 'x-content-type-options': 'nosniff' },
  });
}

/** Dispatch for app/api/r/[...path]/route.ts. */
export async function handle(c: Ctx, req: Request, path: string[]): Promise<Response> {
  const route = `${req.method} ${path[0] ?? ''}`;
  switch (route) {
    case 'POST pair': return pair(c, req);
    case 'POST claim': return claim(c, req);
    case 'GET state': return state(c, req);
    case 'POST cmd': return command(c, req);
    case 'GET img': return getImg(c, path[1] ?? '');
  }
  const phone = ['POST unpair', 'POST view', 'GET poll', 'POST img', 'POST res'].includes(route);
  if (!phone) return err(404, 'not found');
  const id = phoneAuth(c, req);
  if (!id) return err(401, 'bad device credentials');
  switch (route) {
    case 'POST unpair': return unpair(c, id);
    case 'POST view': return postView(c, id, req);
    case 'GET poll': return poll(c, id);
    case 'POST res': return postRes(c, id, req);
    default: return postImg(c, req);
  }
}
