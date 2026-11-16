# SPDX-License-Identifier: GPL-3.0-or-later
"""Zero-shot baselines on the rectified synthetic test crops.

Pretrained backbones (no training) and a perceptual-hash baseline. Reference = the clean TCGdex image of each
card; query = the crop produced by detect.py. Three views (see metrics.full_report): the 870-card gallery of the
trained sets, the same queries against the full gallery incl. the unseen set, and the unseen set (de/30th) against
the full gallery. Writes ml/results/baselines.json.
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
from metrics import full_report
from models import ZERO_SHOT, embed_arrays, load_backbone, preprocess
from synth import load_ref_rgba


def load_crops(name: str, key2i: dict):
    labels = json.loads((SYNTH_DIR / name / "labels.json").read_text())
    crops = [cv2.imread(str(SYNTH_DIR / f"{name}-crops" / l["file"])) for l in labels]
    return crops, np.array([key2i[l["key"]] for l in labels])


def _ref_bgr(card):
    r = load_ref_rgba(str(card.ref_path))
    a = r[..., 3:4].astype(np.float32) / 255
    return (r[..., :3] * a + 255 * (1 - a)).astype(np.uint8)


def phash_scores(cards, query_sets):
    def h(bgr):
        rgb = cv2.cvtColor(cv2.resize(bgr, (256, 352)), cv2.COLOR_BGR2RGB)
        return imagehash.phash(Image.fromarray(rgb), hash_size=16).hash.flatten()

    R = np.stack([h(_ref_bgr(c)) for c in cards]).astype(np.int8)
    out = []
    for crops in query_sets:
        Q = np.stack([h(c) for c in crops]).astype(np.int8)
        out.append(-(Q[:, None, :] != R[None, :, :]).sum(-1).astype(np.float32))  # higher = better
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--models", nargs="*", default=list(ZERO_SHOT))
    args = ap.parse_args()
    dev = device()
    cards = load_manifest(include_holdout=True)
    n_trained = sum(not c.holdout for c in cards)
    key2i = {c.key: i for i, c in enumerate(cards)}
    test, t_test = load_crops("test", key2i)
    unseen, t_unseen = load_crops("test30th", key2i)
    results = {}
    t = time.time()
    s1, s2 = phash_scores(cards, [test, unseen])
    results["phash16"] = full_report(s1, t_test, s2, t_unseen, cards, n_trained)
    print("phash16", {k: round(v["top1"], 3) for k, v in results["phash16"].items() if "top1" in v},
          f"{time.time() - t:.0f}s", flush=True)
    ref_x = [preprocess(load_ref_rgba(str(c.ref_path))) for c in cards]
    q_test = [preprocess(c) for c in test]
    q_unseen = [preprocess(c) for c in unseen]
    for name in args.models:
        t = time.time()
        m = load_backbone(name).to(dev)
        R = embed_arrays(m, ref_x, dev)
        r = full_report(embed_arrays(m, q_test, dev) @ R.T, t_test, embed_arrays(m, q_unseen, dev) @ R.T, t_unseen,
                        cards, n_trained)
        r["dim"] = int(R.shape[1])
        r["params_m"] = round(sum(p.numel() for p in m.parameters()) / 1e6, 2)
        results[name] = r
        print(name, {k: (round(v["top1"], 3), round(v["top5"], 3)) for k, v in r.items() if isinstance(v, dict) and "top1" in v},
              f"{time.time() - t:.0f}s", flush=True)
        del m
        torch.cuda.empty_cache()
    RESULTS_DIR.mkdir(exist_ok=True)
    (RESULTS_DIR / "baselines.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
