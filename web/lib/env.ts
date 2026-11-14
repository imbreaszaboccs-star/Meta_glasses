// Read on every call (not cached) so tests and Vercel env changes take effect.
const num = (k: string, d: number) => {
  const v = Number(process.env[k]);
  return process.env[k] && Number.isFinite(v) ? v : d;
};
const str = (k: string, d: string) => process.env[k] || d;

export const env = () => ({
  geminiApiKey: str("GEMINI_API_KEY", ""),
  appToken: str("APP_TOKEN", ""), // shared bearer token the phone app sends
  hintModel: str("HINT_MODEL", "gemini-3.5-flash-lite"),
  hintThinking: str("HINT_THINKING", "minimal"),
  hintFallbackModel: str("HINT_FALLBACK_MODEL", "gemini-3.1-flash-lite"),
  hintFallbackThinking: str("HINT_FALLBACK_THINKING", "minimal"),
  ttsModel: str("TTS_MODEL", "gemini-3.8-flash-lite-tts"),
  ttsVoice: str("TTS_VOICE", "Kore"),
  maxAudioMb: num("MAX_AUDIO_MB", 4), // Vercel request body limit is 4.5 MB
  maxDumpMb: num("MAX_DUMP_MB", 2),
  maxPhotoMb: num("MAX_PHOTO_MB", 4),
  geminiTimeoutS: num("GEMINI_TIMEOUT_S", 10), // per attempt; a hung primary must fail over fast
  publicUrl: str("PUBLIC_URL", "https://lens.projectviridis.dev"),
});
