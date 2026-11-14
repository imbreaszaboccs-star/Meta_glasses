import { getAi } from "@/lib/ai";
import { env } from "@/lib/env";
import { HttpError, json, route, withFallback } from "@/lib/http";
import { BadMeta, reviewIn } from "@/lib/meta";

export const runtime = "nodejs";
export const maxDuration = 60;

export const POST = route(async (req) => {
  let body;
  try {
    body = reviewIn(await req.json());
  } catch (e) {
    if (e instanceof BadMeta || e instanceof SyntaxError) throw new HttpError(422, "bad body");
    throw e;
  }
  const result = await withFallback("review", env(), (model, thinking) => getAi().review({
    model, thinking, locale: body.locale, items: body.items,
  }), `items=${body.items.length}`);
  return json(result);
});
