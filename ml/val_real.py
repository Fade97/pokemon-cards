# SPDX-License-Identifier: GPL-3.0-or-later
"""Score a checkpoint on the real validation crops of realset.py (cards never trained) against the full EN gallery,
exactly like the per-epoch check in train.py --real. Use it to put a baseline number next to the training log.

  uv run python val_real.py --run mnv3_256_real
"""
from __future__ import annotations

import argparse
import json

import numpy as np

import gpu_aug
import realset
from common import RESULTS_DIR, device
from pipeline import load_model
from train import real_val_eval


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run", required=True)
    args = ap.parse_args()
    dev = device()
    va = realset.real_samples(json.loads(realset.SAMPLES.read_text()), "val")
    x = gpu_aug.load_real_crops([realset.REAL_TRAIN / "crops" / s["file"] for s in va], [0] * len(va))
    r = real_val_eval(load_model(args.run, dev), dev, np.load(realset.GALLERY_NPY, mmap_mode="r"),
                      json.loads(realset.GALLERY_IDS.read_text()), x, [s["card_id"] for s in va])
    print(json.dumps(r))
    RESULTS_DIR.mkdir(exist_ok=True)
    (RESULTS_DIR / f"val_real_{args.run}.json").write_text(json.dumps(r, indent=1))


if __name__ == "__main__":
    main()
