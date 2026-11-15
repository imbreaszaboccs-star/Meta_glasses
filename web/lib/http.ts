import { appAuth } from "@/lib/auth";

export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

const NO_STORE = { "Cache-Control": "no-store" };
export const json = (body: unknown, status = 200) => Response.json(body, { status, headers: NO_STORE });

/** Auth + HttpError -> {detail} JSON, like FastAPI. */
export const route = (fn: (req: Request) => Promise<Response>) => async (req: Request) => {
  const denied = appAuth(req);
  if (denied) return denied;
  try {
    return await fn(req);
  } catch (e) {
    if (e instanceof HttpError) return json({ detail: e.message }, e.status);
    console.error("unhandled", e instanceof Error ? e.name : typeof e);
    return json({ detail: "internal error" }, 500);
  }
};

export async function formOf(req: Request): Promise<FormData> {
  try {
    return await req.formData();
  } catch {
    throw new HttpError(422, "bad multipart body");
  }
}

export function metaOf(form: FormData): string {
  const m = form.get("meta") ?? "{}";
  if (typeof m !== "string" || m.length > 16000) throw new HttpError(422, "bad meta");
  return m;
}

/** Uploaded file bytes (null if absent/empty); 413 when over `maxMb`. */
export async function readFile(form: FormData, field: string, maxMb: number) {
  const f = form.get(field);
  if (!(f instanceof File)) return null;
  if (f.size > maxMb * 1024 * 1024) throw new HttpError(413, `${field} larger than ${maxMb} MB`);
  const data = Buffer.from(await f.arrayBuffer());
  return data.length ? data : null;
}

export function audioMime(form: FormData): string {
  const f = form.get("audio");
  const mime = (f instanceof File && f.type) || "audio/wav";
  return mime === "application/octet-stream" ? "audio/wav" : mime;
}

/** Primary model, then the fallback model. Logs metadata only (never photos, audio or text). */
export async function withFallback<T>(
  name: string,
  s: { hintModel: string; hintThinking: string; hintFallbackModel: string; hintFallbackThinking: string },
  call: (model: string, thinking: string) => Promise<[T, Record<string, unknown>]>,
  sizes = "",
): Promise<T> {
  const attempts = [
    [s.hintModel, s.hintThinking],
    [s.hintFallbackModel, s.hintFallbackThinking],
  ];
  const t0 = performance.now();
  for (const [i, [model, thinking]] of attempts.entries()) {
    try {
      const [result, usage] = await call(model, thinking);
      console.info(`${name} ok ${JSON.stringify(usage)} ms=${Math.round(performance.now() - t0)} ${sizes}`);
      return result;
    } catch (e) {
      console.warn(`${name} failed model=${model} attempt=${i} err=${e instanceof Error ? `${e.name}: ${e.message}` : "?"}`);
    }
  }
  throw new HttpError(502, "AI unavailable, try again");
}
