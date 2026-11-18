# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the recognition assets for the Android test app (recognition lab).

Writes to android/app/src/main/assets/recognition/ (git-ignored, never committed):

  embedder.tflite   the exported LiteRT embedding model (int8 weights, see REPORT.md)
  corners.tflite    the learned card-corner detector, if given (README, "Learned corner detector")
  index.bin         float32 little-endian, N x D, L2-normalized reference embeddings (manifest order)
  cards.json        card metadata in the same order as index.bin
  meta.json         model name, dimension, card count, checksums

The index is computed with the same .tflite model the app runs, so app and index always match. It needs only
numpy, opencv and ai-edge-litert (no torch), so it also runs outside the training machine:

  CARDINDEX_DATA=~/data/pokemon-cards-data python ml/app_assets.py
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

import cv2
import numpy as np

import packs
from common import DATA_DIR, ML_DIR, Card, load_manifest
from synth import load_ref_rgba

INPUT = 224
DEFAULT_OUT = ML_DIR.parent / "android" / "app" / "src" / "main" / "assets" / "recognition"


def to_input(bgr: np.ndarray) -> np.ndarray:
    """Card crop (BGR or BGRA) -> 1 x 224 x 224 x 3 float32 RGB in [0, 1]; same as export_litert.to_input."""
    if bgr.shape[2] == 4:  # BGRA reference: composite on white
        a = bgr[..., 3:4].astype(np.float32) / 255
        bgr = (bgr[..., :3] * a + 255 * (1 - a)).astype(np.uint8)
    rgb = cv2.cvtColor(cv2.resize(bgr, (INPUT, INPUT), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
    return (rgb.astype(np.float32) / 255)[None]


class Embedder:
    def __init__(self, model_path: Path, threads: int = 8):
        from ai_edge_litert.interpreter import Interpreter
        self.it = Interpreter(model_path=str(model_path), num_threads=threads)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]["index"]
        self.out = self.it.get_output_details()[0]["index"]

    def __call__(self, x: np.ndarray) -> np.ndarray:
        self.it.set_tensor(self.inp, x)
        self.it.invoke()
        e = self.it.get_tensor(self.out)[0].astype(np.float32)
        return e / np.linalg.norm(e)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def card_entry(c: Card) -> dict:
    """One entry of cards.json. `imageUrl` is TCGdex's base URL, or "" when the reference image came from another
    source (set mee, packs.ref_fallback_url): the app only loads TCGdex images (ADR 0009)."""
    return {"key": c.key, "cardId": c.card_id, "lang": c.lang, "setId": c.set_id, "setName": c.set_name,
            "localId": c.local_id, "official": c.official, "name": c.name,
            "imageUrl": packs.app_image_url(c.image_url) or "", "unseen": c.holdout}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", default="export/mnv3_256_real_w8.tflite", help="path relative to the data dir")
    ap.add_argument("--check-index", default="",
                    help="index built on the training machine with the same model, compared row by row "
                         "(e.g. index/mnv3_256_w8_tflite.npy); '' = skip")
    ap.add_argument("--corners", default="export/corners_mnv3_w8.tflite",
                    help="learned corner detector (relative to the data dir), copied as corners.tflite; '' = none")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    model = DATA_DIR / args.model
    cards = [c for c in load_manifest(include_holdout=True) if c.ref_path.exists()]
    emb = Embedder(model)
    index = np.stack([emb(to_input(load_ref_rgba(str(c.ref_path)))) for c in cards]).astype("<f4")
    print(f"embedded {len(cards)} reference images, dim {index.shape[1]}")

    report = {}
    if args.check_index and (DATA_DIR / args.check_index).exists():
        ref = np.load(DATA_DIR / args.check_index)
        n = min(len(ref), len(index))
        cos = (ref[:n] * index[:n]).sum(1)
        report = {"rows_compared": n, "cosine_min": float(cos.min()), "cosine_mean": float(cos.mean())}
        print(f"vs. training-machine index: {report}")

    args.out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(model, args.out / "embedder.tflite")
    corners = DATA_DIR / args.corners if args.corners else None
    if corners and corners.exists():
        shutil.copyfile(corners, args.out / "corners.tflite")
    elif (args.out / "corners.tflite").exists():
        (args.out / "corners.tflite").unlink()
    (args.out / "index.bin").write_bytes(index.tobytes())
    meta_cards = [card_entry(c) for c in cards]
    (args.out / "cards.json").write_text(json.dumps(meta_cards, ensure_ascii=False), encoding="utf-8")
    meta = {"model": model.name, "dim": int(index.shape[1]), "cards": len(cards),
            "modelSha256": sha256(args.out / "embedder.tflite"), "indexSha256": sha256(args.out / "index.bin"),
            "corners": corners.name if corners and corners.exists() else "",
            "checkAgainstTrainingIndex": report}
    (args.out / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
