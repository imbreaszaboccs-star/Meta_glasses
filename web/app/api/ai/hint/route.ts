import { getAi } from "@/lib/ai";
import { env } from "@/lib/env";
import { HttpError, audioMime, formOf, json, metaOf, readFile, route, withFallback } from "@/lib/http";
import { BadMeta, hintMeta } from "@/lib/meta";

export const runtime = "nodejs";
export const maxDuration = 60;

export const POST = route(async (req) => {
  const s = env();
  const form = await formOf(req);
  let m;
  try {
    m = hintMeta(metaOf(form));
  } catch (e) {
    throw e instanceof BadMeta ? new HttpError(422, "bad meta") : e;
  }
  const photo = await readFile(form, "photo", s.maxPhotoMb);
  const audio = await readFile(form, "audio", s.maxAudioMb);
  if (!photo && !audio) throw new HttpError(422, "send photo and/or audio");
  const recent = m.recent.filter((r) => r.step);
  const result = await withFallback("hint", s, (model, thinking) => getAi().hint({
    model, thinking, photo, audio, audioMime: audioMime(form), locale: m.locale, mode: m.mode,
    previous: m.previous, recent, goal: m.goal, mistakes: m.mistakes, ask: m.ask, memory: m.memory,
  }), `photo=${photo?.length ?? 0} audio=${audio?.length ?? 0}`);
  return json(result);
});
