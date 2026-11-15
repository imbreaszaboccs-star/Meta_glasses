// Request metadata is untrusted: validated + sanitised here, only whitelisted values reach the prompt.
import { ASK_HINTS, INTENTS, MODE_HINTS, REACTIONS, SITUATIONS, clean, langCode } from "@/lib/ai";

export class BadMeta extends Error {}
const bad = (): never => {
  throw new BadMeta();
};
type Obj = Record<string, unknown>;

const obj = (v: unknown): Obj => (v && typeof v === "object" && !Array.isArray(v) ? (v as Obj) : bad());
const str = (v: unknown, max: number, def = ""): string =>
  v === undefined ? def : typeof v === "string" && v.length <= max ? v : bad();
const list = <T>(v: unknown, min: number, max: number, item: (x: unknown) => T): T[] => {
  if (v === undefined) v = [];
  if (!Array.isArray(v) || v.length < min || v.length > max) bad();
  return (v as unknown[]).map(item);
};
const cleanList = (v: string[], n: number) => v.map((x) => clean(x, n)).filter(Boolean);
const any = (x: unknown) => str(x, Infinity);

function parse(raw: string): Obj {
  try {
    return obj(JSON.parse(raw));
  } catch {
    return bad();
  }
}

function userContext(o: Obj) {
  return {
    locale: langCode(str(o.locale, Infinity, "en")),
    goal: clean(str(o.goal, 1000), 200),
    mistakes: cleanList(list(o.mistakes, 0, 10, any), 120),
  };
}

const minAgo = (v: unknown) =>
  typeof v === "number" && Number.isFinite(v) ? Math.min(600, Math.max(0, Math.round(v))) : 0;

function memoryOf(v: unknown) {
  if (v === undefined || v === null) return undefined;
  const m = obj(v);
  return {
    topic: clean(str(m.topic, Infinity), 80),
    notes: list(m.notes, 0, 10, (x) => {
      const n = obj(x);
      return { min_ago: minAgo(n.min_ago), summary: clean(str(n.summary, Infinity), 300) };
    }).filter((n) => n.summary),
    earlier: list(m.earlier, 0, 8, (x) => {
      const e = obj(x);
      return {
        topic: clean(str(e.topic, Infinity), 80),
        from_min_ago: minAgo(e.from_min_ago),
        to_min_ago: minAgo(e.to_min_ago),
        summary: clean(str(e.summary, Infinity), 400),
      };
    }),
  };
}

export function hintMeta(raw: string) {
  const o = parse(raw);
  const mode = str(o.mode, Infinity, "auto");
  const ask = str(o.ask, Infinity, "help");
  if (o.ts !== undefined && o.ts !== null) str(o.ts, 40);
  return {
    ...userContext(o),
    mode: Object.hasOwn(MODE_HINTS, mode) ? mode : "auto",
    ask: Object.hasOwn(ASK_HINTS, ask) ? ask : "help",
    previous: cleanList(list(o.previous, 0, 5, any), 300),
    memory: memoryOf(o.memory),
    recent: list(o.recent, 0, 6, (x) => {
      const r = obj(x);
      const sit = str(r.situation, Infinity, "other");
      const intent = str(r.intent, Infinity);
      const reaction = str(r.reaction, Infinity);
      const recap = clean(str(r.recap, Infinity), 260);
      const ago = r.min_ago;
      // Optional fields only when set: keeps the prompt short and old phones' payloads unchanged.
      return {
        situation: (SITUATIONS as readonly string[]).includes(sit) ? sit : "other",
        title: clean(str(r.title, Infinity), 200),
        step: clean(str(r.step, Infinity), 200),
        ...((INTENTS as readonly string[]).includes(intent) && { intent }),
        ...(recap && { recap }),
        ...(typeof ago === "number" && Number.isFinite(ago) && { min_ago: Math.min(600, Math.max(0, Math.round(ago))) }),
        ...((REACTIONS as readonly string[]).includes(reaction) && { reaction }),
      };
    }),
  };
}

export function checkMeta(raw: string) {
  const o = parse(raw);
  const q = obj(o.quest ?? bad());
  const steps = cleanList(list(q.steps, 1, 6, (x) => str(x, 2000)), 200);
  const index = q.index;
  if (!steps.length || typeof index !== "number" || !Number.isInteger(index) || index < 0 || index >= steps.length) bad();
  return { ...userContext(o), quest: { title: clean(str(q.title, 1000), 120), steps, index: index as number } };
}

export function dumpMeta(raw: string) {
  const o = parse(raw);
  const locale = langCode(str(o.locale, Infinity, "en"));
  const kind = o.kind ?? "task";
  if (kind !== "task" && kind !== "goal") bad();
  return { kind: kind as "task" | "goal", locale };
}

export function transcribeMeta(raw: string) {
  const o = parse(raw);
  return {
    locale: langCode(str(o.locale, Infinity, "en")),
    prevTopic: clean(str(o.prev_topic, Infinity), 80),
    prevSummary: clean(str(o.prev_summary, Infinity), 300),
  };
}

export function reviewIn(body: unknown) {
  const o = obj(body);
  return {
    locale: langCode(str(o.locale, Infinity, "en")),
    items: list(o.items, 1, 20, (x) => {
      const i = obj(x);
      return {
        title: clean(str(i.title, 2000), 120),
        summary: clean(str(i.summary, 2000), 400),
        correction: clean(str(i.correction, 2000), 400),
        mistake: clean(str(i.mistake, 2000), 120),
      };
    }),
  };
}

export function ttsIn(body: unknown) {
  const o = obj(body);
  const text = str(o.text, 500);
  if (!text) bad();
  return { text };
}
