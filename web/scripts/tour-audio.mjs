// Generates/validates the gesture-tour voice clips (public/g/tour/<lang>-<id>.wav) from public/g/tour/lines.json.
// node --env-file=.env.local scripts/tour-audio.mjs [--force] [--check]
import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { GoogleGenAI } from "@google/genai";

const dir = new URL("../public/g/tour/", import.meta.url);
const lines = JSON.parse(readFileSync(new URL("lines.json", dir), "utf8"));
const force = process.argv.includes("--force");
const check = process.argv.includes("--check");
const names = Object.entries(lines).flatMap(([lang, ids]) => Object.keys(ids).map((id) => `${lang}-${id}.wav`));

function pcmToWav(pcm, rate = 24000) {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0, "latin1");
  h.writeUInt32LE(36 + pcm.length, 4);
  h.write("WAVEfmt ", 8, "latin1");
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20);
  h.writeUInt16LE(1, 22);
  h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28);
  h.writeUInt16LE(2, 32);
  h.writeUInt16LE(16, 34);
  h.write("data", 36, "latin1");
  h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

if (check) {
  const bad = [];
  for (const n of names) {
    const f = new URL(n, dir);
    if (!existsSync(f)) { bad.push(`missing ${n}`); continue; }
    const b = readFileSync(f);
    if (b.subarray(0, 4).toString("latin1") !== "RIFF") bad.push(`not RIFF ${n}`);
    if (b.length < 20 * 1024) bad.push(`too small ${n} (${b.length} B)`);
  }
  for (const f of readdirSync(dir)) if (f.endsWith(".wav") && !names.includes(f)) bad.push(`orphan ${f}`);
  if (bad.length) { console.error(bad.join("\n")); process.exit(1); }
  console.log(`ok: ${names.length} clips`);
  process.exit(0);
}

const model = process.env.TTS_MODEL || "gemini-3.8-flash-lite-tts";
const voice = process.env.TTS_VOICE || "Kore";
const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY, httpOptions: { apiVersion: "v1beta" } });

async function synth(text) {
  const res = await ai.models.generateContent({
    model,
    contents: text, // plain text only: a style instruction in front would be read aloud
    config: { responseModalities: ["AUDIO"], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } } } },
  });
  const data = res.candidates?.[0]?.content?.parts?.find((p) => p.inlineData?.data)?.inlineData?.data;
  if (!data) throw new Error("no audio in response");
  const pcm = Buffer.from(data, "base64");
  return pcm.subarray(0, 4).toString("latin1") === "RIFF" ? pcm : pcmToWav(pcm, 24000);
}

for (const [lang, ids] of Object.entries(lines)) {
  for (const [id, { say }] of Object.entries(ids)) {
    const name = `${lang}-${id}.wav`, f = new URL(name, dir);
    if (existsSync(f) && !force) continue;
    let wav;
    try { wav = await synth(say); } catch (e) { console.warn(`retry ${name}: ${e.message}`); wav = await synth(say); }
    writeFileSync(f, wav);
    console.log(name, statSync(f).size);
  }
}
