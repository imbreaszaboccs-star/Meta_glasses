import { getAi } from "@/lib/ai";
import { env } from "@/lib/env";
import { HttpError, formOf, json, metaOf, readFile, route, withFallback } from "@/lib/http";
import { BadMeta, checkMeta } from "@/lib/meta";

export const runtime = "nodejs";
export const maxDuration = 60;

export const POST = route(async (req) => {
  const s = env();
  const form = await formOf(req);
  let m;
  try {
    m = checkMeta(metaOf(form));
  } catch (e) {
    throw e instanceof BadMeta ? new HttpError(422, "bad meta") : e;
  }
  const photo = await readFile(form, "photo", s.maxPhotoMb);
  if (!photo) throw new HttpError(422, "send a photo");
  const result = await withFallback("check", s, (model, thinking) => getAi().check({
    model, thinking, photo, locale: m.locale, goal: m.goal, mistakes: m.mistakes, quest: m.quest,
  }), `photo=${photo.length}`);
  return json(result);
});
