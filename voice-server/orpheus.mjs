// Text -> speech with Orpheus (run by Ollama) and the SNAC audio decoder (run here with ONNX Runtime).
// Orpheus doesn't output sound: it writes the speech as <custom_token_N> text, seven tokens per
// audio frame, which SNAC decodes into 24 kHz samples.
import ort from "onnxruntime-node";

export const SAMPLE_RATE = 24000;
const CODEBOOK_SIZE = 4096;
const TOKEN_OFFSET = 10;
const MAX_CHUNK_CHARS = 250;

export class OrpheusVoice {
  constructor({ ollamaUrl, model }) {
    this.ollamaUrl = ollamaUrl;
    this.model = model;
  }

  async load(decoderPath) {
    this.decoder = await ort.InferenceSession.create(decoderPath);
  }

  /** Speaks [text] in [voice]; returns 24 kHz mono float samples. Stops early if [signal] aborts. */
  async synthesize(text, voice, signal) {
    const pieces = [];
    for (const chunk of splitIntoChunks(text)) {
      signal?.throwIfAborted();
      const codes = await this.generateCodes(chunk, voice, signal);
      pieces.push(await this.decode(codes));
    }
    return concatWithPauses(pieces, Math.round(SAMPLE_RATE * 0.15));
  }

  async generateCodes(text, voice, signal) {
    // Aborting the fetch also makes Ollama stop generating
    const res = await fetch(`${this.ollamaUrl}/api/generate`, {
      method: "POST",
      signal,
      body: JSON.stringify({
        model: this.model,
        stream: false,
        raw: true,
        prompt: `<|audio|>${voice}: ${text}<|eot_id|>`,
        options: { temperature: 0.6, top_p: 0.9, repeat_penalty: 1.1, num_predict: 4096 },
      }),
    });
    const body = await res.json();
    if (!res.ok) throw new Error(`Ollama: ${body.error ?? `HTTP ${res.status}`}`);

    // Token N -> SNAC code: subtract the offset and the 4096-wide slot for its position in the
    // 7-code frame. Orpheus's start/end markers come out negative and are dropped.
    const codes = [];
    for (const match of body.response.matchAll(/<custom_token_(\d+)>/g)) {
      const code = Number(match[1]) - TOKEN_OFFSET - (codes.length % 7) * CODEBOOK_SIZE;
      if (code >= 0 && code < CODEBOOK_SIZE) codes.push(code);
    }
    if (codes.length < 7) throw new Error("Orpheus returned no audio codes");
    return codes.slice(0, codes.length - (codes.length % 7));
  }

  async decode(codes) {
    // Each 7-code frame is spread over SNAC's three levels: 1 coarse, 2 middle, 4 fine codes
    const levels = [[], [], []];
    for (let i = 0; i < codes.length; i += 7) {
      const c = codes.slice(i, i + 7);
      levels[0].push(c[0]);
      levels[1].push(c[1], c[4]);
      levels[2].push(c[2], c[3], c[5], c[6]);
    }
    const feeds = {};
    levels.forEach((level, i) => {
      feeds[`audio_codes.${i}`] = new ort.Tensor("int64", BigInt64Array.from(level, BigInt), [1, level.length]);
    });
    const { audio_values } = await this.decoder.run(feeds);
    return audio_values.data;
  }
}

/** Splits text at sentence ends into chunks Orpheus handles reliably. */
export function splitIntoChunks(text) {
  const sentences = text.replace(/\s+/g, " ").trim().match(/[^.!?]+[.!?]*["')\]]*\s*/g) ?? [];
  const chunks = [];
  let current = "";
  for (const sentence of sentences) {
    if (current && current.length + sentence.length > MAX_CHUNK_CHARS) {
      chunks.push(current.trim());
      current = "";
    }
    current += sentence;
  }
  if (current.trim()) chunks.push(current.trim());
  return chunks;
}

function concatWithPauses(pieces, pauseSamples) {
  const total = pieces.reduce((sum, p) => sum + p.length, 0) + pauseSamples * Math.max(0, pieces.length - 1);
  const out = new Float32Array(total);
  let offset = 0;
  pieces.forEach((piece, i) => {
    if (i > 0) offset += pauseSamples;
    out.set(piece, offset);
    offset += piece.length;
  });
  return out;
}

/** Encodes float samples as a 16-bit mono WAV file. */
export function toWav(samples) {
  const pcm = Buffer.alloc(samples.length * 2);
  for (let i = 0; i < samples.length; i++) {
    pcm.writeInt16LE(Math.round(Math.max(-1, Math.min(1, samples[i])) * 32767), i * 2);
  }
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);            // PCM format chunk size
  header.writeUInt16LE(1, 20);             // PCM
  header.writeUInt16LE(1, 22);             // mono
  header.writeUInt32LE(SAMPLE_RATE, 24);
  header.writeUInt32LE(SAMPLE_RATE * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);            // bits per sample
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}
