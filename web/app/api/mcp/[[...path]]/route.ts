import { getCtx } from '@/lib/ctx';
import { handleMcp } from '@/lib/mcp';

export const runtime = 'nodejs';
export const maxDuration = 60; // tool calls long-poll the phone for up to ~25 s

type P = { params: Promise<{ path?: string[] }> };
const run = async (req: Request, { params }: P) => handleMcp(getCtx(), req, (await params).path ?? []);
export { run as GET, run as POST };
