# SPDX-License-Identifier: GPL-3.0-or-later
"""Calibrate the live-scan "is there a card?" gate for the learned corner detector (issue #55).

The corner model always returns 4 points plus a mean peak (`score`). The app draws the quad and identifies the
card only when the gate passes. This script measures, for frames with and without a card, the score and the
quad's shape as `detect.quad_shape` computes it (the app's Quad.looksLikeCard checks the same):

  convex     the 4 points form a convex quad in TL, TR, BR, BL order (no twisted or folded quad)
  aspect     (mean width / mean height) / (63/88): 1.0 for a card seen straight on
  fill       quad area / search-area area (the guide plus margin, what the model sees)
  angle      largest deviation of an inner angle from 90 degrees

Result (2026-11-18, corners_mnv3_w8): fill does not separate (the phone photos were not framed, fill p5 0.13);
score >= 0.10 plus convex, aspect within 1.5x and angle <= 35 degrees ("chosen" below) passes 96.6 % of TCG-AR
cards (the rejected 3 % have a median corner error of 18 % and none is recognized), 93.7 % of the phone photos
(the rejected ones are two merged binder cards and twisted quads), 4.3 % of the synthetic empty backgrounds
(6.0 % with the score alone) and 31 % of the TCG-AR windows without a card, which mostly show card-sized
rectangles (boosters, a phone, play-mat boxes); the similarity minimum has to reject those.

Sources:
  tcgar      positives: every annotated TCG-AR card in its guide crop (as eval_tcgar.py), with the corner error
  phone      positives: the real phone photos in <data>/real-photos (app resize -> guide area)
  tcgar_bg   negatives: guide-sized windows of the TCG-AR play mats in which no card lies even half inside
  synth_bg   negatives: synth.py backgrounds (wood, cloth, skin, paper, distractor shapes), no card

  CARDINDEX_DATA=~/data/pokemon-cards-data ~/data/pokemon-cards-data/.venv-lite/bin/python calib_corner_gate.py
Writes results/corner_gate_calibration.json (summary) and <data>/corner-gate-rows.json (per frame).
"""
from __future__ import annotations

import argparse
import json

import cv2
import numpy as np

from common import DATA_DIR, REAL_DIR, RESULTS_DIR
from detect import LearnedDetector, app_resize, guide_area, quad_shape
from eval_tcgar import annotations, corner_error, guide_crop, upright
from synth import background


def row(det, crop, source, **extra):
    q, score = det(crop)
    h, w = crop.shape[:2]
    shape = quad_shape(q)
    return {"source": source, "score": score, "convex": shape["convex"], "aspect": shape["aspect"],
            "fill": shape["area"] / (w * h), "angle": shape["angle"], **extra}


def tcgar_rows(det):
    out, cache = [], {}
    for img_path, quad, _ in annotations():
        if img_path not in cache:
            cache.clear()
            cache[img_path] = cv2.imread(str(img_path))
        img, quad = upright(cache[img_path], quad)
        l, t, r, b = guide_crop(img, quad)
        q, _ = det(img[t:b, l:r])
        out.append(row(det, img[t:b, l:r], "tcgar", image=img_path.stem,
                       err=corner_error(q + np.float32([l, t]), quad)))
    return out


def tcgar_bg_rows(det, rng, per_image=12):
    """Windows the size of a typical guide crop where every annotated card lies less than half inside."""
    by_img = {}
    for img_path, quad, _ in annotations():
        by_img.setdefault(img_path, []).append(quad)
    out = []
    for img_path, quads in by_img.items():
        img = cv2.imread(str(img_path))
        H, W = img.shape[:2]
        card_h = float(np.median([np.sort(np.linalg.norm(q - np.roll(q, 1, 0), axis=1))[2:].mean() for q in quads]))
        gh, gw = int(card_h / 0.78 * 1.14), int(card_h / 0.78 * 63 / 88 * 1.14)
        n, tries = 0, 0
        while n < per_image and tries < 400:
            tries += 1
            x, y = int(rng.integers(0, W - gw)), int(rng.integers(0, H - gh))
            win = np.float32([[x, y], [x + gw, y], [x + gw, y + gh], [x, y + gh]])
            inside = max((cv2.intersectConvexConvex(win, q.astype(np.float32))[0] / cv2.contourArea(q)
                          for q in quads), default=0.0)
            if inside >= 0.5:
                continue
            out.append(row(det, img[y:y + gh, x:x + gw], "tcgar_bg", image=img_path.stem, card_inside=float(inside)))
            n += 1
    return out


