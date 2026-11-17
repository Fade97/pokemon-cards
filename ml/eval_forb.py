# SPDX-License-Identifier: GPL-3.0-or-later
"""Evaluate the app pipeline on FORB "animated cards": 6,025 real photos of English Pokémon cards (mostly eBay
listing photos: hands, sleeves, top loaders, slabs, busy backgrounds) with the exact print as ground truth.

FORB (NeurIPS 2023, https://github.com/pxiangwu/FORB) is used with permission for eval and training; the images
stay in <data>/external/forb and are never committed or published. See docs/research/real-photo-datasets.md.

Gallery: the full EN language pack (every TCGdex EN card with an image, ~22k), i.e. what the app searches when
English is installed, not just the 870 trained cards.

  map    FORB ground truth (pokemon.com scan, "SM7_EN_52") -> TCGdex id. Each scan is embedded and matched against
         the gallery; a mapping is accepted only when the top-1 shares the collector number. -> forb_map.json
  eval   photo -> detector (classic or learned corners, on the whole photo: the seller framed the card) ->
         rectify -> embedding (both 180-degree orientations) -> kNN over the gallery. Scored by split (a fixed
         20 % of the cards is held out from any training) and by FORB difficulty.

  CARDINDEX_DATA=... python eval_forb.py map
  CARDINDEX_DATA=... python eval_forb.py eval --detector learned --embedder mnv3_256_real
"""
from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict

import cv2
import numpy as np

from common import DATA_DIR, EXPORT_DIR, RESULTS_DIR, split_of
from detect import center_crop_fallback, detect, rectify
from eval_real_manual import THRESH, Embedder, LearnedDetector, summarize

FORB = DATA_DIR / "external" / "forb"
WORK = DATA_DIR / "packs-work"
MAP = FORB / "forb_map.json"

def norm_number(s: str) -> str:
    """'SWSH090' -> 'SWSH90', '052' -> '52', 'sv47' -> 'SV47': compare collector numbers across sources."""
    return re.sub(r"(?<!\d)0+(?=\d)", "", s.strip().upper())


def forb_number(url: str) -> str | None:
    """'.../SM7_EN_52.png' -> '52'; None for the two ground-truth images from shops, which carry no number."""
    stem = url.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    return stem.split("_EN_", 1)[1] if "_EN_" in stem else None


def gallery(lang: str = "en", model_name: str | None = None):
    meta = json.loads((WORK / lang / "embeddings.json").read_text())
    if model_name and meta["model"] != model_name:
        raise SystemExit(f"{lang} embeddings are from {meta['model']}, not {model_name}: run build_packs.py embed")
    rows = np.frombuffer((WORK / lang / "embeddings.bin").read_bytes(), "<f4").reshape(len(meta["ids"]), meta["dim"])
    cards = {c["id"]: c for c in json.loads((WORK / lang / "metadata.json").read_text(encoding="utf-8"))["cards"]}
    return meta["ids"], np.ascontiguousarray(rows), cards


def load_gt():
    return [json.loads(line) for line in (FORB / "animated_cards_gt.ndjson").open()]


def cmd_map(emb: Embedder, ids, R, cards):
    gt = load_gt()
    scans = {}
    for g in gt:
        scans.setdefault(g["groundtruth_images"][0], g["groundtruth_urls"][0])
    out, reasons = {}, defaultdict(int)
    for img, url in sorted(scans.items()):
        bgr = cv2.imread(str(FORB / img))
        if bgr is None:
            reasons["missing image"] += 1
            continue
        sims = R @ emb(bgr)
        if forb_number(url) is None:
            reasons["no collector number in the ground truth"] += 1
            continue
        num = norm_number(forb_number(url))
        top = np.argsort(-sims)[:10]
        # The scan is clean, so the right print is near the top; several prints share an artwork (reprints,
        # promos), so take the best candidate whose collector number matches.
        hit = next((i for i in top if norm_number(cards[ids[i]]["localId"]) == num), None)
        if hit is None:
            reasons["no number match in top-10"] += 1
            out[img] = {"url": url, "id": None, "top1": ids[top[0]], "sim": round(float(sims[top[0]]), 3)}
            continue
        out[img] = {"url": url, "id": ids[hit], "sim": round(float(sims[hit]), 3), "rank": int(np.where(top == hit)[0][0])}
        reasons["mapped"] += 1
    MAP.write_text(json.dumps(out, indent=1))
    print(dict(reasons), "->", MAP)


