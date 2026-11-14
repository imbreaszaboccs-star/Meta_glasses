/**
 * Meta AI (Muse) connector: "Hey Meta, ask FocusLens…" (V2_DIRECTION.md §3.4).
 *
 * MCP over Streamable HTTP, JSON responses only (no SSE): POST /api/mcp with JSON-RPC.
 * Tools are relayed to the phone (the only place with data): RPUSH cmds → phone runs it → POST res → we long-poll.
 *
 * Account linking = OAuth 2.1 authorization code + PKCE where the "login" is the 8-char pairing code from the
 * phone. Stateless: the auth code is an HMAC-signed blob (60 s), the access token is `m.id.gen.mac` and is
 * revoked by unpair (gen). ponytail: auth codes are not single-use (PKCE + 60 s bound them); dynamic client
 * registration accepts anyone (clients are public, PKCE-only). Adjust once Meta publishes the Muse connector spec.
 */
import { awaitResult, claimCode, enqueue, isOnline, museAuth, museToken, safeEqual, sign, type Ctx } from '@/lib/relay';
import { createHash } from 'node:crypto';

const PROTOCOL = '2025-06-18';
const WAIT_MS = 25_000; // ponytail: Muse tool-call timeout unknown (Fáze 0 #10)
const CODE_TTL_MS = 60_000;

const TOOLS = [
  { name: 'help', description: 'Explain what the user just missed in the lecture or what the next small step on their task is. Use for "what did I miss", "help me", "what now".',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false } },
  { name: 'start_focus', description: 'Start a FocusLens focus block on the glasses.',
    inputSchema: { type: 'object', properties: {
      mode: { type: 'string', enum: ['auto', 'lecture', 'homework', 'desk'] },
      goal: { type: 'string', description: 'What the user wants to get done, short.' } }, additionalProperties: false } },
  { name: 'stop_focus', description: 'Stop the running focus block.', inputSchema: { type: 'object', properties: {}, additionalProperties: false } },
  { name: 'add_note', description: 'Save a short note or to-do to the FocusLens inbox (brain dump) without interrupting the focus block.',
    inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false } },
  { name: 'last_hint', description: 'Repeat the last FocusLens hint.', inputSchema: { type: 'object', properties: {}, additionalProperties: false } },
];