def synth_bg_rows(det, rng, n=600):
    out = []
    for i in range(n):
        frame = background(rng, 480, 640) if i % 2 else background(rng, 640, 480)
        frame = np.clip(frame, 0, 255).astype(np.uint8)
        l, t, r, b = guide_area(frame.shape[1], frame.shape[0])
        out.append(row(det, frame[t:b, l:r], "synth_bg"))
    return out


def phone_rows(det):
    out = []
    for p in sorted(REAL_DIR.glob("*.jpg")):
        img = app_resize(cv2.imread(str(p)))
        l, t, r, b = guide_area(img.shape[1], img.shape[0])
        out.append(row(det, img[t:b, l:r], "phone", image=p.name))
    return out


def passes(r, min_score, shape):
    if r["score"] < min_score:
        return False
    if shape is None:
        return True
    return (r["convex"] and shape["aspect"][0] <= r["aspect"] <= shape["aspect"][1]
            and r["fill"] >= shape["fill"] and r["angle"] <= shape["angle"])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", type=int, default=55)
    args = ap.parse_args()
    det = LearnedDetector()
    rng = np.random.default_rng(args.seed)
    rows = tcgar_rows(det) + phone_rows(det) + tcgar_bg_rows(det, rng) + synth_bg_rows(det, rng)
    (DATA_DIR / "corner-gate-rows.json").write_text(json.dumps(rows))

    groups = {
        "tcgar_good (err<=5%)": [r for r in rows if r["source"] == "tcgar" and r["err"] <= 5],
        "tcgar_bad (err>5%)": [r for r in rows if r["source"] == "tcgar" and r["err"] > 5],
        "phone": [r for r in rows if r["source"] == "phone"],
        "tcgar_bg (no card inside)": [r for r in rows if r["source"] == "tcgar_bg" and r["card_inside"] < 0.02],
        "tcgar_bg (part of a card)": [r for r in rows if r["source"] == "tcgar_bg" and r["card_inside"] >= 0.02],
        "synth_bg": [r for r in rows if r["source"] == "synth_bg"],
    }

    def pct(v, q):
        return round(float(np.percentile(v, q)), 3) if v else None

    dist = {name: {k: {f"p{q}": pct([r[k] for r in rs], q) for q in (1, 5, 50, 95, 99)}
                   for k in ("score", "aspect", "fill", "angle")} | {"n": len(rs),
                   "convex": round(sum(r["convex"] for r in rs) / max(1, len(rs)), 3)}
            for name, rs in groups.items()}
    shapes = {
        "none": None,
        "loose": {"aspect": (0.6, 1.6), "fill": 0.15, "angle": 40},
        "medium": {"aspect": (0.7, 1.4), "fill": 0.20, "angle": 30},
        "tight": {"aspect": (0.8, 1.25), "fill": 0.25, "angle": 20},
        "chosen": {"aspect": (1 / 1.5, 1.5), "fill": 0.0, "angle": 35},
    }
    table = {}
    for sname, shape in shapes.items():
        for ms in (0.06, 0.08, 0.10, 0.12):
            table[f"{sname} score>={ms}"] = {g: round(100 * sum(passes(r, ms, shape) for r in rs) / max(1, len(rs)), 1)
                                              for g, rs in groups.items()}
    summary = {"distributions": dist, "pass_rate_pct": table}
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / "corner_gate_calibration.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
