# SPDX-License-Identifier: GPL-3.0-or-later
"""Evaluate the app pipeline on FORB "animated cards": 6,025 real photos of English Pokémon cards (mostly eBay
listing photos: hands, sleeves, top loaders, slabs, busy backgrounds) with the exact print as ground truth.

FORB (NeurIPS 2023, https://github.com/pxiangwu/FORB) is used with permission for eval and training; the images
stay in <data>/external/forb and are never committed or published. See docs/research/real-photo-datasets.md.

Gallery: the full EN language pack (every TCGdex EN card with an image, ~22k), i.e. what the app searches when
English is installed, not just the 870 trained cards.

  map    FORB ground truth (pokemon.com scan, "SM7_EN_52") -> TCGdex id: the print with the URL's set code and
         number when the gallery has it, else the best of the scan's top-10 gallery matches with the same number
         and similarity >= 0.6. -> forb_map.json
  eval   photo -> detector (classic or learned corners, on the whole photo: the seller framed the card) ->
         rectify -> embedding (both 180-degree orientations) -> kNN over the gallery. Scored by split (a fixed
         20 % of the cards is held out from any training) and by FORB difficulty.
         --two-pass (learned only, issue #78): cut the guide box around the first quad, detect again, embed both
         crops and keep the one with the higher top-1 similarity (detect.two_pass). The one-pass result of the same
         photos is kept next to it ("two_pass" in the summary), so one run gives the paired comparison.

  CARDINDEX_DATA=... python eval_forb.py map
  CARDINDEX_DATA=... python eval_forb.py eval --detector learned --embedder mnv3_256_real
  CARDINDEX_DATA=... python eval_forb.py eval --embedder mnv3_256_forb --two-pass --split test
"""
from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

from common import DATA_DIR, EXPORT_DIR, RESULTS_DIR, split_of
from detect import LearnedDetector, app_resize, center_crop_fallback, detect, two_pass
from eval_real_manual import THRESH, Embedder, recognize, summarize

FORB = DATA_DIR / "external" / "forb"
WORK = DATA_DIR / "packs-work"
MAP = FORB / "forb_map.json"
MIN_MAP_SIM = 0.6


def norm_number(s: str) -> str:
    """'SWSH090' -> 'SWSH90', '052' -> '52', 'sv47' -> 'SV47': compare collector numbers across sources."""
    return re.sub(r"(?<!\d)0+(?=\d)", "", s.strip().upper())


def forb_number(url: str) -> str | None:
    """'.../SM7_EN_52.png' -> '52'; None for the two ground-truth images from shops, which carry no number."""
    stem = url.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    return stem.split("_EN_", 1)[1] if "_EN_" in stem else None


# pokemon.com set code (upper-cased, as in the FORB ground-truth URL) -> TCGdex EN set id, where lower-casing and
# "SM35" -> "sm3.5" do not give it.
FORB_SETS = {"CEL": "cel25", "CELC": "cel25cc", "HSP": "hgssp", "POP%20Series%202": "pop2", "POP%20Series_3": "pop3"}


def forb_set(url: str, known_sets: set[str]) -> str | None:
    """'.../SM35/SM35_EN_14.png' -> 'sm3.5'; None when no TCGdex set fits."""
    code = url.rsplit("/", 1)[-1].split("_EN_", 1)[0]
    cands = [FORB_SETS.get(code, code.lower())]
    m = re.fullmatch(r"([a-z]+\d+)5", cands[0])
    if m:
        cands.append(f"{m.group(1)}.5")
    return next((c for c in reversed(cands) if c in known_sets), None)


def gallery(lang: str = "en", model_name: str | None = None, work: Path = WORK):
    meta = json.loads((work / lang / "embeddings.json").read_text())
    if model_name and meta["model"] != model_name:
        raise SystemExit(f"{lang} embeddings are from {meta['model']}, not {model_name}: run build_packs.py embed")
    rows = np.frombuffer((work / lang / "embeddings.bin").read_bytes(), "<f4").reshape(len(meta["ids"]), meta["dim"])
    cards = {c["id"]: c for c in json.loads((work / lang / "metadata.json").read_text(encoding="utf-8"))["cards"]}
    return meta["ids"], np.ascontiguousarray(rows), cards


def load_gt():
    return [json.loads(line) for line in (FORB / "animated_cards_gt.ndjson").open()]


