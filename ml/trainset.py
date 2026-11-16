# SPDX-License-Identifier: GPL-3.0-or-later
"""Pre-render the training set: synthetic photo -> detect.py -> rectified crop.

Using the real detector for most crops makes training see the same crop errors as inference. When the detector
misses (IoU < 0.75) or for 30 % of samples, the true corners plus noise are used instead.
Seeds are disjoint from the test (20261116) and val sets. Output: <data>/train/<card idx>/<k>.jpg
"""
from __future__ import annotations

import argparse
import json
from multiprocessing import Pool

import cv2
import numpy as np

from common import DATA_DIR, load_manifest
from detect import detect, quad_iou, rectify
from synth import load_ref_rgba, render

OUT = DATA_DIR / "train"
SEED = 777


def _init():
    cv2.setNumThreads(1)


def _job(args):
    ci, k, path = args
    out = OUT / f"{ci:04d}" / f"{k:03d}.jpg"
    if out.exists():
        return None
    rng = np.random.default_rng((SEED, ci, k))
    img, gt = render(load_ref_rgba(path), rng)
    quad, _ = detect(img)
    iou = quad_iou(quad, gt) if quad is not None else 0.0
    if iou < 0.75 or rng.random() < 0.3:
        diag = np.linalg.norm(gt[2] - gt[0])
        quad = gt + rng.normal(0, 0.012 * diag, gt.shape).astype(np.float32)
        src = "gt"
    else:
        src = "det"
    out.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(out), rectify(img, quad), [cv2.IMWRITE_JPEG_QUALITY, 92])
    return src, iou


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--per-card", type=int, default=48)
    ap.add_argument("--workers", type=int, default=14)
    args = ap.parse_args()
    cards = load_manifest()
    jobs = [(ci, k, str(c.ref_path)) for ci, c in enumerate(cards) for k in range(args.per_card)]
    with Pool(args.workers, initializer=_init) as pool:
        res = [r for r in pool.imap_unordered(_job, jobs, chunksize=8) if r]
    srcs = [r[0] for r in res]
    stats = {"n": len(res), "det": srcs.count("det"), "gt": srcs.count("gt")}
    print(stats)
    (OUT / "stats.json").write_text(json.dumps(stats))


if __name__ == "__main__":
    main()