const j = (body: unknown, status = 200, extra: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...extra } });
const base = (c: Ctx) => c.publicUrl.replace(/\/+$/, '');
const b64 = (s: string) => Buffer.from(s).toString('base64url');
const unb64 = (s: string) => Buffer.from(s, 'base64url').toString();
const esc = (s: string) => s.replace(/[&<>"']/g, (ch) => `&#${ch.charCodeAt(0)};`);

/** https anywhere, http only for loopback (desktop MCP clients). */
function okRedirect(u: string) {
  try {
    const x = new URL(u);
    return x.protocol === 'https:' || (x.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(x.hostname));
  } catch { return false; }
}

// ---------------------------------------------------------------- MCP JSON-RPC

type Rpc = { jsonrpc?: string; id?: string | number | null; method?: string; params?: Record<string, unknown> };
const text = (t: string, isError = false) => ({ content: [{ type: 'text', text: t }], isError });

async function callTool(c: Ctx, dev: { id: string; view: string | null }, name: string, args: Record<string, unknown>) {
  if (!TOOLS.some((t) => t.name === name)) return null;
  if (!isOnline(c, dev.view)) return text('FocusLens on the phone is offline. Open the FocusLens app on your phone and try again.', true);
  const q = await enqueue(c, dev.id, name, args, true);
  if (!q) return text('Invalid arguments.', true);
  const r = await awaitResult(c, dev.id, q.cmd.id, WAIT_MS);
  if (!r) return text('The phone did not answer in time. Try again in a moment.', true);
  return text(r.text, !r.ok);
}

async function rpc(c: Ctx, dev: { id: string; view: string | null }, m: Rpc) {
  const ok = (result: unknown) => ({ jsonrpc: '2.0', id: m.id ?? null, result });
  const fail = (code: number, message: string) => ({ jsonrpc: '2.0', id: m.id ?? null, error: { code, message } });
  switch (m.method) {
    case 'initialize':
      return ok({ protocolVersion: typeof m.params?.protocolVersion === 'string' ? m.params.protocolVersion : PROTOCOL,
        capabilities: { tools: {} }, serverInfo: { name: 'focuslens', version: '2.0.0' },
        instructions: 'FocusLens is an ADHD focus assistant on the user\'s smart glasses. Data lives on the user\'s phone.' });
    case 'ping': return ok({});
    case 'tools/list': return ok({ tools: TOOLS });
    case 'tools/call': {
      const p = m.params ?? {};
      const args = p.arguments && typeof p.arguments === 'object' && !Array.isArray(p.arguments) ? p.arguments as Record<string, unknown> : {};
      const r = typeof p.name === 'string' ? await callTool(c, dev, p.name, args) : null;
      return r ? ok(r) : fail(-32602, 'unknown tool');
    }
    default: return fail(-32601, 'method not found');
  }
}

async function mcp(c: Ctx, req: Request) {
  const dev = await museAuth(c, req);
  if (!dev) return j({ error: 'invalid_token' }, 401, {
    'www-authenticate': `Bearer resource_metadata="${base(c)}/.well-known/oauth-protected-resource"` });
  let m: unknown;
  try { m = await req.json(); } catch { return j({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }, 400); }
  const msgs = (Array.isArray(m) ? m : [m]).slice(0, 10) as Rpc[];
  const out = [];
  for (const x of msgs) if (x && typeof x === 'object' && x.id !== undefined && x.method) out.push(await rpc(c, dev, x));
  if (!out.length) return new Response(null, { status: 202 }); // notifications only
  return j(Array.isArray(m) ? out : out[0]);
}

// ---------------------------------------------------------------- OAuth (pairing code as the login)

const CHALLENGE_RE = /^[A-Za-z0-9_-]{43,128}$/;

function authorizeForm(q: URLSearchParams, error = '') {
  const hidden = ['client_id', 'redirect_uri', 'state', 'code_challenge']
    .map((k) => `<input type="hidden" name="${k}" value="${esc(q.get(k) ?? '')}">`).join('');
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Link FocusLens</title><style>body{font:18px system-ui;background:#000;color:#fff;max-width:420px;margin:40px auto;padding:0 16px}
input[name=code]{font:28px monospace;letter-spacing:4px;width:100%;padding:10px;text-transform:uppercase}button{font:20px system-ui;margin-top:16px;padding:10px 20px}
.e{color:#ff8a80}</style></head><body><h1>Link FocusLens to Meta AI</h1>
<p>On your phone: FocusLens → Settings → Add to glasses menu. Type the 8-character code shown there.</p>
${error ? `<p class="e">${esc(error)}</p>` : ''}<form method="post">${hidden}<input name="code" maxlength="8" autocomplete="off" autofocus required>
<button>Link</button></form></body></html>`;
  return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store',
    'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' https: http://localhost:* http://127.0.0.1:*; frame-ancestors 'none'",
    'referrer-policy': 'no-referrer', 'x-content-type-options': 'nosniff' } });
}

function checkAuthParams(q: URLSearchParams) {
  const ru = q.get('redirect_uri') ?? '';
  if (!okRedirect(ru)) return 'bad redirect_uri';
  if (!CHALLENGE_RE.test(q.get('code_challenge') ?? '')) return 'PKCE code_challenge (S256) required';
  return null;
}

async function authorize(c: Ctx, req: Request) {
  if (req.method === 'GET') {
    const q = new URL(req.url).searchParams;
    const bad = checkAuthParams(q) ?? (q.get('response_type') === 'code' ? null : 'response_type must be code')
      ?? ((q.get('code_challenge_method') ?? 'S256') === 'S256' ? null : 'only S256');
    return bad ? j({ error: 'invalid_request', error_description: bad }, 400) : authorizeForm(q);
  }
  const q = new URLSearchParams(await req.text());
  const bad = checkAuthParams(q);
  if (bad) return j({ error: 'invalid_request', error_description: bad }, 400);
  const r = await claimCode(c, req, q.get('code'));
  if (r instanceof Response) return authorizeForm(q, r.status === 429 ? 'Too many attempts, wait a minute.' : 'Code invalid or expired. Get a new one on the phone.');
  const payload = b64(JSON.stringify({ id: r.id, gen: r.gen, ru: q.get('redirect_uri'), cc: q.get('code_challenge'), exp: c.now() + CODE_TTL_MS }));
  const to = new URL(q.get('redirect_uri')!);
  to.searchParams.set('code', `${payload}.${sign(c, 'a', payload)}`);
  if (q.get('state')) to.searchParams.set('state', q.get('state')!);
  return new Response(null, { status: 302, headers: { location: to.toString(), 'cache-control': 'no-store' } });
}

async function token(c: Ctx, req: Request) {
  const q = new URLSearchParams(await req.text());
  const bad = (d: string) => j({ error: 'invalid_grant', error_description: d }, 400);
  if (q.get('grant_type') !== 'authorization_code') return j({ error: 'unsupported_grant_type' }, 400);
  const [payload, sig] = (q.get('code') ?? '').split('.');
  if (!payload || !sig || !safeEqual(sig, sign(c, 'a', payload))) return bad('bad code');
  let p: { id: string; gen: string; ru: string; cc: string; exp: number };
  try { p = JSON.parse(unb64(payload)); } catch { return bad('bad code'); }
  if (c.now() > p.exp) return bad('code expired');
  if (q.get('redirect_uri') && q.get('redirect_uri') !== p.ru) return bad('redirect_uri mismatch');
  const verifier = q.get('code_verifier') ?? '';
  if (!safeEqual(createHash('sha256').update(verifier).digest('base64url'), p.cc)) return bad('PKCE failed');
  return j({ access_token: museToken(c, p.id, p.gen), token_type: 'Bearer', scope: 'focuslens' });
}

function metadata(c: Ctx, which: string) {
  const b = base(c);
  if (which === 'prm') return j({ resource: `${b}/api/mcp`, authorization_servers: [b], bearer_methods_supported: ['header'] });
  return j({ issuer: b, authorization_endpoint: `${b}/api/mcp/authorize`, token_endpoint: `${b}/api/mcp/token`,
    registration_endpoint: `${b}/api/mcp/register`, response_types_supported: ['code'], grant_types_supported: ['authorization_code'],
    code_challenge_methods_supported: ['S256'], token_endpoint_auth_methods_supported: ['none'], scopes_supported: ['focuslens'] });
}

async function register(req: Request) {
  let b: Record<string, unknown> = {};
  try { b = await req.json(); } catch { /* empty */ }
  const uris = Array.isArray(b.redirect_uris) ? b.redirect_uris.filter((u): u is string => typeof u === 'string') : [];
  if (!uris.length || !uris.every(okRedirect)) return j({ error: 'invalid_redirect_uri' }, 400);
  return j({ client_id: `public-${createHash('sha256').update(uris.join(' ')).digest('hex').slice(0, 16)}`, redirect_uris: uris,
    token_endpoint_auth_method: 'none', grant_types: ['authorization_code'], response_types: ['code'] }, 201);
}

/** Dispatch for app/api/mcp/[[...path]]/route.ts (+ /.well-known rewrites in next.config.ts). */
export async function handleMcp(c: Ctx, req: Request, path: string[]): Promise<Response> {
  const route = `${req.method} ${path[0] ?? ''}`;
  switch (route) {
    case 'POST ': return mcp(c, req);
    case 'GET ': return j({ error: 'use POST (JSON responses only, no SSE stream)' }, 405, { allow: 'POST' });
    case 'GET authorize': case 'POST authorize': return authorize(c, req);
    case 'POST token': return token(c, req);
    case 'POST register': return register(req);
    case 'GET prm': case 'GET asm': return metadata(c, path[0]);
    default: return j({ error: 'not found' }, 404);
  }
}
