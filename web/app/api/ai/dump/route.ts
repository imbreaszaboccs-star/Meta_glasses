import { getAi } from "@/lib/ai";
import { env } from "@/lib/env";
import { HttpError, audioMime, formOf, json, metaOf, readFile, route, withFallback } from "@/lib/http";
import { BadMeta, dumpMeta } from "@/lib/meta";

export const runtime = "nodejs";
export const maxDuration = 60;

export const POST = route(async (req) => {
  const s = env();
  const form = await formOf(req);
  let m;
  try {
    m = dumpMeta(metaOf(form));
  } catch (e) {
    throw e instanceof BadMeta ? new HttpError(422, "bad meta") : e;
  }
  const audio = await readFile(form, "audio", s.maxDumpMb);
  if (!audio) throw new HttpError(422, "send audio");
  const result = await withFallback("dump", s, (model, thinking) => getAi().dump({
    model, thinking, audio, audioMime: audioMime(form), kind: m.kind, locale: m.locale,
  }), `audio=${audio.length} kind=${m.kind}`);
  return json(result);
});