def cmd_map(emb: Embedder, ids, R, cards):
    gt = load_gt()
    scans = {}
    for g in gt:
        scans.setdefault(g["groundtruth_images"][0], g["groundtruth_urls"][0])
    by_set_number = {(c["setId"], norm_number(c["localId"])): cid for cid, c in cards.items()}
    known_sets = {c["setId"] for c in cards.values()}
    pos = {cid: i for i, cid in enumerate(ids)}
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
        # The URL names set and number: when TCGdex has that print, take it directly.
        direct = by_set_number.get((forb_set(url, known_sets), num))
        if direct in pos:
            out[img] = {"url": url, "id": direct, "sim": round(float(sims[pos[direct]]), 3), "by": "set+number"}
            reasons["mapped by set and number"] += 1
            continue
        top = np.argsort(-sims)[:10]
        # The scan is clean, so the right print is near the top; several prints share an artwork (reprints,
        # promos), so take the best candidate whose collector number matches.
        hit = next((i for i in top if norm_number(cards[ids[i]]["localId"]) == num), None)
        # Only where TCGdex files the print elsewhere (XY alternate arts "XYA 107a" -> xy9-107a, sim >= 0.85); a weak
        # match with the same number is another card (sm3.5 has no images: SM35_EN_14 -> sm2-14 at 0.45).
        if hit is not None and sims[hit] < MIN_MAP_SIM:
            hit = None
        if hit is None:
            reasons["no number match in top-10"] += 1
            out[img] = {"url": url, "id": None, "top1": ids[top[0]], "sim": round(float(sims[top[0]]), 3)}
            continue
        out[img] = {"url": url, "id": ids[hit], "sim": round(float(sims[hit]), 3), "rank": int(np.where(top == hit)[0][0]),
                    "by": "embedding"}
        reasons["mapped by embedding + number"] += 1
    MAP.write_text(json.dumps(out, indent=1))
    print(dict(reasons), "->", MAP)


def misses_by_set(rows: list[dict]) -> dict[str, dict[str, int]]:
    """Per TCGdex set of the true card: photos and top-1 misses, sets with the most misses first."""
    n: dict[str, list[int]] = {}
    for r in rows:
        v = n.setdefault(r["truth"].rsplit("-", 1)[0], [0, 0])
        v[0] += 1
        v[1] += not r["ok_exact"]
    return {s: {"n": a, "miss": m} for s, (a, m) in sorted(n.items(), key=lambda kv: (-kv[1][1], kv[0])) if m}


def score_sims(sims, truth: str, ids, cards, pos) -> dict:
    """Top-1 / top-5, exact print and same name, of one similarity vector against the true card."""
    top = np.argsort(-sims)[:5]
    name = cards[truth]["name"]
    return {"pred": ids[top[0]], "sim": float(sims[top[0]]), "ok_exact": bool(top[0] == pos[truth]),
            "top5_exact": bool(pos[truth] in top), "ok_name": cards[ids[top[0]]]["name"] == name,
            "top5_name": any(cards[ids[i]]["name"] == name for i in top)}


def two_pass_summary(rows: list[dict]) -> dict:
    """One pass vs. two-pass on the same photos (rows of an --two-pass run): top-1 / top-5 of both, how often the
    second pass was kept, photos gained and lost."""
    one = [r["one_pass"]["ok_exact"] for r in rows]
    two = [r["ok_exact"] for r in rows]
    return {"n": len(rows), "one_pass_top1": sum(one), "two_pass_top1": sum(two),
            "one_pass_top5": sum(r["one_pass"]["top5_exact"] for r in rows),
            "two_pass_top5": sum(r["top5_exact"] for r in rows),
            "second_pass_kept": sum(r["second_pass_used"] for r in rows),
            "gained": sum(b and not a for a, b in zip(one, two)), "lost": sum(a and not b for a, b in zip(one, two))}


