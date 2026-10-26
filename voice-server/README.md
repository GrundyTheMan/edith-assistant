# Edith voice server

Gives Edith a voice on Windows, where Ollama's own speech model (phonon) can't run yet.

The app connects here (port 11435) instead of directly to Ollama:

- `POST /v1/audio/speech` is answered here: Orpheus (run by Ollama) writes the speech as audio
  codes, and the SNAC decoder turns them into a 24 kHz WAV.
- Every other request (chat, transcription) is passed through to Ollama unchanged.

## Setup (once)

Needs Node.js 20+ and Ollama running.

```
npm install
npm run setup
```

`setup` downloads the SNAC decoder (52.6 MB) into `models/` and pulls the Orpheus model into
Ollama (2.4 GB).

## Run

```
npm start
```

In the app, Settings → Server URL should be `http://10.0.2.2:11435` (the default) on the emulator.

## Settings

Set as environment variables before `npm start`:

| Variable | Default | |
|---|---|---|
| `VOICE` | `tara` | tara, leah, jess, leo, dan, mia, zac, zoe |
| `PORT` | `11435` | |
| `HOST` | `127.0.0.1` | Use `0.0.0.0` to accept a real phone on your network |
| `OLLAMA_URL` | `http://127.0.0.1:11434` | |
| `ORPHEUS_MODEL` | `sematre/orpheus:ft-en-3b-q4_k_m` | |
| `DECODER_THREADS` | `4` | CPU threads for the audio decoder |

If the server isn't running, chat stops working too (it goes through here). Switch the app's
Server URL to port 11434 to talk to Ollama directly; Edith then uses the phone's voice.
