// Downloads the SNAC audio decoder and pulls the Orpheus model into Ollama.
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { config } from "./config.mjs";

const DECODER_URL = "https://huggingface.co/onnx-community/snac_24khz-ONNX/resolve/main/onnx/decoder_model.onnx";

if (existsSync(config.decoderPath)) {
  console.log(`SNAC decoder already present: ${config.decoderPath}`);
} else {
  console.log("Downloading SNAC decoder (52.6 MB)...");
  const res = await fetch(DECODER_URL);
  if (!res.ok) throw new Error(`Download failed: HTTP ${res.status}`);
  mkdirSync(new URL("./models/", import.meta.url), { recursive: true });
  writeFileSync(config.decoderPath, Buffer.from(await res.arrayBuffer()));
  console.log(`Saved ${config.decoderPath}`);
}

console.log(`Pulling ${config.orpheusModel} into Ollama (2.4 GB the first time)...`);
execFileSync("ollama", ["pull", config.orpheusModel], { stdio: "inherit" });
console.log("Setup complete. Start the server with: npm start");
