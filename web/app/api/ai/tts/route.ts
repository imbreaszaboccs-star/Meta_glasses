import { clean, getAi } from "@/lib/ai";
import { env } from "@/lib/env";
import { HttpError, route } from "@/lib/http";
import { BadMeta, ttsIn } from "@/lib/meta";

export const runtime = "nodejs";
export const maxDuration = 60;

export const POST = route(async (req) => {
  let text;
  try {
    text = clean(ttsIn(await req.json()).text, 500);
  } catch (e) {
    if (e instanceof BadMeta || e instanceof SyntaxError) throw new HttpError(422, "bad body");
    throw e;
  }
  if (!text) throw new HttpError(422, "empty text");
  const s = env();
  const t0 = performance.now();
  let wav: Buffer;
  try {
    wav = await getAi().tts({ model: s.ttsModel, voice: s.ttsVoice, text });
  } catch (e) {
    console.warn(`tts failed err=${e instanceof Error ? `${e.name}: ${e.message}` : "?"}`);
    throw new HttpError(502, "TTS unavailable");
  }
  console.info(`tts ok ms=${Math.round(performance.now() - t0)} bytes=${wav.length}`);
  return new Response(new Uint8Array(wav), { headers: { "Content-Type": "audio/wav", "Cache-Control": "no-store" } });
});