def cmd_eval(emb: Embedder, ids, R, cards, detector: str, limit: int | None, use_two_pass: bool = False,
             split: str | None = None):
    if use_two_pass and detector != "learned":
        raise SystemExit("--two-pass needs the learned detector")
    mapping = json.loads(MAP.read_text())
    det = LearnedDetector() if detector == "learned" else "classic"
    pos = {cid: i for i, cid in enumerate(ids)}
    rows = []
    gt = load_gt()[:limit] if limit else load_gt()
    for n, g in enumerate(gt):
        m = mapping.get(g["groundtruth_images"][0])
        if not m or not m["id"] or (split and split_of(m["id"]) != split):
            continue
        img = cv2.imread(str(FORB / g["query_image"]))
        if img is None:
            continue
        img = app_resize(img)
        row = {"query": g["query_id"], "difficulty": g["difficulty"], "split": split_of(m["id"]), "truth": m["id"]}
        if det == "classic":
            q, _ = detect(img)
            sims = recognize(emb, R, img, center_crop_fallback(img) if q is None else q)
        elif use_two_pass:
            seen = []

            def match(quad):
                v = recognize(emb, R, img, quad)
                seen.append(v)
                return float(v.max()), v

            q, s = det(img)
            _, _, sims, used = two_pass(det, img, q, s, match)
            row["one_pass"] = score_sims(seen[0], m["id"], ids, cards, pos)
            row["second_pass_used"] = used
        else:
            q, _ = det(img)
            sims = recognize(emb, R, img, q)
        rows.append({**row, **score_sims(sims, m["id"], ids, cards, pos)})
        if n % 500 == 0:
            print(n, flush=True)
    groups = {"all": rows}
    for key in ("split", "difficulty"):
        for r in rows:
            groups.setdefault(f"{key}={r[key]}", []).append(r)
    for r in rows:  # the test split by difficulty: the fair score once the train split is used for training
        groups.setdefault(f"split={r['split']},difficulty={r['difficulty']}", []).append(r)
    summary = {k: {**summarize(v), "name_top1": sum(r["ok_name"] for r in v),
                   "name_top5": sum(r["top5_name"] for r in v),
                   **({"two_pass": two_pass_summary(v)} if use_two_pass else {})} for k, v in groups.items()}
    summary["test_misses_by_set"] = misses_by_set([r for r in rows if r["split"] == "test"])
    tag = f"forb_{emb.tag}_{detector}" + ("_twopass" if use_two_pass else "") + (f"_{split}" if split else "")
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / f"{tag}.json").write_text(json.dumps(
        {"gallery": len(ids), "threshold": THRESH, "two_pass": use_two_pass, "split": split or "all",
         "summary": summary}, indent=1))
    # Per-photo rows contain only ids and scores, but keep them with the data, not in the repo.
    (FORB / f"{tag}_rows.json").write_text(json.dumps(rows))
    for k, v in summary.items():
        if v.get("n"):
            line = (f"{k:22s} n={v['n']:5d}  top1 {100 * v['top1'] / v['n']:5.1f}  top5 {100 * v['top5'] / v['n']:5.1f}"
                    f"  name-top1 {100 * v['name_top1'] / v['n']:5.1f}  shown@{THRESH} {v['shown@0.5']}"
                    f" ({v['shown_wrong']} wrong)")
            if use_two_pass:
                t = v["two_pass"]
                line += (f"  one-pass top1 {100 * t['one_pass_top1'] / v['n']:5.1f}, 2nd kept {t['second_pass_kept']},"
                         f" +{t['gained']}/-{t['lost']}")
            print(line)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["map", "eval"])
    ap.add_argument("--embedder", default="mnv3_256_real")
    ap.add_argument("--detector", choices=["classic", "learned"], default="learned")
    ap.add_argument("--variant", default="w8", help="export file <embedder>_<variant>.tflite")
    ap.add_argument("--work", type=Path, default=WORK, help="build_packs work dir with en embeddings of that file")
    ap.add_argument("--limit", type=int, default=None, help="only the first N FORB queries (quick check)")
    ap.add_argument("--two-pass", action="store_true",
                    help="eval, learned: detect again on the guide box around the first quad and keep the crop with "
                         "the higher top-1 similarity (issue #78)")
    ap.add_argument("--split", choices=["train", "test"], default=None,
                    help="eval: only this card split (common.split_of)")
    args = ap.parse_args()
    if not (EXPORT_DIR / f"{args.embedder}_{args.variant}.tflite").exists():
        raise SystemExit("embedder not found")
    emb = Embedder(args.embedder, args.variant)
    ids, R, cards = gallery("en", f"{args.embedder}_{args.variant}.tflite", args.work)
    if args.cmd == "map":
        cmd_map(emb, ids, R, cards)
    else:
        cmd_eval(emb, ids, R, cards, args.detector, args.limit, args.two_pass, args.split)


if __name__ == "__main__":
    main()
