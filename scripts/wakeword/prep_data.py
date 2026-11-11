"""Background audio and room echoes for training "Hey Edith" (the Windows version of the notebook's download cells).

- mit_rirs/:    MIT environmental impulse responses (davidscripka/MIT_environmental_impulse_responses)
- audioset_16k/: four shards of AudioSet's balanced train set (agkphysics/AudioSet, parquet)
  (FMA, used by the notebook, is now a loader script for a 7 GB zip; AudioSet already has plenty of music.)

Everything is written as 16 kHz mono 16-bit WAV. Audio is decoded with soundfile, not `datasets`' decoder (which
needs ffmpeg/torchcodec on Windows).
"""
import io
import sys
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import scipy.io.wavfile
import soundfile as sf
from scipy.signal import resample_poly
from math import gcd
from tqdm import tqdm

ROOT = Path(__file__).parent / "data"
SR = 16000


def to_16k_int16(audio: np.ndarray, sr: int) -> np.ndarray:
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    if sr != SR:
        g = gcd(sr, SR)
        audio = resample_poly(audio, SR // g, sr // g)
    return (np.clip(audio, -1, 1) * 32767).astype(np.int16)


def decode(audio_bytes: bytes):
    data, sr = sf.read(io.BytesIO(audio_bytes), dtype="float32", always_2d=False)
    return to_16k_int16(data, sr)


def audio_column(row):
    a = row["audio"]
    return a["bytes"], (a.get("path") or "")


def rirs():
    from huggingface_hub import snapshot_download
    out = ROOT / "mit_rirs"
    out.mkdir(parents=True, exist_ok=True)
    src = Path(snapshot_download("davidscripka/MIT_environmental_impulse_responses", repo_type="dataset",
                                 allow_patterns=["16khz/*.wav"], local_dir=ROOT / "mit_rirs_raw"))
    n = 0
    for f in tqdm(sorted((src / "16khz").glob("*.wav")), desc="MIT RIRs"):
        scipy.io.wavfile.write(out / f.name, SR, decode(f.read_bytes()))
        n += 1
    print(f"RIRs: {n}")


def audioset(shards=("00", "01", "02", "03"), out_name="audioset_16k"):
    import urllib.request
    out = ROOT / out_name
    out.mkdir(parents=True, exist_ok=True)
    raw = ROOT / "audioset_raw"
    raw.mkdir(parents=True, exist_ok=True)
    for s in shards:
        f = raw / f"{s}.parquet"
        if not f.exists():
            url = f"https://huggingface.co/datasets/agkphysics/AudioSet/resolve/main/data/bal_train/{s}.parquet"
            print(f"Downloading {url}")
            urllib.request.urlretrieve(url, f.with_suffix(".part"))
            f.with_suffix(".part").rename(f)
        table = pq.ParquetFile(f)
        names = table.schema_arrow.names
        n = 0
        for batch in tqdm(table.iter_batches(batch_size=64, columns=["audio"] + (["video_id"] if "video_id" in names else [])), desc=f"AudioSet {s}"):
            for row in batch.to_pylist():
                b, path = audio_column(row)
                name = row.get("video_id") or Path(path).stem or f"{s}_{n}"
                try:
                    scipy.io.wavfile.write(out / f"{name}.wav", SR, decode(b))
                    n += 1
                except Exception as e:  # a few clips are empty or unreadable; skip them
                    print(f"skip {name}: {e}", file=sys.stderr)
        print(f"AudioSet {s}: {n} clips")


def heldout():
    """AudioSet shard 04, never used in training: evaluate.py's false-wake test."""
    audioset(shards=("04",), out_name="heldout_audioset_16k")


if __name__ == "__main__":
    steps = sys.argv[1:] or ["rirs", "audioset", "heldout"]
    for s in steps:
        globals()[s]()
