# SPDX-License-Identifier: GPL-3.0-or-later
"""Zero-shot baselines on the rectified synthetic test crops.

Pretrained backbones (no training) and a perceptual-hash baseline. Reference = the clean TCGdex image of each
card; query = the crop produced by detect.py. Writes ml/results/baselines.json.
"""
from __future__ import annotations

import argparse
import json
import time

import cv2
import imagehash
import numpy as np
import torch
from PIL import Image

from common import RESULTS_DIR, SYNTH_DIR, device, load_manifest
from metrics import evaluate, knn
from models import ZERO_SHOT, embed_arrays, load_backbone, preprocess
from synth import load_ref_rgba


def load_test(name="test"):
    cards = load_manifest()
    key2i = {c.key: i for i, c in enumerate(cards)}
    labels = json.loads((SYNTH_DIR / name / "labels.json").read_text())
    crops_dir = SYNTH_DIR / f"{name}-crops"
    crops = [cv2.imread(str(crops_dir / l["file"])) for l in labels]
    truth = np.array([key2i[l["key"]] for l in labels])
    return cards, crops, truth


def phash_baseline(cards, crops, truth):
    def h(bgr):
        rgb = cv2.cvtColor(cv2.resize(bgr, (256, 352)), cv2.COLOR_BGR2RGB)
        return imagehash.phash(Image.fromarray(rgb), hash_size=16).hash.flatten()

    refs = []
    for c in cards:
        r = load_ref_rgba(str(c.ref_path))
        a = r[..., 3:4].astype(np.float32) / 255
        refs.append(h((r[..., :3] * a + 255 * (1 - a)).astype(np.uint8)))
    R = np.stack(refs).astype(np.int8)
    Q = np.stack([h(c) for c in crops]).astype(np.int8)
    dist = (Q[:, None, :] != R[None, :, :]).sum(-1)
    topk = np.argsort(dist, axis=1)[:, :5]
    return evaluate(topk, truth, cards)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--models", nargs="*", default=list(ZERO_SHOT))
    args = ap.parse_args()
    dev = device()
    cards, crops, truth = load_test()
    ref_x = [preprocess(load_ref_rgba(str(c.ref_path))) for c in cards]
    q_x = [preprocess(c) for c in crops]
    results = {}
    t = time.time()
    results["phash16"] = phash_baseline(cards, crops, truth)
    print("phash16", results["phash16"]["top1"], results["phash16"]["top5"], f"{time.time() - t:.0f}s")
    for name in args.models:
        t = time.time()
        m = load_backbone(name).to(dev)
        R = embed_arrays(m, ref_x, dev)
        Q = embed_arrays(m, q_x, dev)
        topk, _ = knn(Q, R)
        r = evaluate(topk, truth, cards)
        r["dim"] = int(R.shape[1])
        r["params_m"] = round(sum(p.numel() for p in m.parameters()) / 1e6, 2)
        results[name] = r
        print(name, r["dim"], r["top1"], r["top5"], r["top1_errors"], f"{time.time() - t:.0f}s")
        del m
        torch.cuda.empty_cache()
    RESULTS_DIR.mkdir(exist_ok=True)
    (RESULTS_DIR / "baselines.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
