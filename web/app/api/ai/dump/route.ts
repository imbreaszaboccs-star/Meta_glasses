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
  if (m.kind === "task") {
    // Notes: dedicated transcription model, verbatim; any failure or empty result falls back to the dump prompt.
    const t0 = performance.now();
    try {
      const [text, usage] = await getAi().transcribe({ model: s.transcribeModel, audio, audioMime: audioMime(form) });
      console.info(`transcribe ok ${JSON.stringify(usage)} chars=${text.length} ms=${Math.round(performance.now() - t0)} audio=${audio.length}`);
      if (text) return json({ text });
    } catch (e) {
      console.warn(`transcribe failed model=${s.transcribeModel} err=${e instanceof Error ? `${e.name}: ${e.message}` : "?"}`);
    }
  }
  const result = await withFallback("dump", s, (model, thinking) => getAi().dump({
    model, thinking, audio, audioMime: audioMime(form), kind: m.kind,
  }), `audio=${audio.length} kind=${m.kind}`);
  return json(result);
});
