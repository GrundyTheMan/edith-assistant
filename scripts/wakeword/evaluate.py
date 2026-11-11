"""Held-out evaluation of hey_edith.onnx, scored the way Edith's app does it (80 ms chunks; a wake is a score at or
above the threshold, then the detector resets).

- Recall: 400 new synthetic "hey edith" clips (new seeds), clean and with room echo + real background noise at 5-15 dB.
- Sound-alikes: 400 new synthetic clips of the negative phrases.
- False wakes per hour: AudioSet shard 04 (data/heldout_audioset_16k), real audio never used in training.
- Reference: openWakeWord's published "Hey Jarvis" on the same kind of synthetic clips.

Usage: .venv/Scripts/python.exe evaluate.py [path/to/model.onnx]
"""
import glob
import random
import shutil
import sys

import numpy as np
import scipy.io.wavfile as w
from scipy.signal import fftconvolve

sys.path.insert(0, "piper_gen")
from generate_samples import generate_samples
from train_hey_edith import CONFIG

THRESHOLDS = [0.3, 0.5, 0.7]   # the app's sensitivity 0.75 / 0.5 (default) / 0.25
PAD = np.zeros(16000, np.int16)


def read(path):
    return w.read(path)[1].astype(np.float32) / 32768


def scores(model, audio):
    """Score per 80 ms chunk, 1 s of silence before and after (the detector needs ~1.3 s of audio to warm up)."""
    a = np.concatenate([PAD, (np.clip(audio, -1, 1) * 32767).astype(np.int16), PAD])
    model.reset()
    name = list(model.models)[0]
    return np.array([model.predict(a[i:i + 1280])[name] for i in range(0, len(a) - 1279, 1280)])


def noisy(clip, rirs, backgrounds, rng):
    rir = read(rng.choice(rirs))
    wet = fftconvolve(clip, rir)[:len(clip) + 8000]
    wet /= max(1e-6, np.abs(wet).max()) / max(1e-6, np.abs(clip).max())
    bg = read(rng.choice(backgrounds))
    if len(bg) < len(wet):
        bg = np.tile(bg, len(wet) // len(bg) + 1)
    start = rng.randrange(len(bg) - len(wet) + 1)
    bg = bg[start:start + len(wet)]
    snr = rng.uniform(5, 15)
    p_sig, p_bg = np.mean(wet ** 2) + 1e-9, np.mean(bg ** 2) + 1e-9
    return wet + bg * np.sqrt(p_sig / (p_bg * 10 ** (snr / 10)))


def wakes(s, threshold):
    """Wakes in a score stream, counting like the app: fire, then ignore until the detector refills (~1.3 s)."""
    n, i = 0, 0
    while i < len(s):
        if s[i] >= threshold:
            n += 1
            i += 16
        else:
            i += 1
    return n


if __name__ == "__main__":
    model_path = sys.argv[1] if len(sys.argv) > 1 else "model_out/hey_edith.onnx"
    for d in ["eval_pos", "eval_neg", "eval_jarvis"]:
        shutil.rmtree(d, ignore_errors=True)
    generate_samples("hey edith", "eval_pos", 400)
    generate_samples(CONFIG["custom_negative_phrases"], "eval_neg", 400)
    generate_samples("hey jarvis", "eval_jarvis", 400)

    from openwakeword.model import Model
    edith = Model(wakeword_models=[model_path], inference_framework="onnx")
    jarvis = Model(wakeword_models=["hey_jarvis"], inference_framework="onnx")

    rng = random.Random(7)
    rirs = glob.glob("data/mit_rirs/*.wav")
    backgrounds = sorted(glob.glob("data/heldout_audioset_16k/*.wav"))

    def peak_scores(model, folder, with_noise):
        out = []
        for f in sorted(glob.glob(folder + "/*.wav")):
            clip = read(f)
            out.append(scores(model, noisy(clip, rirs, backgrounds, rng) if with_noise else clip).max())
        return np.array(out)

    results = {
        "hey edith, clean": peak_scores(edith, "eval_pos", False),
        "hey edith, echo + noise": peak_scores(edith, "eval_pos", True),
        "sound-alikes, clean": peak_scores(edith, "eval_neg", False),
        "sound-alikes, echo + noise": peak_scores(edith, "eval_neg", True),
        "(reference) Hey Jarvis model on hey jarvis, echo + noise": peak_scores(jarvis, "eval_jarvis", True),
    }
    print(f"\nModel: {model_path}")
    print("Share of clips that woke it, by threshold " + " / ".join(map(str, THRESHOLDS)))
    for name, s in results.items():
        print(f"  {name:58s} " + " / ".join(f"{(s >= t).mean():5.0%}" for t in THRESHOLDS))

    # Which sound-alikes wake it (clean, default threshold): phrase → share. Same seed order as generation isn't
    # kept, so read the phrase back from a fresh synthesis of each phrase instead.
    print("Sound-alikes that wake it at 0.5 (20 clean clips each):")
    for phrase in CONFIG["custom_negative_phrases"]:
        d = "eval_phrase"
        shutil.rmtree(d, ignore_errors=True)
        generate_samples(phrase, d, 20)
        share = (peak_scores(edith, d, False) >= 0.5).mean()
        if share > 0:
            print(f"  {phrase!r}: {share:.0%}")

    for label, model in [("Hey Edith", edith), ("(reference) Hey Jarvis", jarvis)]:
        total_s, counts = 0.0, {t: 0 for t in THRESHOLDS}
        for f in backgrounds:
            audio = read(f)
            total_s += len(audio) / 16000
            s = scores(model, audio)
            for t in THRESHOLDS:
                counts[t] += wakes(s, t)
        hours = total_s / 3600
        print(f"{label}: false wakes on {hours:.2f} h of held-out real audio (AudioSet 04): " +
              " / ".join(f"{counts[t]} ({counts[t] / hours:.1f}/h)" for t in THRESHOLDS))
