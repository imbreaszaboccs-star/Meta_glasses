import { json } from "@/lib/http";

export const runtime = "nodejs";

export const GET = () => json({ ok: true });
