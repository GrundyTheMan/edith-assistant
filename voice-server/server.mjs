// Edith's voice server. The app talks to this instead of directly to Ollama:
//  - POST /v1/audio/speech is answered here with Orpheus + SNAC (a WAV file)
//  - every other request is passed through to Ollama unchanged (chat, transcription, ...)
import http from "node:http";
import { existsSync } from "node:fs";
import { config } from "./config.mjs";
import { OrpheusVoice, SAMPLE_RATE, toWav } from "./orpheus.mjs";

const VOICES = ["tara", "leah", "jess", "leo", "dan", "mia", "zac", "zoe"];
const ollama = new URL(config.OLLAMA_URL);

if (!existsSync(config.decoderPath)) {
  console.error(`SNAC decoder not found at ${config.decoderPath}. Run "npm run setup" first.`);
  process.exit(1);
}
const orpheus = new OrpheusVoice({ ollamaUrl: config.OLLAMA_URL, model: config.orpheusModel });
await orpheus.load(config.decoderPath);

const server = http.createServer(async (req, res) => {
  if (req.method === "POST" && new URL(req.url, "http://x").pathname === "/v1/audio/speech") {
    await handleSpeech(req, res);
  } else {
    proxyToOllama(req, res);
  }
});

// OpenAI-style speech request: { input, voice?, model? (ignored), response_format? (wav only) }
async function handleSpeech(req, res) {
  const started = Date.now();
  // The app drops a reply's audio request when a newer reply starts; stop work on it right away
  const abort = new AbortController();
  res.on("close", () => {
    if (!res.writableFinished) abort.abort();
  });
  try {
    const body = JSON.parse(await readBody(req) || "{}");
    const text = String(body.input ?? "").trim();
    if (!text) return sendError(res, 400, "input is required");
    const voice = VOICES.includes(body.voice) ? body.voice : config.voice;
    log(`speech  ${voice}: ${text.length} chars requested (client port ${req.socket.remotePort})`);

    const samples = await orpheus.synthesize(text, voice, abort.signal);
    const wav = toWav(samples);
    res.writeHead(200, { "Content-Type": "audio/wav", "Content-Length": wav.length });
    res.end(wav);
    const audioSecs = samples.length / SAMPLE_RATE;
    log(`speech  ${voice}: ${text.length} chars -> ${audioSecs.toFixed(1)}s audio in ${((Date.now() - started) / 1000).toFixed(1)}s`);
  } catch (e) {
    if (abort.signal.aborted) return log("speech  cancelled: the app hung up");
    log(`speech  failed: ${e.message}`);
    sendError(res, 500, e.message);
  }
}

function proxyToOllama(req, res) {
  const upstream = http.request(
    {
      hostname: ollama.hostname,
      port: ollama.port,
      method: req.method,
      path: req.url,
      headers: { ...req.headers, host: ollama.host },
    },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode, upstreamRes.headers);
      upstreamRes.pipe(res);
      log(`proxy   ${req.method} ${req.url} -> ${upstreamRes.statusCode}`);
    }
  );
  upstream.on("error", (e) => {
    log(`proxy   ${req.method} ${req.url} failed: ${e.message}`);
    if (!res.headersSent) sendError(res, 502, `Couldn't reach Ollama at ${config.OLLAMA_URL}: ${e.message}`);
    else res.destroy();
  });
  req.pipe(upstream);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const parts = [];
    req.on("data", (part) => parts.push(part));
    req.on("end", () => resolve(Buffer.concat(parts).toString("utf8")));
    req.on("error", reject);
  });
}

function sendError(res, status, message) {
  const body = JSON.stringify({ error: { message } });
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

function log(message) {
  console.log(`${new Date().toLocaleTimeString()}  ${message}`);
}

server.listen(config.PORT, config.HOST, () => {
  log(`Edith voice server on http://${config.HOST}:${config.PORT} (voice: ${config.voice})`);
  log(`Passing everything else through to Ollama at ${config.OLLAMA_URL}`);
});
