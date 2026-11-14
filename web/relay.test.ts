// Port of backend/test_remote.py onto the Redis relay (in-memory Kv fake, fake clock).
import { describe, expect, it } from 'vitest';
import { handle, type Ctx } from '@/lib/relay';
import { fakeKv } from './test-kv';

const TOKEN = 'test-token';

function env() {
  const clock = { t: 1_000_000_000 };
  const now = () => clock.t;
  const c: Ctx = { kv: fakeKv(now), now, secret: 's3cret', appToken: TOKEN, publicUrl: 'https://lens.example' };
  const call = async (method: string, path: string, opts: { h?: Record<string, string>; json?: unknown; raw?: BodyInit } = {}) => {
    const headers = new Headers(opts.h);
    let body = opts.raw;
    if (opts.json !== undefined) { body = JSON.stringify(opts.json); headers.set('content-type', 'application/json'); }
    const res = await handle(c, new Request(`https://x/api/r/${path}`, { method, headers, body }), path.split('/'));
    const text = await res.text();
    let data: any = null;
    try { data = JSON.parse(text); } catch { /* empty */ }
    return { status: res.status, data, headers: res.headers };
  };
  const pair = async (b: unknown = {}) => {
    const r = await call('POST', 'pair', { h: { authorization: `Bearer ${TOKEN}` }, json: b });
    expect(r.status).toBe(200);
    return r.data as { device_id: string; phone_secret: string; code: string; expires_in: number; web_url: string; install_url: string };
  };
  const phoneH = (p: { device_id: string; phone_secret: string }) => ({ 'x-device-id': p.device_id, authorization: `Bearer ${p.phone_secret}` });
  const webH = (t: string) => ({ authorization: `Bearer ${t}` });
  const claim = (code: string, ip = '1.2.3.4') => call('POST', 'claim', { json: { code }, h: { 'x-real-ip': ip } });
  const pairedWeb = async () => { const p = await pair(); const t = (await claim(p.code)).data.web_token as string; return { p, t }; };
  return { c, clock, call, pair, phoneH, webH, claim, pairedWeb };
}

