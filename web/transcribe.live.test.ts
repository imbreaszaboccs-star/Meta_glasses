// Live check, skipped by default: LIVE_AUDIO=<wav path> npm test -- transcribe.live (reads GEMINI_API_KEY from .env.local).
import { readFileSync } from "node:fs";
import { expect, it } from "vitest";
import { Gemini } from "@/lib/ai";
import { env } from "@/lib/env";

it.skipIf(!process.env.LIVE_AUDIO)("transcribes a real recording", async () => {
  const key = /^GEMINI_API_KEY=(.+)$/m.exec(readFileSync(".env.local", "utf8"))?.[1].trim();
  const [text, meta] = await new Gemini(key ?? "", 30).transcribe({
    model: env().transcribeModel, audio: readFileSync(process.env.LIVE_AUDIO!), audioMime: "audio/wav",
  });
  console.log(`transcript chars=${text.length} model=${meta.model} in=${meta.in_tokens} out=${meta.out_tokens}`);
  expect(text.length).toBeGreaterThan(0);
  expect(text.length).toBeLessThanOrEqual(300);
}, 30_000);
