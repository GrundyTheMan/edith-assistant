import { fileURLToPath } from "node:url";

// Every setting can be overridden with an environment variable of the same name
export const config = {
  // Where the app connects. 127.0.0.1 is enough for the Android emulator (it reaches the
  // PC as 10.0.2.2); use 0.0.0.0 to accept a real phone on the local network.
  HOST: process.env.HOST ?? "127.0.0.1",
  PORT: Number(process.env.PORT ?? 11435),
  OLLAMA_URL: process.env.OLLAMA_URL ?? "http://127.0.0.1:11434",
  orpheusModel: process.env.ORPHEUS_MODEL ?? "sematre/orpheus:ft-en-3b-q4_k_m",
  // Orpheus English voices: tara, leah, jess, leo, dan, mia, zac, zoe
  voice: process.env.VOICE ?? "tara",
  decoderPath: process.env.SNAC_DECODER ?? fileURLToPath(new URL("./models/snac_24khz_decoder.onnx", import.meta.url)),
};
