// In-memory stand-in for the Upstash subset in lib/relay.ts (tests only).
import type { Kv } from '@/lib/relay';

export function fakeKv(now: () => number): Kv & { data: Map<string, { v: string | string[]; exp?: number }> } {
  const data = new Map<string, { v: string | string[]; exp?: number }>();
  const live = (k: string) => {
    const e = data.get(k);
    if (e && e.exp !== undefined && e.exp <= now()) { data.delete(k); return undefined; }
    return e;
  };
  return {
    data,
    async get(k) { const e = live(k); return typeof e?.v === 'string' ? e.v : null; },
    async mget(...ks) { return ks.map((k) => { const e = live(k); return typeof e?.v === 'string' ? e.v : null; }); },
    async set(k, v, o) { data.set(k, { v, exp: o ? now() + o.ex * 1000 : undefined }); return 'OK'; },
    async getdel(k) { const e = live(k); data.delete(k); return typeof e?.v === 'string' ? e.v : null; },
    async del(...ks) { ks.forEach((k) => data.delete(k)); return ks.length; },
    async incr(k) { const e = live(k); const n = Number(e?.v ?? 0) + 1; data.set(k, { v: String(n), exp: e?.exp }); return n; },
    async expire(k, s) { const e = live(k); if (e) e.exp = now() + s * 1000; return 1; },
    async rpush(k, ...v) { const e = live(k); const l = Array.isArray(e?.v) ? e.v : []; l.push(...v); data.set(k, { v: l, exp: e?.exp }); return l.length; },
    async lpop(k, n) { const e = live(k); if (!Array.isArray(e?.v) || !e.v.length) return null; return e.v.splice(0, n); },
  };
}
