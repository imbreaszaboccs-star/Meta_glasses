import { createHash, timingSafeEqual } from "node:crypto";
import { env } from "@/lib/env";

const digest = (s: string) => createHash("sha256").update(s).digest();

/** null when `Authorization: Bearer <APP_TOKEN>` matches, else a 401 response. */
export function appAuth(req: Request): Response | null {
  const expected = env().appToken;
  const header = req.headers.get("authorization") ?? "";
  const token = (header.startsWith("Bearer ") ? header.slice(7) : header).trim();
  if (expected && timingSafeEqual(digest(token), digest(expected))) return null;
  return Response.json({ detail: "bad token" }, { status: 401, headers: { "Cache-Control": "no-store" } });
}
