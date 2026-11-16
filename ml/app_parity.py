# SPDX-License-Identifier: GPL-3.0-or-later
"""Reference results for the Android parity test: the Python pipeline on a folder of synthetic photos.

Takes every n-th image of a synthetic set (synth.py), re-encodes it as PNG (so the app and Python decode identical
pixels), and runs detect -> rectify -> embed (the app's .tflite) -> kNN against the app's index.bin, with the
180° check of pipeline.py. Writes <out>/expected.json; push <out> to the device and run RecognitionParityTest.

  CARDINDEX_DATA=~/data/pokemon-cards-data python ml/app_parity.py --name parity --every 5
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from app_assets import DEFAULT_OUT, Embedder, to_input
from common import DATA_DIR, SYNTH_DIR
from detect import center_crop_fallback, detect, rectify


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--name", default="parity", help="synthetic set under <data>/synth/")
    ap.add_argument("--every", type=int, default=5)
    ap.add_argument("--assets", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--out", type=Path, default=DATA_DIR / "app-parity")
    args = ap.parse_args()

    cards = json.loads((args.assets / "cards.json").read_text(encoding="utf-8"))
    keys = [c["key"] for c in cards]
    index = np.fromfile(args.assets / "index.bin", dtype="<f4").reshape(len(cards), -1)
    emb = Embedder(args.assets / "embedder.tflite")
    labels = json.loads((SYNTH_DIR / args.name / "labels.json").read_text(encoding="utf-8"))[:: args.every]
    args.out.mkdir(parents=True, exist_ok=True)
    rows = []
    for lab in labels:
        img = cv2.imread(str(SYNTH_DIR / args.name / lab["file"]))
        png = Path(lab["file"]).with_suffix(".png").name
        cv2.imwrite(str(args.out / png), img)
        quad, score = detect(img)
        found = quad is not None
        if not found:
            quad = center_crop_fallback(img)
        crop = rectify(img, quad)
        sims = np.stack([index @ emb(to_input(crop)), index @ emb(to_input(cv2.rotate(crop, cv2.ROTATE_180)))], 1)
        flip = int(sims[:, 1].max() > sims[:, 0].max())
        s = sims[:, flip]
        order = np.argsort(-s)[:5]
        rows.append({"file": png, "truth": lab["key"], "truthQuad": lab["quad"], "found": found,
                     "quad": np.asarray(quad, float).round(2).tolist(), "score": score, "flip": flip,
                     "top5": [keys[i] for i in order], "sims": [round(float(s[i]), 5) for i in order]})
    (args.out / "expected.json").write_text(json.dumps(rows), encoding="utf-8")
    top1 = np.mean([r["top5"][0] == r["truth"] for r in rows])
    print(f"wrote {len(rows)} images to {args.out}; python top-1 {top1:.3f}")


if __name__ == "__main__":
    main()
