// Meta AI connector: OAuth (pairing code + PKCE) and MCP tools relayed to the phone.
import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { handle, type Ctx } from '@/lib/relay';
import { handleMcp } from '@/lib/mcp';
import { fakeKv } from './test-kv';

const TOKEN = 'test-token';
const VERIFIER = 'v'.repeat(50);
const CHALLENGE = createHash('sha256').update(VERIFIER).digest('base64url');
const RU = 'https://muse.example/cb';

function env() {
  const clock = { t: 1_000_000_000 };
  const c: Ctx = { kv: fakeKv(() => clock.t), now: () => clock.t, secret: 's3cret', appToken: TOKEN, publicUrl: 'https://lens.example' };
  const relay = (method: string, path: string, h: Record<string, string> = {}, body?: unknown) =>
    handle(c, new Request(`https://x/api/r/${path}`, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) }), path.split('/'));
  const mcp = (method: string, path: string, opts: { h?: Record<string, string>; body?: string } = {}) =>
    handleMcp(c, new Request(`https://lens.example/api/mcp/${path}`, { method, headers: opts.h, body: opts.body, redirect: 'manual' }), path ? path.split('?')[0].split('/') : []);
  const pair = async () => (await (await relay('POST', 'pair', { authorization: `Bearer ${TOKEN}` }, {})).json()) as { device_id: string; phone_secret: string; code: string };
  const form = (o: Record<string, string>) => new URLSearchParams(o).toString();
  async function link(code: string) {
    const r = await mcp('POST', 'authorize', { body: form({ code, redirect_uri: RU, state: 'st', code_challenge: CHALLENGE, client_id: 'c' }) });
    return r;
  }
  async function token(authCode: string, verifier = VERIFIER) {
    return mcp('POST', 'token', { body: form({ grant_type: 'authorization_code', code: authCode, code_verifier: verifier, redirect_uri: RU }) });
  }
  async function linked() {
    const p = await pair();
    const loc = new URL((await link(p.code)).headers.get('location')!);
    const t = (await (await token(loc.searchParams.get('code')!)).json()).access_token as string;
    return { p, t };
  }
  const rpc = (t: string, body: unknown) => mcp('POST', '', { h: { authorization: `Bearer ${t}`, 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const phoneH = (p: { device_id: string; phone_secret: string }) => ({ 'x-device-id': p.device_id, authorization: `Bearer ${p.phone_secret}` });
  return { c, clock, relay, mcp, pair, link, token, linked, rpc, phoneH };
}

describe('oauth', () => {
  it('discovery metadata', async () => {
    const e = env();
    expect((await (await e.mcp('GET', 'prm')).json()).authorization_servers).toEqual(['https://lens.example']);
    expect((await (await e.mcp('GET', 'asm')).json()).code_challenge_methods_supported).toEqual(['S256']);
  });

  it('authorize form requires https redirect and PKCE', async () => {
    const e = env();
    const q = (o: Record<string, string>) => e.mcp('GET', `authorize?${new URLSearchParams(o)}`);
    const ok = await q({ response_type: 'code', redirect_uri: RU, code_challenge: CHALLENGE, code_challenge_method: 'S256', state: '"><script>' });
    expect(ok.status).toBe(200);
    const html = await ok.text();
    expect(html).not.toContain('"><script>');
    expect((await q({ response_type: 'code', redirect_uri: 'http://evil.example/cb', code_challenge: CHALLENGE })).status).toBe(400);
    expect((await q({ response_type: 'code', redirect_uri: RU })).status).toBe(400);
  });

  it('pairing code → auth code → token; PKCE, expiry and single-use pairing code enforced', async () => {
    const e = env();
    const p = await e.pair();
    const r = await e.link(p.code.toLowerCase());
    expect(r.status).toBe(302);
    const loc = new URL(r.headers.get('location')!);
    expect(loc.origin + loc.pathname).toBe(RU);
    expect(loc.searchParams.get('state')).toBe('st');
    const code = loc.searchParams.get('code')!;
    expect((await e.token(code, 'w'.repeat(50))).status).toBe(400);
    const t = await e.token(code);
    expect(t.status).toBe(200);
    expect((await t.json()).access_token).toMatch(/^m\./);
    expect((await e.link(p.code)).status).toBe(200); // pairing code already used → form again with error
    e.clock.t += 61_000;
    expect((await e.token(code)).status).toBe(400);
    expect((await e.token(code.replace(/.$/, (ch) => (ch === 'A' ? 'B' : 'A')))).status).toBe(400);
  });

  it('dynamic registration only for safe redirect uris', async () => {
    const e = env();
    expect((await e.mcp('POST', 'register', { body: JSON.stringify({ redirect_uris: [RU] }) })).status).toBe(201);
    expect((await e.mcp('POST', 'register', { body: JSON.stringify({ redirect_uris: ['javascript:alert(1)'] }) })).status).toBe(400);
  });
});

describe('mcp', () => {
  it('needs a connector token (web tokens and garbage rejected)', async () => {
    const e = env();
    const r = await e.rpc('nope', { jsonrpc: '2.0', id: 1, method: 'tools/list' });
    expect(r.status).toBe(401);
    expect(r.headers.get('www-authenticate')).toContain('/.well-known/oauth-protected-resource');
    const p = await e.pair();
    const web = (await (await e.relay('POST', 'claim', {}, { code: p.code })).json()).web_token;
    expect((await e.rpc(web, { jsonrpc: '2.0', id: 1, method: 'tools/list' })).status).toBe(401);
  });

  it('initialize, list, notifications', async () => {
    const e = env();
    const { t } = await e.linked();
    const init = await (await e.rpc(t, { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18' } })).json();
    expect(init.result.capabilities).toEqual({ tools: {} });
    const list = await (await e.rpc(t, { jsonrpc: '2.0', id: 2, method: 'tools/list' })).json();
    expect(list.result.tools.map((x: { name: string }) => x.name)).toEqual(['help', 'start_focus', 'stop_focus', 'add_note', 'last_hint']);
    expect((await e.rpc(t, { jsonrpc: '2.0', method: 'notifications/initialized' })).status).toBe(202);
    expect((await (await e.rpc(t, { jsonrpc: '2.0', id: 3, method: 'nope' })).json()).error.code).toBe(-32601);
  });

  it('tool call is relayed to the phone and its answer returned', async () => {
    const e = env();
    const { p, t } = await e.linked();
    const off = await (await e.rpc(t, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'last_hint', arguments: {} } })).json();
    expect(off.result.isError).toBe(true);
    await e.relay('POST', 'view', e.phoneH(p), { state: {} });
    const pending = e.rpc(t, { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'add_note', arguments: { text: 'Buy milk‮ https://x.io' } } });
    let cmds: { id: string; type: string; args: unknown; reply?: boolean }[] = [];
    for (let i = 0; i < 20 && !cmds.length; i++) {
      await new Promise((r) => setTimeout(r, 20));
      cmds = (await (await e.relay('GET', 'poll', e.phoneH(p))).json()).cmds;
    }
    expect(cmds).toEqual([{ id: cmds[0].id, type: 'add_note', args: { text: 'Buy milk' }, reply: true }]);
    expect((await e.relay('POST', 'res', e.phoneH(p), { id: cmds[0].id, ok: true, text: 'Saved.' })).status).toBe(204);
    const r = await (await pending).json();
    expect(r.result).toEqual({ content: [{ type: 'text', text: 'Saved.' }], isError: false });
    const bad = await (await e.rpc(t, { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'add_note', arguments: {} } })).json();
    expect(bad.result.isError).toBe(true);
  });

  it('unpair revokes the connector token', async () => {
    const e = env();
    const { p, t } = await e.linked();
    await e.relay('POST', 'unpair', e.phoneH(p));
    expect((await e.rpc(t, { jsonrpc: '2.0', id: 1, method: 'tools/list' })).status).toBe(401);
  });
});
