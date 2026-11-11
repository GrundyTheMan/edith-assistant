"""Sanity check for the synthetic voices: openWakeWord's published "Hey Jarvis" model should fire on our synthetic
"hey jarvis" clips and not on "hey edith" clips. If it does, the clips look like real speech to openWakeWord."""
import glob
import shutil
import sys

import numpy as np
import scipy.io.wavfile as w

sys.path.insert(0, "piper_gen")
from generate_samples import generate_samples


def best_score(model, path):
    sr, a = w.read(path)
    a = np.concatenate([np.zeros(16000, np.int16), a, np.zeros(16000, np.int16)])
    model.reset()
    s = 0.0
    for i in range(0, len(a) - 1280, 1280):
        s = max(s, float(list(model.predict(a[i:i + 1280]).values())[0]))
    return s


if __name__ == "__main__":
    for d in ["scratch_j", "scratch_e"]:
        shutil.rmtree(d, ignore_errors=True)
    generate_samples("hey jarvis", "scratch_j", 100)
    generate_samples("hey edith", "scratch_e", 100)
    from openwakeword.model import Model
    m = Model(wakeword_models=["hey_jarvis"], inference_framework="onnx")
    for name in ["scratch_j", "scratch_e"]:
        s = np.array([best_score(m, f) for f in glob.glob(name + "/*.wav")])
        print(f"{name}: mean {s.mean():.3f}, fired at 0.5: {(s >= 0.5).mean():.0%}")
