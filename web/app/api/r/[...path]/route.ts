import { withCtx } from '@/lib/ctx';
import { handle } from '@/lib/relay';

export const runtime = 'nodejs';

type P = { params: Promise<{ path: string[] }> };
const run = async (req: Request, { params }: P) => withCtx(async (c) => handle(c, req, (await params).path));
export { run as GET, run as POST };
