# SPDX-License-Identifier: GPL-3.0-or-later
"""Evaluate corner detection and recognition on the TCG-AR real set: 51 overhead 1920x1080 photos of play mats
with 832 hand-annotated cards (4 corners each; 636 with a pokemontcg.io id), sleeved, holo, overlapping.

TCG-AR (ULiège, https://github.com/ULiege-VIULab/tcg-ar, arXiv 2607.02090) is used with permission for eval and
training; images stay in <data>/external/tcg-ar and are never committed. The domain differs from the app: cards
are small (~150 px wide) and seen from above, so this is a stress test, not an estimate of app accuracy.

Per annotated card the app situation is simulated: crop a box around the card with its neighbours (the guide
plus 7 % margin, card filling ~78 % of the height), then
  detector   classic / learned corners inside that crop -> mean corner error in % of the card diagonal
  recognize  rectify with the detected (or the annotated) corners -> embedding -> kNN over the EN pack gallery

  CARDINDEX_DATA=... python eval_tcgar.py --embedder mnv3_256_real
"""
from __future__ import annotations

import argparse
import json
import re

import cv2
import numpy as np

from common import DATA_DIR, RESULTS_DIR
from detect import detect, order_quad, rectify
from eval_forb import gallery, split_of
from eval_real_manual import Embedder, LearnedDetector

ROOT = DATA_DIR / "external" / "tcg-ar" / "real" / "real"

# pokemontcg.io set id -> TCGdex set id where the simple rules below do not apply.
SET_EXCEPTIONS = {"pgo": "swsh10.5", "base6": "lc", "swsh45": "swsh4.5", "sm35": "sm3.5", "sm75": "sm7.5",
                  "sm115": "sm11.5", "swsh35": "swsh3.5"}


def tcgdex_id(ptcg_id: str, known: set[str]) -> str | None:
    """'sv3pt5-146' -> 'sv03.5-146', 'me1-114' -> 'me01-114', 'swsh12pt5-1' -> 'swsh12.5-001'? Try the variants
    and keep the one TCGdex knows."""
    if ptcg_id == "unknown" or "-" not in ptcg_id:
        return None
    s, num = ptcg_id.rsplit("-", 1)
    s = SET_EXCEPTIONS.get(s, s.replace("pt", "."))
    sets = {s}
    m = re.fullmatch(r"(sv|me)(\d)(\.\d)?", s)
    if m:
        sets.add(f"{m.group(1)}0{m.group(2)}{m.group(3) or ''}")
    for st in sets:
        for n in (num, num.zfill(3), num.lstrip("0") or "0"):
            if f"{st}-{n}" in known:
                return f"{st}-{n}"
    return None


def annotations():
    for txt in sorted((ROOT / "annotations_identity").glob("*.txt"), key=lambda p: int(p.stem)):
        img = ROOT / "images" / f"{txt.stem}.png"
        if not img.exists():
            img = next((ROOT / "images").glob(f"{txt.stem}.*"), None)
        for line in txt.read_text().splitlines():
            p = line.split()
            if len(p) >= 10:
                yield img, np.array(p[:8], np.float32).reshape(4, 2), p[8]


def upright(img, quad):
    """The user holds the phone along the card: when the card lies sideways, rotate the photo by 90 degrees.
    Decided on the raw quad (order_quad always returns a portrait order): sideways = the long sides run closer to
    horizontal than to vertical."""
    sides = np.roll(quad, -1, 0) - quad
    long_side = sides[np.argmax(np.linalg.norm(sides, axis=1))]
    if abs(long_side[1]) >= abs(long_side[0]):
        return img, quad
    h = img.shape[0]
    return cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE), np.stack([h - 1 - quad[:, 1], quad[:, 0]], 1)


def corner_error(pred, gt):
    """Mean corner distance in % of the card diagonal, minimised over the 4 rotations (a 180-degree card is fine,
    the embedding tries both)."""
    diag = np.linalg.norm(gt[0] - gt[2])
    g, p = order_quad(gt), order_quad(pred)
    return float(min(np.linalg.norm(np.roll(p, k, 0) - g, axis=1).mean() for k in range(4)) / diag * 100)