describe('relay', () => {
  it('pair requires app token', async () => {
    const e = env();
    expect((await e.call('POST', 'pair', { json: {} })).status).toBe(401);
  });

  it('pair returns install url and reuses device; new code replaces old; forged secret never hijacks', async () => {
    const e = env();
    const p = await e.pair();
    expect(p.code).toHaveLength(8);
    expect(p.expires_in).toBe(600);
    expect(p.web_url).toBe(`https://lens.example/g?code=${p.code}`);
    expect(p.install_url.startsWith('fb-viewapp://web_app_deep_link?appName=FocusLens&appUrl=https%3A%2F%2Flens.example')).toBe(true);
    const again = await e.pair({ device_id: p.device_id, phone_secret: p.phone_secret });
    expect(again.device_id).toBe(p.device_id);
    expect(again.code).not.toBe(p.code);
    expect((await e.claim(p.code)).status).toBe(404);
    const forged = await e.pair({ device_id: p.device_id, phone_secret: 'wrong' });
    expect(forged.device_id).not.toBe(p.device_id);
  });

  it('claim is single use, case-insensitive and expires', async () => {
    const e = env();
    const p = await e.pair();
    const r = await e.claim(p.code.toLowerCase());
    expect(r.status).toBe(200);
    expect(r.data.web_token.length).toBeGreaterThan(30);
    expect((await e.claim(p.code)).status).toBe(404);
    const p2 = await e.pair({ device_id: p.device_id, phone_secret: p.phone_secret });
    e.clock.t += 601_000;
    expect((await e.claim(p2.code)).status).toBe(404);
  });

  it('claim is rate limited per ip', async () => {
    const e = env();
    const codes = [];
    for (let i = 0; i < 11; i++) codes.push((await e.claim('AAAAAAAA')).status);
    expect(codes.slice(0, 10)).toEqual(Array(10).fill(404));
    expect(codes[10]).toBe(429);
    expect((await e.claim('AAAAAAAA', '9.9.9.9')).status).toBe(404);
  });

  it('rejects bad web and phone credentials', async () => {
    const e = env();
    const p = await e.pair();
    expect((await e.call('GET', 'state')).status).toBe(401);
    expect((await e.call('GET', 'state', { h: e.webH('nope') })).status).toBe(401);
    const forged = `${p.device_id}.0.${'A'.repeat(43)}`;
    expect((await e.call('GET', 'state', { h: e.webH(forged) })).status).toBe(401);
    expect((await e.call('POST', 'view', { h: { 'x-device-id': 'x', authorization: 'Bearer y' }, json: { state: {} } })).status).toBe(401);
    expect((await e.call('POST', 'view', { h: { ...e.phoneH(p), authorization: 'Bearer wrong' }, json: { state: {} } })).status).toBe(401);
  });

  it('state roundtrip and online window', async () => {
    const e = env();
    const { p, t } = await e.pairedWeb();
    let s = await e.call('GET', 'state', { h: e.webH(t) });
    expect(s.data).toEqual({ phone_online: false, state_age_s: null, state: null, acks: [] });
    expect(s.headers.get('cache-control')).toBe('no-store');
    const state = { v: 1, focus: { running: true, goal: 'Stats' } };
    expect((await e.call('POST', 'view', { h: e.phoneH(p), json: { state } })).status).toBe(204);
    s = await e.call('GET', 'state', { h: e.webH(t) });
    expect(s.data.phone_online).toBe(true);
    expect(s.data.state).toEqual(state);
    expect(s.data.state_age_s).toBe(0);
    e.clock.t += 61_000;
    expect((await e.call('GET', 'state', { h: e.webH(t) })).data.phone_online).toBe(false);
  });

  it.each([
    ['x'.repeat(64 * 1024 + 1), 413], ['{not json', 422], ['[1,2]', 422], ['{"state":[1]}', 422],
  ])('view validation %#', async (raw, code) => {
    const e = env();
    const p = await e.pair();
    expect((await e.call('POST', 'view', { h: e.phoneH(p), raw })).status).toBe(code);
  });

  it('command needs an online phone', async () => {
    const e = env();
    const { t } = await e.pairedWeb();
    expect((await e.call('POST', 'cmd', { h: e.webH(t), json: { type: 'help' } })).status).toBe(409);
  });

  it.each([
    { type: 'rm -rf' }, { type: 'start_focus', args: { mode: 'pirate' } }, { type: 'start_focus', args: { evil: 1 } },
    { type: 'inbox_done', args: { id: '../x', done: true } }, { type: 'inbox_done', args: { id: 'abc' } },
    { type: 'set_config', args: { nudges: 'yes please' } }, { type: 'help', args: [1] }, { type: 'gesture', args: { g: 'jump' } },
    { type: 'gesture' }, { type: 'sim' }, { type: 'sim', args: { name: 'reboot' } },
    { type: 'set_config', args: { bufferSec: 999 } }, { type: 'set_config', args: { language: 'xx' } },
    { type: 'imu_label', args: { label: 'dance' } }, { type: 'card_delete', args: { id: '../../x' } }, { type: 'web_seen', args: {} }, { type: 'voice_note', args: { text: 'x' } },
  ])('command whitelist rejects %j', async (cmd) => {
    const e = env();
    const { p, t } = await e.pairedWeb();
    await e.call('POST', 'view', { h: e.phoneH(p), json: { state: {} } });
    expect((await e.call('POST', 'cmd', { h: e.webH(t), json: cmd })).status).toBe(422);
  });

  it('command is delivered by poll, cleaned, and acked through the view', async () => {
    const e = env();
    const { p, t } = await e.pairedWeb();
    await e.call('POST', 'view', { h: e.phoneH(p), json: { state: {} } });
    const r = await e.call('POST', 'cmd', { h: e.webH(t), json: { type: 'start_focus', args: { mode: 'lecture', goal: 'Entropy‮ https://x.io' } } });
    expect(r.status).toBe(202);
    const g = await e.call('POST', 'cmd', { h: e.webH(t), json: { type: 'gesture', args: { g: 'select' } } });
    const got = await e.call('GET', 'poll', { h: e.phoneH(p) });
    expect(got.data.cmds).toEqual([
      { id: r.data.id, type: 'start_focus', args: { mode: 'lecture', goal: 'Entropy' } },
      { id: g.data.id, type: 'gesture', args: { g: 'select' } },
    ]);
    expect((await e.call('GET', 'poll', { h: e.phoneH(p) })).data.cmds).toEqual([]);
    await e.call('POST', 'view', { h: e.phoneH(p), json: { state: {}, acks: [{ id: r.data.id, ok: true }, { id: 'x', ok: false, error: 'no‮' }] } });
    const acks = (await e.call('GET', 'state', { h: e.webH(t) })).data.acks;
    expect(acks).toEqual([{ id: r.data.id, ok: true, error: null }, { id: 'x', ok: false, error: 'no' }]);
  });

  it('voice_note takes no args and is delivered', async () => {
    const e = env();
    const { p, t } = await e.pairedWeb();
    await e.call('POST', 'view', { h: e.phoneH(p), json: { state: {} } });
    expect((await e.call('POST', 'cmd', { h: e.webH(t), json: { type: 'voice_note', args: {} } })).status).toBe(202);
    expect((await e.call('GET', 'poll', { h: e.phoneH(p) })).data.cmds.map((c: any) => c.type)).toEqual(['voice_note']);
  });

  it('sim command accepts a known scenario name', async () => {
    const e = env();
    const { p, t } = await e.pairedWeb();
    await e.call('POST', 'view', { h: e.phoneH(p), json: { state: {} } });
    const r = await e.call('POST', 'cmd', { h: e.webH(t), json: { type: 'sim', args: { name: 'lapse_l2' } } });
    expect(r.status).toBe(202);
    expect((await e.call('GET', 'poll', { h: e.phoneH(p) })).data.cmds).toEqual([{ id: r.data.id, type: 'sim', args: { name: 'lapse_l2' } }]);
  });

  it('unpair revokes web tokens, wipes live data and the id cannot be re-paired', async () => {
    const e = env();
    const { p, t } = await e.pairedWeb();
    await e.call('POST', 'view', { h: e.phoneH(p), json: { state: { secret: 'notes' } } });
    expect((await e.call('POST', 'unpair', { h: e.phoneH(p) })).status).toBe(204);
    expect((await e.call('GET', 'state', { h: e.webH(t) })).status).toBe(401);
    expect(await e.c.kv.get(`view:${p.device_id}`)).toBeNull();
    const again = await e.pair({ device_id: p.device_id, phone_secret: p.phone_secret });
    expect(again.device_id).not.toBe(p.device_id);
  });

  it('images: jpeg only, size cap, served by hash without auth', async () => {
    const e = env();
    const p = await e.pair();
    expect((await e.call('POST', 'img', { h: e.phoneH(p), raw: new Uint8Array([1, 2, 3, 4]) })).status).toBe(422);
    expect((await e.call('POST', 'img', { raw: new Uint8Array([0xff, 0xd8, 0xff, 0]) })).status).toBe(401);
    const jpg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
    const r = await e.call('POST', 'img', { h: e.phoneH(p), raw: jpg });
    expect(r.data.hash).toMatch(/^[0-9a-f]{64}$/);
    const res = await handle(e.c, new Request(`https://x/api/r/img/${r.data.hash}`), ['img', r.data.hash]);
    expect(res.headers.get('content-type')).toBe('image/jpeg');
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(jpg);
    expect((await e.call('GET', 'img/../../etc')).status).toBe(404);
  });
});
