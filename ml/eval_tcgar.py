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
  --two-pass also recognises with two-pass detection (issue #78, detect.two_pass): cut the guide box around the
             learned quad, detect again, keep the crop with the higher top-1 similarity ("two_pass" next to
             "learned", same cards)

  CARDINDEX_DATA=... python eval_tcgar.py --embedder mnv3_256_real
  CARDINDEX_DATA=... python eval_tcgar.py --embedder mnv3_256_forb --two-pass
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import cv2
import numpy as np

from common import DATA_DIR, RESULTS_DIR
from detect import LearnedDetector, detect, guide_crop, order_quad, two_pass  # guide_crop: re-exported
from eval_forb import gallery, split_of
from eval_real_manual import Embedder, recognize

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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--embedder", default="mnv3_256_real")
    ap.add_argument("--variant", default="w8", help="export file <embedder>_<variant>.tflite")
    ap.add_argument("--work", type=Path, default=None, help="build_packs work dir with en embeddings of that file")
    ap.add_argument("--two-pass", action="store_true",
                    help="also recognise with two-pass detection: keep the higher-similarity crop (issue #78)")
    args = ap.parse_args()
    emb = Embedder(args.embedder, args.variant)
    learned = LearnedDetector()
    ids, R, cards = gallery("en", f"{args.embedder}_{args.variant}.tflite", **({"work": args.work} if args.work else {}))
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
        q, _ = detect(g)
        row["err_classic"] = None if q is None else corner_error(q + np.float32([l, t]), quad)
        q_learned, s_learned = learned(g)
        q_learned = q_learned + np.float32([l, t])
        row["err_learned"] = corner_error(q_learned, quad)
        cid = tcgdex_id(ptcg, known)
        if ptcg != "unknown" and cid is None:
            unmapped[ptcg] = unmapped.get(ptcg, 0) + 1
        if cid in pos:
            row["truth"], row["split"] = cid, split_of(cid)

            def match(q):
                v = recognize(emb, R, img, order_quad(q))
                return float(v.max()), v

            sims_by = {"gt": match(quad)[1], "learned": match(q_learned)[1]}
            if args.two_pass:
                q2, _, sims_by["two_pass"], row["second_pass_used"] = two_pass(learned, img, q_learned, s_learned, match)
                row["err_two_pass"] = corner_error(q2, quad)
            for name, sims in sims_by.items():
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
    names = ("gt", "learned", "two_pass") if args.two_pass else ("gt", "learned")

    def rec_summary(rs):
        s = {"n": len(rs), **{f"{k}_{name}": pct([r[f"{k}_{name}"] for r in rs]) for name in names
                              for k in ("top1", "top5", "name")}}
        if args.two_pass:
            one, two = [r["top1_learned"] for r in rs], [r["top1_two_pass"] for r in rs]
            s["two_pass_vs_learned"] = {"second_pass_kept": sum(r["second_pass_used"] for r in rs),
                                        "gained": sum(b and not a for a, b in zip(one, two)),
                                        "lost": sum(a and not b for a, b in zip(one, two))}
        return s

    if args.two_pass:  # corner error of the first pass and of the kept quad, both on the mapped cards only
        for name in ("learned", "two_pass"):
            e = [r[f"err_{name}"] for r in rec]
            summary[f"detector_{name}_mapped"] = {
                "n": len(e), "err_median_pct_diag": round(float(np.median(e)), 2),
                "within_3pct": pct([x <= 3 for x in e]), "within_5pct": pct([x <= 5 for x in e])}
    summary["recognition"] = rec_summary(rec)
    # TCG-AR train-split cards may be used in training (train.py --real): only the test split is a fair score then.
    for split in ("train", "test"):
        summary[f"recognition_split={split}"] = rec_summary([r for r in rec if r["split"] == split])
    summary["median_card_width_px"] = round(float(np.median([r["card_px"] for r in rows])), 1)
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    suffix = "_twopass" if args.two_pass else ""
    out = RESULTS_DIR / f"tcgar_{args.embedder}_{args.variant}{suffix}.json"
    out.write_text(json.dumps(summary, indent=1))
    (ROOT.parent.parent / f"tcgar_{args.embedder}{suffix}_rows.json").write_text(json.dumps(rows))  # leak_check.py
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
