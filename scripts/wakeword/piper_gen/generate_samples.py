"""A Windows stand-in for piper-sample-generator's `generate_samples` (openWakeWord's train.py imports it by name).

piper-sample-generator needs piper-phonemize, which has no Windows build. This uses the `piper-tts` package (it
ships espeak-ng for Windows) with Piper's multi-speaker ONNX voices instead:
- en_US-libritts_r-medium: 904 speakers (the same LibriTTS-R data piper-sample-generator's model was trained on)
- en_GB-vctk-medium: 109 speakers, for some accent variety

What it can't copy: piper-sample-generator also blends speaker embeddings (slerp), which the ONNX voices don't
expose. Variety comes instead from the 1,013 speakers, the length/noise scales, a little jitter, and punctuation
that changes the prosody ("hey edith", "hey, edith!", …).

Clips are written as 16 kHz mono 16-bit WAV, what openWakeWord's augmentation and features expect.
"""
import os
import random
from math import gcd
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import scipy.io.wavfile
from scipy.signal import resample_poly

VOICES_DIR = Path(__file__).resolve().parent.parent / "voices"
VOICES = [("en_US-libritts_r-medium", 904, 0.88), ("en_GB-vctk-medium", 109, 0.12)]
OUT_SR = 16000
PUNCTUATION = [("", ""), ("", "."), ("", "!"), ("", "?"), (",", ""), (",", "!")]

_voices = {}


def _voice(name):
    if name not in _voices:
        from piper import PiperVoice
        _voices[name] = PiperVoice.load(VOICES_DIR / f"{name}.onnx")
    return _voices[name]


def _vary(text, rng):
    """'hey edith' → e.g. 'hey, edith!' (prosody changes with punctuation). Single words are left alone."""
    words = text.split()
    mid, end = rng.choice(PUNCTUATION)
    if len(words) > 1:
        words[0] = words[0] + mid
    return " ".join(words) + end


def _say(voice, text, cfg):
    chunks = list(voice.synthesize(text, cfg))
    if not chunks:
        return None, None
    return np.concatenate([c.audio_float_array for c in chunks]).astype(np.float32), chunks[0].sample_rate


def _trim(audio, level=0.02):
    loud = np.flatnonzero(np.abs(audio) > level * np.abs(audio).max())
    return audio[loud[0]:loud[-1] + 1] if len(loud) else audio


def _synth(job):
    text, path, seed, length_scales, noise_scales, noise_ws = job
    from piper import SynthesisConfig
    rng = random.Random(seed)
    names, counts, weights = zip(*VOICES)
    i = rng.choices(range(len(names)), weights=weights)[0]
    voice = _voice(names[i])
    cfg = SynthesisConfig(
        speaker_id=rng.randrange(counts[i]),
        length_scale=rng.choice(length_scales) * rng.uniform(0.92, 1.08),
        noise_scale=rng.choice(noise_scales) * rng.uniform(0.85, 1.0),
        noise_w_scale=rng.choice(noise_ws) * rng.uniform(0.85, 1.0),
    )
    if "|" in text:
        # "hey|edith": each part said separately by the same voice, joined by a 0.15-0.6 s pause ("Hey… Edith").
        # Windows' voices pause that long at "Hey, Edith!", and the first models missed exactly those.
        parts = [_say(voice, p, cfg) for p in text.split("|")]
        if any(a is None for a, _ in parts):
            return False
        sr = parts[0][1]
        gap = np.zeros(int(sr * rng.uniform(0.15, 0.6)), np.float32)
        pieces = []
        for k, (a, _) in enumerate(parts):
            pieces += ([gap] if k else []) + [_trim(a)]
        audio = np.concatenate([np.zeros(int(sr * 0.1), np.float32)] + pieces + [np.zeros(int(sr * 0.1), np.float32)])
    else:
        audio, sr = _say(voice, _vary(text, rng), cfg)
        if audio is None:
            return False
    g = gcd(sr, OUT_SR)
    audio = resample_poly(audio, OUT_SR // g, sr // g)
    # Piper normalizes to full scale; 0.9 leaves room for the resampler's overshoot instead of clipping it.
    scipy.io.wavfile.write(path, OUT_SR, (np.clip(audio * 0.9, -1, 1) * 32767).astype(np.int16))
    return True


def generate_samples(text, output_dir, max_samples, file_names=None, length_scales=(0.75, 1.0, 1.25),
                     noise_scales=(0.667,), noise_scale_ws=(0.8,), batch_size=None, **kwargs):
    texts = [text] if isinstance(text, str) else list(text)
    os.makedirs(output_dir, exist_ok=True)
    if file_names is None:
        start = len(os.listdir(output_dir))
        file_names = [f"{start + i}.wav" for i in range(max_samples)]
    base_seed = random.randrange(1 << 30)
    jobs = [
        (texts[i % len(texts)], os.path.join(output_dir, file_names[i]), base_seed + i,
         list(length_scales), list(noise_scales), list(noise_scale_ws))
        for i in range(max_samples)
    ]
    workers = max(1, min(16, (os.cpu_count() or 2) // 2))
    done = 0
    with Pool(workers) as pool:
        for ok in pool.imap_unordered(_synth, jobs, chunksize=8):
            done += ok
            if done % 500 == 0:
                print(f"  generated {done}/{max_samples}", flush=True)
    print(f"  generated {done}/{max_samples} into {output_dir}", flush=True)
