"""Scores Windows' own voices (David, Zira; never seen in training) with each model, at the app's default 0.5."""
import glob
import sys

import numpy as np

from evaluate import read, scores

if __name__ == "__main__":
    from openwakeword.model import Model
    for path in sys.argv[1:]:
        m = Model(wakeword_models=[path], inference_framework="onnx")
        pos = sorted(f for f in glob.glob("sapi/*.wav") if "neg_" not in f)
        neg = sorted(glob.glob("sapi/neg_*.wav"))
        ps = np.array([scores(m, read(f)).max() for f in pos])
        ns = np.array([scores(m, read(f)).max() for f in neg])
        print(f"{path}: 'Hey Edith' woke it {int((ps >= 0.5).sum())}/{len(ps)} (min {ps.min():.2f}); "
              f"other phrases woke it {int((ns >= 0.5).sum())}/{len(ns)} (max {ns.max():.2f})")
        for f, s in zip(neg, ns):
            if s >= 0.5:
                print(f"   woke on {f} ({s:.2f})")
        for f, s in zip(pos, ps):
            if s < 0.5:
                print(f"   missed {f} ({s:.2f})")
