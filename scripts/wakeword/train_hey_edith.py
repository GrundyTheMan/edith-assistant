"""Train openWakeWord's "Hey Edith" model on this Windows PC (the steps of openWakeWord's train.py / Colab notebook).

Usage:  .venv/Scripts/python.exe train_hey_edith.py [generate] [augment] [train]   (no args = all three)
        add "small" for a quick end-to-end dry run in ./small_run

Differences from openWakeWord's train.py, all for Windows:
- Voices come from piper_gen/generate_samples.py (piper-tts) instead of piper-sample-generator (Linux only).
- The training DataLoader runs in-process (num_workers=0): train.py hands a generator to worker processes, which
  Windows' spawn can't pickle.
- No .tflite conversion (Edith uses ONNX; the converter needs TensorFlow 2.8).
Otherwise it calls openWakeWord's own functions with openWakeWord's default settings (examples/custom_model.yml).
"""
import logging
import random
import os
import sys
import uuid
from pathlib import Path

import numpy as np
import scipy.io.wavfile
import torch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "piper_gen"))
DATA = HERE / "data"

CONFIG = {
    "model_name": "hey_edith",
    "target_phrase": ["hey edith"],
    # Sound-alikes it must not wake on (the docs' list plus more names and phrases near "hey edith").
    "custom_negative_phrases": [
        "edit", "hey edit", "hey eddie", "edith", "hey ed", "head with", "hey eden", "hey edward", "hey ethan",
        "hey judith", "meredith", "hey adam", "hey it is", "a death", "hey heather", "hey alexa", "hey jarvis",
        "okay edith", "hey, it's", "eight with", "they did", "hey deaf",
    ],
    "n_samples": 10000,
    "n_samples_val": 2000,
    "tts_batch_size": 50,
    "augmentation_batch_size": 16,
    "output_dir": str(HERE / "model_out"),
    "rir_paths": [str(DATA / "mit_rirs")],
    "background_paths": [str(DATA / "audioset_16k")],
    "false_positive_validation_data_path": str(DATA / "validation_set_features.npy"),
    "augmentation_rounds": 1,
    "feature_data_files": {"ACAV100M_sample": str(DATA / "openwakeword_features_ACAV100M_2000_hrs_16bit.npy")},
    # v2: more sound-alikes per batch (50 → 100), and the training background audio as negatives (see
    # background_features). v1 woke 8 times an hour on held-out audio, against 1.5 for openWakeWord's Hey Jarvis.
    "custom_negative_share": 0.4,
    # v3: v2 missed Windows' voices saying "Hey, Edith!" with a long pause after "Hey".
    "paused_phrase": "hey|edith",
    "n_paused": 3000,
    "background_negatives": True,
    "batch_n_per_class": {"ACAV100M_sample": 1024, "adversarial_negative": 100, "positive": 50, "background": 256},
    "model_type": "dnn",
    "layer_size": 32,
    "steps": 50000,
    "max_negative_weight": 1500,
    "target_false_positives_per_hour": 0.2,
}

SMALL = {"n_samples": 300, "n_samples_val": 100, "steps": 300, "output_dir": str(HERE / "small_run")}