def guide_crop(img, quad):
    """The box the user would frame: card height = 78 % of the guide, 63:88 guide, plus 7 % margin."""
    h, w = img.shape[:2]
    c = quad.mean(0)
    side = sorted(np.linalg.norm(quad - np.roll(quad, 1, 0), axis=1))
    card_h = (side[2] + side[3]) / 2
    gh = card_h / 0.78
    gw = gh * 63 / 88
    l, t = int(max(0, c[0] - gw / 2 * 1.07)), int(max(0, c[1] - gh / 2 * 1.07))
    r, b = int(min(w, c[0] + gw / 2 * 1.07)), int(min(h, c[1] + gh / 2 * 1.07))
    return l, t, r, b


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--embedder", default="mnv3_256_real")
    args = ap.parse_args()
    emb = Embedder(args.embedder)
    learned = LearnedDetector()
    ids, R, cards = gallery("en", f"{args.embedder}_w8.tflite")
    known, pos = set(cards), {cid: i for i, cid in enumerate(ids)}
    rows, unmapped, cache = [], {}, {}
    for img_path, quad, ptcg in annotations():
        if img_path not in cache:
            cache.clear()
            cache[img_path] = cv2.imread(str(img_path))
        img, quad = upright(cache[img_path], quad)
        l, t, r, b = guide_crop(img, quad)
        g = img[t:b, l:r]
        row = {"image": img_path.stem, "ptcg": ptcg, "card_px": float(np.linalg.norm(quad[0] - quad[1]))}
        for name in ("classic", "learned"):
            if name == "classic":
                q, _ = detect(g)
            else:
                q, _ = learned(g)
            row[f"err_{name}"] = None if q is None else corner_error(q + np.float32([l, t]), quad)
        cid = tcgdex_id(ptcg, known)
        if ptcg != "unknown" and cid is None:
            unmapped[ptcg] = unmapped.get(ptcg, 0) + 1
        if cid in pos:
            row["truth"], row["split"] = cid, split_of(cid)
            for name, q in (("gt", quad), ("learned", None)):
                if q is None:
                    q, _ = learned(g)
                    q = q + np.float32([l, t])
                crop = rectify(img, order_quad(q))
                s0, s1 = R @ emb(crop), R @ emb(cv2.rotate(crop, cv2.ROTATE_180))
                sims = s1 if s1.max() > s0.max() else s0
                top = np.argsort(-sims)[:5]
                row[f"top1_{name}"] = bool(top[0] == pos[cid])
                row[f"top5_{name}"] = bool(pos[cid] in top)
                row[f"name_{name}"] = cards[ids[top[0]]]["name"] == cards[cid]["name"]
        rows.append(row)

    def pct(v):
        return round(100 * sum(v) / len(v), 1) if v else None

    summary = {"cards": len(rows), "unmapped_ids": unmapped}
    for name in ("classic", "learned"):
        errs = [r[f"err_{name}"] for r in rows]
        found = [e for e in errs if e is not None]
        summary[f"detector_{name}"] = {
            "found": pct([e is not None for e in errs]),
            "err_median_pct_diag": round(float(np.median(found)), 2) if found else None,
            "within_3pct": pct([e is not None and e <= 3 for e in errs]),
            "within_5pct": pct([e is not None and e <= 5 for e in errs])}
    rec = [r for r in rows if "truth" in r]
    summary["recognition"] = {"n": len(rec), **{
        f"{k}_{name}": pct([r[f"{k}_{name}"] for r in rec]) for name in ("gt", "learned")
        for k in ("top1", "top5", "name")}}
    summary["median_card_width_px"] = round(float(np.median([r["card_px"] for r in rows])), 1)
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out = RESULTS_DIR / f"tcgar_{args.embedder}_w8.json"
    out.write_text(json.dumps(summary, indent=1))
    (ROOT.parent.parent / f"tcgar_{args.embedder}_rows.json").write_text(json.dumps(rows))
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