def cmd_eval(emb: Embedder, ids, R, cards, detector: str, limit: int | None):
    mapping = json.loads(MAP.read_text())
    det = LearnedDetector() if detector == "learned" else "classic"
    pos = {cid: i for i, cid in enumerate(ids)}
    rows = []
    gt = load_gt()[:limit] if limit else load_gt()
    for n, g in enumerate(gt):
        m = mapping.get(g["groundtruth_images"][0])
        if not m or not m["id"]:
            continue
        img = cv2.imread(str(FORB / g["query_image"]))
        if img is None:
            continue
        h, w = img.shape[:2]
        s = 2048 / max(h, w)
        if s < 1:
            img = cv2.resize(img, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
        if det == "classic":
            q, _ = detect(img)
            q = center_crop_fallback(img) if q is None else q
        else:
            q, _ = det(img)
        crop = rectify(img, q)
        s0, s1 = R @ emb(crop), R @ emb(cv2.rotate(crop, cv2.ROTATE_180))
        sims = s1 if s1.max() > s0.max() else s0
        top = np.argsort(-sims)[:5]
        truth = pos[m["id"]]
        name = cards[m["id"]]["name"]
        rows.append({"query": g["query_id"], "difficulty": g["difficulty"], "split": split_of(m["id"]),
                     "truth": m["id"], "pred": ids[top[0]], "sim": float(sims[top[0]]),
                     "ok_exact": bool(top[0] == truth), "top5_exact": bool(truth in top),
                     "ok_name": cards[ids[top[0]]]["name"] == name,
                     "top5_name": any(cards[ids[i]]["name"] == name for i in top)})
        if n % 500 == 0:
            print(n, flush=True)
    groups = {"all": rows}
    for key in ("split", "difficulty"):
        for r in rows:
            groups.setdefault(f"{key}={r[key]}", []).append(r)
    summary = {k: {**summarize(v), "name_top1": sum(r["ok_name"] for r in v),
                   "name_top5": sum(r["top5_name"] for r in v)} for k, v in groups.items()}
    tag = f"forb_{emb.tag}_{detector}"
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / f"{tag}.json").write_text(json.dumps(
        {"gallery": len(ids), "threshold": THRESH, "summary": summary}, indent=1))
    # Per-photo rows contain only ids and scores, but keep them with the data, not in the repo.
    (FORB / f"{tag}_rows.json").write_text(json.dumps(rows))
    for k, v in summary.items():
        if v["n"]:
            print(f"{k:22s} n={v['n']:5d}  top1 {100 * v['top1'] / v['n']:5.1f}  top5 {100 * v['top5'] / v['n']:5.1f}"
                  f"  name-top1 {100 * v['name_top1'] / v['n']:5.1f}  shown@{THRESH} {v['shown@0.5']}"
                  f" ({v['shown_wrong']} wrong)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["map", "eval"])
    ap.add_argument("--embedder", default="mnv3_256_real")
    ap.add_argument("--detector", choices=["classic", "learned"], default="learned")
    ap.add_argument("--limit", type=int, default=None, help="only the first N FORB queries (quick check)")
    args = ap.parse_args()
    emb = Embedder(args.embedder)
    ids, R, cards = gallery("en", f"{args.embedder}_w8.tflite")
    if not (EXPORT_DIR / f"{args.embedder}_w8.tflite").exists():
        raise SystemExit("embedder not found")
    if args.cmd == "map":
        cmd_map(emb, ids, R, cards)
    else:
        cmd_eval(emb, ids, R, cards, args.detector, args.limit)


if __name__ == "__main__":
    main()