def generate(config, dirs):
    from generate_samples import generate_samples
    from openwakeword.data import generate_adversarial_texts

    def fill(out, n, texts, noise):
        os.makedirs(out, exist_ok=True)
        have = len(os.listdir(out))
        if have > 0.95 * n:
            logging.info(f"{out}: {have} clips already, skipping")
            return
        generate_samples(text=texts, max_samples=n - have, noise_scales=[noise], noise_scale_ws=[noise],
                         length_scales=[0.75, 1.0, 1.25], output_dir=out,
                         file_names=[uuid.uuid4().hex + ".wav" for _ in range(n - have)])

    def adversarial(n):
        # train.py puts each custom phrase in once next to ~n generated ones, so with 10,000 clips each custom
        # phrase got ~1 clip in 450. v1 then woke on "hey eden" 85% of the time. Here the custom phrases are
        # [custom_negative_share] of the clips, interleaved with the generated ones.
        custom = list(config["custom_negative_phrases"])
        generated = []
        for phrase in config["target_phrase"]:
            generated += generate_adversarial_texts(input_text=phrase, N=n // len(config["target_phrase"]),
                                                    include_partial_phrase=1.0, include_input_words=0.2)
        n_custom = int(n * config["custom_negative_share"])
        texts = [custom[i % len(custom)] for i in range(n_custom)] + generated[:n - n_custom]
        random.Random(1).shuffle(texts)
        return texts

    fill(dirs["positive_train"], config["n_samples"], config["target_phrase"], 0.98)
    fill(dirs["positive_test"], config["n_samples_val"], config["target_phrase"], 1.0)
    # v3: "Hey… Edith" with a pause, as extra positives (see piper_gen's "hey|edith").
    for kind, n in [("positive_train", config["n_paused"]), ("positive_test", config["n_paused"] // 5)]:
        have = len(list(Path(dirs[kind]).glob("paused_*.wav")))
        if have < 0.95 * n:
            generate_samples(text=config["paused_phrase"], max_samples=n - have, noise_scales=[0.98],
                             noise_scale_ws=[0.98], length_scales=[0.75, 1.0, 1.25], output_dir=dirs[kind],
                             file_names=[f"paused_{uuid.uuid4().hex}.wav" for _ in range(n - have)])
    fill(dirs["negative_train"], config["n_samples"], adversarial(config["n_samples"]), 0.98)
    fill(dirs["negative_test"], config["n_samples_val"], adversarial(config["n_samples_val"]), 1.0)


def clip_length(dirs):
    """train.py's rule: the median positive clip + 750 ms, at least 2 s, snapped to 2 s when close."""
    clips = list(Path(dirs["positive_test"]).glob("*.wav"))
    rng = np.random.default_rng(0)
    lengths = [len(scipy.io.wavfile.read(clips[i])[1]) for i in rng.integers(0, len(clips), 50)]
    total = int(round(np.median(lengths) / 1000) * 1000) + 12000
    if total < 32000 or abs(total - 32000) <= 4000:
        total = 32000
    return total


def _load_wav(path, *args, **kwargs):
    """torchaudio.load without torchcodec (torchaudio 2.9+ needs it): every clip here is a plain WAV."""
    import soundfile as sf
    data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    return torch.from_numpy(data.T.copy()), sr


def _info_wav(path, *args, **kwargs):
    """torchaudio.info, removed in torchaudio 2.9: what openWakeWord and torch-audiomentations read from it."""
    import types
    import soundfile as sf
    i = sf.info(str(path))
    return types.SimpleNamespace(num_frames=i.frames, sample_rate=i.samplerate, num_channels=i.channels)


def _trim_after_close(path):
    """openWakeWord's trim_mmap, run once the writer has let go of the file: on Windows a memory-mapped file can't
    be deleted while it's open, and compute_features_from_generator calls trim_mmap with it still open."""
    import gc
    from numpy.lib.format import open_memmap
    gc.collect()
    src = np.load(path, mmap_mode="r")
    n = src.shape[0]
    while n > 0 and not np.any(src[n - 1]):
        n -= 1
    tmp = path[:-4] + ".trimmed.npy"
    dst = open_memmap(tmp, mode="w+", dtype=np.float32, shape=(n,) + src.shape[1:])
    for i in range(0, n, 1024):
        dst[i:min(i + 1024, n)] = src[i:min(i + 1024, n)]
    dst.flush()
    del src, dst
    gc.collect()
    os.replace(tmp, path)
    logging.info(f"{path}: {n} rows")


def augment(config, dirs, total_length):
    import openwakeword.data
    import torchaudio
    torchaudio.load = _load_wav
    torchaudio.info = _info_wav
    openwakeword.data.trim_mmap = lambda path: None   # done by _trim_after_close instead
    from openwakeword.data import augment_clips
    from openwakeword.utils import compute_features_from_generator

    rir = [str(p) for d in config["rir_paths"] for p in Path(d).glob("*.wav")]
    background = [str(p) for d in config["background_paths"] for p in Path(d).glob("*.wav")]
    for kind in ["positive_train", "negative_train", "positive_test", "negative_test"]:
        out = os.path.join(dirs["features"], f"{kind.split('_')[0]}_features_{kind.split('_')[1]}.npy")
        if os.path.exists(out):
            logging.info(f"{out} exists, skipping")
            continue
        clips = [str(p) for p in Path(dirs[kind]).glob("*.wav")] * config["augmentation_rounds"]
        gen = augment_clips(clips, total_length=total_length, batch_size=config["augmentation_batch_size"],
                            background_clip_paths=background, RIR_paths=rir)
        logging.info(f"Features for {kind} ({len(clips)} clips)")
        compute_features_from_generator(gen, n_total=len(clips), clip_duration=total_length, output_file=out,
                                        device="cpu", ncpu=1)
        _trim_after_close(out)


def background_features(config, dirs, total_length):
    """Extra negatives: the training background audio (AudioSet 00-03) by itself, cut into clip-length windows.
    The positives are mixed with this audio, so the model should learn the background alone isn't the wake word.
    (AudioSet 04 stays held out for evaluate.py.)"""
    import openwakeword
    out = os.path.join(dirs["features"], "background_features.npy")
    if os.path.exists(out):
        logging.info(f"{out} exists, skipping")
        return out
    F = openwakeword.utils.AudioFeatures(device="cpu")
    windows = []
    for d in config["background_paths"]:
        for p in sorted(Path(d).glob("*.wav")):
            a = scipy.io.wavfile.read(p)[1]
            for i in range(0, len(a) - total_length + 1, total_length):
                windows.append(a[i:i + total_length])
    logging.info(f"Background features from {len(windows)} windows")
    feats = [F.embed_clips(np.array(windows[i:i + 256]), batch_size=256) for i in range(0, len(windows), 256)]
    np.save(out, np.vstack(feats).astype(np.float32))
    return out


def train(config, dirs, total_length):
    import openwakeword
    from openwakeword.data import mmap_batch_generator
    from openwakeword.train import Model

    F = openwakeword.utils.AudioFeatures(device="cpu")
    input_shape = F.get_embedding_shape(total_length // 16000)
    oww = Model(n_classes=1, input_shape=input_shape, model_type=config["model_type"],
                layer_dim=config["layer_size"], seconds_per_example=1280 * input_shape[0] / 16000)

    def f(x, n=16):
        if n > x.shape[1] or n < x.shape[1]:
            x = np.vstack(x)
            return np.array([x[i:i + n, :] for i in range(0, x.shape[0] - n, n)])
        return x

    files = dict(config["feature_data_files"])
    if config.get("background_negatives"):
        files["background"] = background_features(config, dirs, total_length)
    data_transforms = {key: f for key in files}
    label_transforms = {key: (lambda x: [1 for _ in x]) if key == "positive" else (lambda x: [0 for _ in x])
                        for key in ["positive"] + list(files) + ["adversarial_negative"]}
    files["positive"] = os.path.join(dirs["features"], "positive_features_train.npy")
    files["adversarial_negative"] = os.path.join(dirs["features"], "negative_features_train.npy")
    batches = mmap_batch_generator(files, n_per_class=config["batch_n_per_class"],
                                   data_transform_funcs=data_transforms, label_transform_funcs=label_transforms)

    class IterDataset(torch.utils.data.IterableDataset):
        def __iter__(self):
            return batches

    X_train = torch.utils.data.DataLoader(IterDataset(), batch_size=None, num_workers=0)

    fp = np.load(config["false_positive_validation_data_path"])
    fp = np.array([fp[i:i + input_shape[0]] for i in range(0, fp.shape[0] - input_shape[0], 1)])
    X_val_fp = torch.utils.data.DataLoader(
        torch.utils.data.TensorDataset(torch.from_numpy(fp), torch.from_numpy(np.zeros(fp.shape[0], np.float32))),
        batch_size=len(fp))
    pos = np.load(os.path.join(dirs["features"], "positive_features_test.npy"))
    neg = np.load(os.path.join(dirs["features"], "negative_features_test.npy"))
    labels = np.hstack((np.ones(pos.shape[0]), np.zeros(neg.shape[0]))).astype(np.float32)
    X_val = torch.utils.data.DataLoader(
        torch.utils.data.TensorDataset(torch.from_numpy(np.vstack((pos, neg))), torch.from_numpy(labels)),
        batch_size=len(labels))

    best = oww.auto_train(X_train=X_train, X_val=X_val, false_positive_val_data=X_val_fp, steps=config["steps"],
                          max_negative_weight=config["max_negative_weight"],
                          target_fp_per_hour=config["target_false_positives_per_hour"])
    # openWakeWord's export_model, with the classic exporter (torch 2.9+ defaults to one that needs onnxscript).
    # Opset 13 and input [1, 16, 96], like its published models and what Edith's WakeWord.kt feeds.
    import copy
    out = os.path.join(config["output_dir"], config["model_name"] + ".onnx")
    torch.save(best.state_dict(), out[:-5] + ".pt")
    torch.onnx.export(copy.deepcopy(best).to("cpu").eval(), torch.rand(input_shape)[None, ], out,
                      opset_version=13, dynamo=False)
    logging.info(f"Saved {out}")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    args = sys.argv[1:]
    config = dict(CONFIG)
    if "small" in args:
        config.update(SMALL)
        args.remove("small")
    steps = args or ["generate", "augment", "train"]

    base = os.path.join(config["output_dir"], config["model_name"])
    os.makedirs(base, exist_ok=True)
    dirs = {k: os.path.join(base, k) for k in ["positive_train", "positive_test", "negative_train", "negative_test"]}
    dirs["features"] = base

    if "generate" in steps:
        generate(config, dirs)
    # Fixed at 2 s: v1 and v2 measured 32000, the negative features were computed at that length, and it gives
    # the [1, 16, 96] input Edith's WakeWord.kt feeds. The paused clips mustn't move it.
    total_length = 32000
    logging.info(f"Clip length: {total_length} samples (measured: {clip_length(dirs)})")
    if "augment" in steps:
        augment(config, dirs, total_length)
    if "train" in steps:
        train(config, dirs, total_length)
