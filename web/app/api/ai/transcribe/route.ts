import { getAi } from "@/lib/ai";
import { env } from "@/lib/env";
import { HttpError, audioMime, formOf, json, metaOf, readFile, route, withFallback } from "@/lib/http";
import { BadMeta, transcribeMeta } from "@/lib/meta";

export const runtime = "nodejs";
export const maxDuration = 60;

export const POST = route(async (req) => {
  const s = env();
  const form = await formOf(req);
  let m;
  try {
    m = transcribeMeta(metaOf(form));
  } catch (e) {
    throw e instanceof BadMeta ? new HttpError(422, "bad meta") : e;
  }
  const audio = await readFile(form, "audio", s.maxAudioMb);
  if (!audio) throw new HttpError(422, "send audio");
  const result = await withFallback("transcribe", s, (model, thinking) => getAi().transcribe({
    model, thinking, audio, audioMime: audioMime(form), locale: m.locale, prevTopic: m.prevTopic, prevSummary: m.prevSummary,
  }), `audio=${audio.length}`);
  return json(result);
});
