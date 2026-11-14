import { Redis } from '@upstash/redis';
import { handle, type Ctx, type Kv } from '@/lib/relay';

export const runtime = 'nodejs';

let ctx: Ctx | undefined;
function getCtx(): Ctx {
  if (ctx) return ctx;
  const e = process.env;
  const need = (k: string, v?: string) => { if (!v) throw new Error(`${k} not set`); return v; };
  const kv = new Redis({
    url: need('UPSTASH_REDIS_REST_URL', e.UPSTASH_REDIS_REST_URL ?? e.KV_REST_API_URL),
    token: need('UPSTASH_REDIS_REST_TOKEN', e.UPSTASH_REDIS_REST_TOKEN ?? e.KV_REST_API_TOKEN),
    automaticDeserialization: false, // values are our own JSON strings
  }) as unknown as Kv;
  ctx = {
    kv, now: Date.now,
    secret: need('RELAY_SECRET', e.RELAY_SECRET),
    appToken: need('APP_TOKEN', e.APP_TOKEN),
    publicUrl: need('PUBLIC_URL', e.PUBLIC_URL ?? (e.VERCEL_PROJECT_PRODUCTION_URL && `https://${e.VERCEL_PROJECT_PRODUCTION_URL}`)),
  };
  return ctx;
}

type P = { params: Promise<{ path: string[] }> };
const run = async (req: Request, { params }: P) => handle(getCtx(), req, (await params).path);
export { run as GET, run as POST };
