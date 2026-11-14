import { getCtx } from '@/lib/ctx';
import { handle } from '@/lib/relay';

export const runtime = 'nodejs';

type P = { params: Promise<{ path: string[] }> };
const run = async (req: Request, { params }: P) => handle(getCtx(), req, (await params).path);
export { run as GET, run as POST };
