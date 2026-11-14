// Fáze 0 hardware spike helpers (public/g/spike.html). No data stored; beats only log a counter.
export const runtime = 'nodejs';

type P = { params: Promise<{ kind: string }> };
export async function GET(req: Request, { params }: P) {
  const { kind } = await params;
  if (kind === 'beat') {
    const q = new URL(req.url).searchParams;
    // Vercel logs show whether the glasses kept running JS while the display slept / the app was in background.
    console.info(`spike beat n=${Number(q.get('n')) | 0} vis=${q.get('vis') === 'visible' ? 'visible' : 'hidden'}`);
    return new Response(null, { status: 204, headers: { 'cache-control': 'no-store' } });
  }
  if (kind === 'sse') {
    const enc = new TextEncoder();
    const stream = new ReadableStream({
      async start(ctl) {
        for (let i = 1; i <= 5; i++) {
          ctl.enqueue(enc.encode(`event: tick\ndata: ${i}\n\n`));
          await new Promise((r) => setTimeout(r, 1000));
        }
        ctl.close();
      },
    });
    return new Response(stream, { headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-store' } });
  }
  return new Response(null, { status: 404 });
}
