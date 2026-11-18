# SPDX-License-Identifier: GPL-3.0-or-later
"""Error analysis of the learned corner detector on real photos (issue #59): where do the corners fail, and how
much recognition do the failures cost?

Every photo is recognised with several crops, all with the same embedding and the full EN pack gallery:

  learned  the app's detector (corners_mnv3_w8) on the input the evaluators use: the whole FORB photo (2048 px),
           or the guide box around a TCG-AR card
  refine   a second detector pass on the guide box cut around the first quad (card at 78 % of the box height,
           63:88 + 7 % margin, detect.guide_crop): the framing the detector was trained on
  full     FORB only: the whole photo rectified as if it were the card (the seller framed it)
  gt       TCG-AR only: the annotated corners

A photo the detector gets wrong while another crop gets it right is a corner failure; a photo no crop gets right
is an embedding (or ground-truth) failure. FORB has no corner labels; TCG-AR does (corner error in % of the
diagonal, eval_tcgar.corner_error). The classic-detector rows of eval_forb.py are joined in when present.

  CARDINDEX_DATA=... python corner_analysis.py forb  [--limit N]   # ~1 h on 2 cores for 5,578 photos
  CARDINDEX_DATA=... python corner_analysis.py tcgar                # ~3 min
  CARDINDEX_DATA=... python corner_analysis.py summary              # -> results/corner_analysis_<model>.json
  CARDINDEX_DATA=... python corner_analysis.py compare --corners corners_mnv3_real   # shipped vs. fine-tuned
  CARDINDEX_DATA=... python corner_analysis.py sheets               # contact sheets of failures -> <data>/report/

Per-photo rows (ids, scores, quads) stay with the data: <data>/external/{forb,tcg-ar}/corner_rows_<model>.json.
"""
from __future__ import annotations

import argparse
import json
import time
from collections import Counter

import cv2
import numpy as np

from common import DATA_DIR, EXPORT_DIR, RESULTS_DIR, split_of
from detect import (
    LearnedDetector,
    app_resize,
    guide_crop,
    keep_higher_sim,
    looks_like_card,
    order_quad,
    quad_shape,
    refine,
)
from eval_forb import FORB, MAP, gallery, load_gt
from eval_real_manual import Embedder, recognize
from eval_tcgar import ROOT as TCGAR_ROOT
from eval_tcgar import annotations, corner_error, tcgdex_id, upright

REPORT_DIR = DATA_DIR / "report" / "corner-analysis"
THREADS = 2
DEFAULT_CORNERS = "corners_mnv3"


def tag(model: str, corners: str) -> str:
    """Row/result file tag: the embedder, plus the corner run when it is not the shipped one."""
    return model if corners == DEFAULT_CORNERS else f"{model}_{corners}"


def detector(corners: str) -> LearnedDetector:
    return LearnedDetector(EXPORT_DIR / f"{corners}_w8.tflite", threads=THREADS)


def pick(r: dict) -> dict:
    """The deployable two-pass rule on a row: the learned or the refine crop, whichever has the higher top-1
    similarity (detect.keep_higher_sim)."""
    return keep_higher_sim((r["learned"]["sim"], r["learned"]), (r["refine"]["sim"], r["refine"]))[0]


class Recognizer:
    def __init__(self, model: str):
        self.emb = Embedder(model, threads=THREADS)
        self.ids, self.R, self.cards = gallery("en", f"{model}_w8.tflite")
        self.pos = {cid: i for i, cid in enumerate(self.ids)}

    def __call__(self, img, quad, truth: str) -> dict:
        sims = recognize(self.emb, self.R, img, quad)
        t = self.pos[truth]
        rank = int((sims > sims[t]).sum())
        top = int(np.argmax(sims))
        return {"ok": rank == 0, "top5": rank < 5, "rank": rank, "sim": round(float(sims[top]), 4),
                "sim_truth": round(float(sims[t]), 4), "pred": self.ids[top],
                "name_ok": self.cards[self.ids[top]]["name"] == self.cards[truth]["name"]}


def shape(q, w, h) -> dict:
    s = quad_shape(q)
    return {"area_frac": round(s["area"] / (w * h), 4), "aspect": round(s["aspect"], 3), "angle": round(s["angle"], 1),
            "card_like": bool(looks_like_card(q))}


def quad_shift(a, b) -> float:
    """Mean corner distance between two quads in % of a's diagonal."""
    a, b = np.asarray(a), np.asarray(b)
    return float(np.linalg.norm(a - b, axis=1).mean() / max(np.linalg.norm(a[0] - a[2]), 1e-6) * 100)


def cmd_forb(model: str, corners: str, limit: int | None, test_only: bool):
    rec, det = Recognizer(model), detector(corners)
    mapping = json.loads(MAP.read_text())
    gt = load_gt()[:limit] if limit else load_gt()
    out = FORB / f"corner_rows_{tag(model, corners)}.json"
    part = out.with_suffix(".partial.json")
    rows = json.loads(part.read_text()) if part.exists() else []  # resume an interrupted run
    done, t0 = {r["query"] for r in rows}, time.time()
    for n, g in enumerate(gt):
        m = mapping.get(g["groundtruth_images"][0])
        if not m or not m["id"] or (test_only and split_of(m["id"]) != "test") or g["query_id"] in done:
            continue
        img = cv2.imread(str(FORB / g["query_image"]))
        if img is None:
            continue
        img = app_resize(img)
        h, w = img.shape[:2]
        q1, s1 = det(img)
        q2, s2, _ = refine(det, img, q1)
        full = np.float32([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]])
        row = {"query": g["query_id"], "difficulty": g["difficulty"], "split": split_of(m["id"]), "truth": m["id"],
               "w": w, "h": h, "q_learned": q1.round(1).tolist(), "q_refine": q2.round(1).tolist(),
               "score_learned": round(s1, 4), "score_refine": round(s2, 4), "shift_refine": round(quad_shift(q1, q2), 2),
               "shape_learned": shape(q1, w, h), "shape_refine": shape(q2, w, h)}
        for name, q in (("learned", q1), ("refine", q2), ("full", full)):
            row[name] = rec(img, q, m["id"])
        rows.append(row)
        if len(rows) % 200 == 0:
            print(f"{n} photos, {len(rows)} scored, {time.time() - t0:.0f}s", flush=True)
            part.write_text(json.dumps(rows))
    out.write_text(json.dumps(rows))
    part.unlink(missing_ok=True)
    print(f"{len(rows)} rows -> {out} ({time.time() - t0:.0f}s)")


def cmd_tcgar(model: str, corners: str):
    rec, det = Recognizer(model), detector(corners)
    known = set(rec.cards)
    rows, cache = [], {}
    for k, (img_path, quad, ptcg) in enumerate(annotations()):
        if img_path not in cache:
            cache.clear()
            cache[img_path] = cv2.imread(str(img_path))
        img0 = cache[img_path]
        img, quad = upright(img0, quad)
        sideways = img is not img0
        l, t, r, b = guide_crop(img, quad)
        q1, s1 = det(img[t:b, l:r])
        q1 = q1 + np.float32([l, t])
        q2, s2, _ = refine(det, img, q1)
        quad = order_quad(quad)  # the annotations come in any corner order; rectify needs TL, TR, BR, BL
        card_w = (np.linalg.norm(quad[1] - quad[0]) + np.linalg.norm(quad[2] - quad[3])) / 2
        row = {"k": k, "image": img_path.stem, "ptcg": ptcg, "sideways": sideways,
               "card_px": round(float(card_w), 1), "box": [l, t, r, b],
               "q_gt": quad.round(1).tolist(), "q_learned": q1.round(1).tolist(), "q_refine": q2.round(1).tolist(),
               "score_learned": round(s1, 4), "score_refine": round(s2, 4),
               "err_learned": round(corner_error(q1, quad), 3), "err_refine": round(corner_error(q2, quad), 3),
               "card_like_learned": bool(looks_like_card(q1))}
        cid = tcgdex_id(ptcg, known)
        if cid in rec.pos:
            row["truth"], row["split"] = cid, split_of(cid)
            for name, q in (("gt", quad), ("learned", q1), ("refine", q2)):
                row[name] = rec(img, q, cid)
        rows.append(row)
    out = TCGAR_ROOT.parent.parent / f"corner_rows_{tag(model, corners)}.json"
    out.write_text(json.dumps(rows))
    print(f"{len(rows)} rows -> {out}")


# ---------------------------------------------------------------- summary
def pct(v) -> float | None:
    v = list(v)
    return round(100 * sum(v) / len(v), 1) if v else None


def outcome(rows, a: str, b: str) -> dict:
    """2x2 of top-1 right/wrong for crop a vs. crop b."""
    c = Counter((r[a]["ok"], r[b]["ok"]) for r in rows)
    return {f"{a}_only": c[(True, False)], f"{b}_only": c[(False, True)], "both": c[(True, True)],
            "neither": c[(False, False)]}


def forb_summary(rows, classic: dict) -> dict:
    out = {}
    for r in rows:
        c = classic.get(r["query"])
        r["classic"] = {"ok": c["ok_exact"]} if c else None
        r["any"] = {"ok": any(r[k]["ok"] for k in ("learned", "refine", "full")) or bool(c and c["ok_exact"])}
    groups = {"all": rows, "split=test": [r for r in rows if r["split"] == "test"],
              "split=train": [r for r in rows if r["split"] == "train"]}
    for d in ("easy", "medium", "hard"):
        groups[f"test,difficulty={d}"] = [r for r in rows if r["split"] == "test" and r["difficulty"] == d]
    for name, rs in groups.items():
        if not rs:
            continue
        s = {"n": len(rs)}
        for k in ("learned", "refine", "full", "any"):
            s[f"top1_{k}"] = pct(r[k]["ok"] for r in rs)
        s["top5_learned"] = pct(r["learned"]["top5"] for r in rs)
        s["top1_classic"] = pct(r["classic"]["ok"] for r in rs if r["classic"])
        s["learned_vs_refine"] = outcome(rs, "learned", "refine")
        s["learned_vs_full"] = outcome(rs, "learned", "full")
        miss = [r for r in rs if not r["learned"]["ok"]]
        s["learned_misses"] = len(miss)
        # a miss that some other crop recognises = a corner (framing) failure; the rest is embedding / truth
        s["misses_fixed_by_other_crop"] = sum(r["any"]["ok"] for r in miss)
        s["misses_fixed_by_refine"] = sum(r["refine"]["ok"] for r in miss)
        # deployable two-pass rule: run both passes, keep the crop whose top-1 similarity is higher
        s["top1_pick_higher_sim"] = pct(pick(r)["ok"] for r in rs)
        # noise floor: nearly the same quad (< 1 % shift), yet the two crops disagree on top-1
        near = [r for r in rs if r["shift_refine"] < 1]
        s["noise_flips_at_shift<1%"] = {"n": len(near), "flip_pct": pct(r["learned"]["ok"] != r["refine"]["ok"]
                                                                         for r in near)}
        # geometric suspects: learned quad not card-shaped, or the second pass moves it by >= 5 %
        geo = [r for r in rs if not r["shape_learned"]["card_like"] or r["shift_refine"] >= 5]
        s["geometric_suspects"] = {"n": len(geo), "share_pct": round(100 * len(geo) / len(rs), 1),
                                   "top1_learned": pct(r["learned"]["ok"] for r in geo),
                                   "top1_refine": pct(r["refine"]["ok"] for r in geo)}
        out[name] = s
    # where the learned crop fails: bucket the photos by the quad and the photo
    t = [r for r in rows]

    def bucket(key, f):
        b = {}
        for r in t:
            b.setdefault(f(r), []).append(r)
        return {k: {"n": len(v), "top1_learned": pct(r["learned"]["ok"] for r in v),
                    "top1_refine": pct(r["refine"]["ok"] for r in v), "top1_any": pct(r["any"]["ok"] for r in v),
                    "corner_fail": pct((not r["learned"]["ok"]) and r["any"]["ok"] for r in v)}
                for k, v in sorted(b.items())}

    def area(r):
        a = r["shape_learned"]["area_frac"]
        return "a<10%" if a < 0.1 else "b10-25%" if a < 0.25 else "c25-50%" if a < 0.5 else "d>=50%"

    def aspect(r):
        a = r["w"] / r["h"]
        return "landscape>1.15" if a > 1.15 else "portrait<0.87" if a < 0.87 else "square-ish"

    def score(r):
        s = r["score_learned"]
        return "a<0.10" if s < 0.10 else "b0.10-0.20" if s < 0.2 else "c0.20-0.35" if s < 0.35 else "d>=0.35"

    out["buckets_all_photos"] = {
        "quad_area_of_photo": bucket("area", area), "photo_aspect": bucket("aspect", aspect),
        "detector_score": bucket("score", score),
        "card_like": bucket("card_like", lambda r: str(r["shape_learned"]["card_like"])),
        "refine_shift": bucket("shift", lambda r: "a<2%" if r["shift_refine"] < 2 else "b2-5%" if r["shift_refine"] < 5
                               else "c5-15%" if r["shift_refine"] < 15 else "d>=15%")}
    return out


def tcgar_summary(rows) -> dict:
    def det(rs, k):
        e = np.array([r[f"err_{k}"] for r in rs])
        return {"n": len(rs), "err_median_pct_diag": round(float(np.median(e)), 2),
                "within_3pct": pct(e <= 3), "within_5pct": pct(e <= 5), "over_10pct": pct(e > 10)}

    out = {"detector": {k: det(rows, k) for k in ("learned", "refine")},
           "detector_by_split": {sp: {k: det([r for r in rows if r.get("split") == sp], k)
                                      for k in ("learned", "refine")} for sp in ("train", "test")}}
    small = np.median([r["card_px"] for r in rows])
    out["detector_by_card_size"] = {f"card<{small:.0f}px": det([r for r in rows if r["card_px"] < small], "learned"),
                                    f"card>={small:.0f}px": det([r for r in rows if r["card_px"] >= small], "learned")}
    out["detector_by_orientation"] = {"sideways": det([r for r in rows if r["sideways"]], "learned"),
                                      "upright": det([r for r in rows if not r["sideways"]], "learned")}
    rec = [r for r in rows if "truth" in r]
    for name, rs in (("all", rec), ("test", [r for r in rec if r["split"] == "test"]),
                     ("train", [r for r in rec if r["split"] == "train"])):
        s = {"n": len(rs), **{f"top1_{k}": pct(r[k]["ok"] for r in rs) for k in ("gt", "learned", "refine")},
             **{f"top5_{k}": pct(r[k]["top5"] for r in rs) for k in ("gt", "learned")},
             "gt_vs_learned": outcome(rs, "gt", "learned")}
        # recognition by corner error of the learned quad
        for lo, hi in ((0, 3), (3, 10), (10, 1e9)):
            b = [r for r in rs if lo <= r["err_learned"] < hi]
            s[f"err{lo}-{hi if hi < 1e9 else 'inf'}"] = {"n": len(b), "top1_gt": pct(r["gt"]["ok"] for r in b),
                                                       "top1_learned": pct(r["learned"]["ok"] for r in b)}
        out[f"recognition_{name}"] = s
    return out


def cmd_summary(model: str, corners: str):
    res = {"model": model, "detector": f"{corners}_w8", "gallery": "EN pack (packs-work/en)"}
    fp = FORB / f"corner_rows_{tag(model, corners)}.json"
    if fp.exists():
        cp = FORB / f"forb_{model}_w8_classic_rows.json"
        classic = {r["query"]: r for r in json.loads(cp.read_text())} if cp.exists() else {}
        res["forb"] = forb_summary(json.loads(fp.read_text()), classic)
    tp = TCGAR_ROOT.parent.parent / f"corner_rows_{tag(model, corners)}.json"
    if tp.exists():
        res["tcgar"] = tcgar_summary(json.loads(tp.read_text()))
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out = RESULTS_DIR / f"corner_analysis_{tag(model, corners)}.json"
    out.write_text(json.dumps(res, indent=1))
    print(json.dumps(res, indent=1))


def cmd_compare(model: str, corners: str):
    """Side by side: the shipped detector vs. `corners` on the held-out test split (both summaries must exist)."""
    a = json.loads((RESULTS_DIR / f"corner_analysis_{model}.json").read_text())
    b = json.loads((RESULTS_DIR / f"corner_analysis_{tag(model, corners)}.json").read_text())

    def get(d, *path):
        for k in path:
            d = d.get(k) if isinstance(d, dict) else None
        return d

    lines = [("TCG-AR test: corner error median, % diag", ("tcgar", "detector_by_split", "test", "learned",
                                                           "err_median_pct_diag")),
             ("TCG-AR test: within 3 %", ("tcgar", "detector_by_split", "test", "learned", "within_3pct")),
             ("TCG-AR test: over 10 %", ("tcgar", "detector_by_split", "test", "learned", "over_10pct")),
             ("TCG-AR test: top-1 annotated corners", ("tcgar", "recognition_test", "top1_gt")),
             ("TCG-AR test: top-1 learned corners", ("tcgar", "recognition_test", "top1_learned")),
             ("TCG-AR test: top-1 refine pass", ("tcgar", "recognition_test", "top1_refine")),
             ("FORB test: top-1 learned", ("forb", "split=test", "top1_learned")),
             ("FORB test: top-1 refine pass", ("forb", "split=test", "top1_refine")),
             ("FORB test hard: top-1 learned", ("forb", "test,difficulty=hard", "top1_learned"))]
    print(f"{'':42s} {DEFAULT_CORNERS:>14s} {corners:>18s}")
    for label, path in lines:
        print(f"{label:42s} {str(get(a, *path)):>14s} {str(get(b, *path)):>18s}")
    res = {"model": model, "old": DEFAULT_CORNERS, "new": corners, "table": {
        label: [get(a, *path), get(b, *path)] for label, path in lines}}
    for name, root, key in (("forb_test", FORB, "query"), ("tcgar_test", TCGAR_ROOT.parent.parent, "k")):
        pa, pb = root / f"corner_rows_{model}.json", root / f"corner_rows_{tag(model, corners)}.json"
        if pa.exists() and pb.exists():
            res[name] = paired(json.loads(pa.read_text()), json.loads(pb.read_text()), key)
    out = RESULTS_DIR / f"corner_compare_{tag(model, corners)}.json"
    out.write_text(json.dumps(res, indent=1))
    print(json.dumps({k: v for k, v in res.items() if k != "table"}, indent=1))
    print("->", out)


def paired(old_rows, new_rows, key: str) -> dict:
    """Same test photos, old vs. new detector: how many each one gets right that the other misses, the share of
    learned scores under the app's live-scan gate (CornerDetector.DEFAULT_MIN_SCORE = 0.10) and, for TCG-AR,
    the per-card corner error change."""
    a = {r[key]: r for r in old_rows if r.get("split") == "test"}
    b = {r[key]: r for r in new_rows if r.get("split") == "test"}
    ks = sorted(set(a) & set(b))
    out = {"n": len(ks)}
    if not ks:
        return out

    for crop, f in (("learned", lambda r: r["learned"]), ("refine", lambda r: r["refine"]), ("pick_higher_sim", pick)):
        oo = np.array([f(a[k])["ok"] for k in ks])
        nn = np.array([f(b[k])["ok"] for k in ks])
        out[crop] = {"old_top1": pct(oo), "new_top1": pct(nn), "old_only": int((oo & ~nn).sum()),
                     "new_only": int((~oo & nn).sum())}
    sa = np.array([a[k]["score_learned"] for k in ks])
    sb = np.array([b[k]["score_learned"] for k in ks])
    out["score_learned_median"] = [round(float(np.median(sa)), 3), round(float(np.median(sb)), 3)]
    out["score_learned_below_0.10_pct"] = [pct(sa < 0.10), pct(sb < 0.10)]
    if "err_learned" in a[ks[0]]:
        ea = np.array([a[k]["err_learned"] for k in ks])
        eb = np.array([b[k]["err_learned"] for k in ks])
        out["err_learned_mean"] = [round(float(ea.mean()), 2), round(float(eb.mean()), 2)]
        out["err_better_by_1pct"], out["err_worse_by_1pct"] = int((eb < ea - 1).sum()), int((eb > ea + 1).sum())
    return out


# ---------------------------------------------------------------- contact sheets (local only, never committed)
def _tile(img, quads, label, size=320):
    vis = img.copy()
    th = max(2, round(max(img.shape[:2]) / 300))
    for q, col in quads:
        cv2.polylines(vis, [np.asarray(q, np.int32)], True, col, th)
        cv2.circle(vis, tuple(int(v) for v in q[0]), th * 3, col, -1)  # TL
    s = size / max(vis.shape[:2])
    vis = cv2.resize(vis, (round(vis.shape[1] * s), round(vis.shape[0] * s)), interpolation=cv2.INTER_AREA)
    tile = np.full((size + 34, size, 3), 255, np.uint8)
    tile[:vis.shape[0], :vis.shape[1]] = vis
    for i, line in enumerate(label.split("\n")[:2]):
        cv2.putText(tile, line, (3, size + 13 + 16 * i), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 0, 0), 1, cv2.LINE_AA)
    return tile


def _sheet(tiles, path, cols=6):
    if not tiles:
        return
    while len(tiles) % cols:
        tiles.append(np.full_like(tiles[0], 255))
    grid = np.vstack([np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)])
    path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(path), grid, [cv2.IMWRITE_JPEG_QUALITY, 85])
    print(path)


def cmd_sheets(model: str, n: int, seed: int):
    """Red = learned quad, green = refine quad (FORB) / annotated quad (TCG-AR), blue = refine (TCG-AR)."""
    rng = np.random.default_rng(seed)
    gt = {g["query_id"]: g for g in load_gt()}
    fp = FORB / f"corner_rows_{model}.json"
    if fp.exists():
        rows = json.loads(fp.read_text())
        groups = {
            "forb_corner_fail": [r for r in rows if not r["learned"]["ok"] and (r["refine"]["ok"] or r["full"]["ok"])],
            "forb_all_fail": [r for r in rows if not (r["learned"]["ok"] or r["refine"]["ok"] or r["full"]["ok"])],
            "forb_refine_broke": [r for r in rows if r["learned"]["ok"] and not r["refine"]["ok"]],
        }
        for name, rs in groups.items():
            chosen = [rs[i] for i in sorted(rng.choice(len(rs), min(n, len(rs)), replace=False))] if rs else []
            tiles = []
            for r in chosen:
                img = app_resize(cv2.imread(str(FORB / gt[r["query"]]["query_image"])))
                lab = (f"q{r['query']} {r['difficulty'][0]} s={r['score_learned']:.2f} {r['truth']}\n"
                       f"L:{'ok' if r['learned']['ok'] else r['learned']['rank']} R:{'ok' if r['refine']['ok'] else r['refine']['rank']}"
                       f" F:{'ok' if r['full']['ok'] else r['full']['rank']} a={r['shape_learned']['area_frac']:.2f}")
                tiles.append(_tile(img, [(r["q_learned"], (0, 0, 255)), (r["q_refine"], (0, 200, 0))], lab))
            _sheet(tiles, REPORT_DIR / f"{name}.jpg")
    tp = TCGAR_ROOT.parent.parent / f"corner_rows_{model}.json"
    if tp.exists():
        rows = sorted(json.loads(tp.read_text()), key=lambda r: -r["err_learned"])[:n]
        tiles, cache = [], {}
        for r in rows:
            p = next((TCGAR_ROOT / "images").glob(f"{r['image']}.*"))
            if p not in cache:
                cache = {p: cv2.imread(str(p))}
            img = cache[p]
            if r["sideways"]:
                img = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
            l, t, rr, b = r["box"]
            off = np.float32([l, t])
            qs = [(np.float32(r["q_gt"]) - off, (0, 200, 0)), (np.float32(r["q_learned"]) - off, (0, 0, 255)),
                  (np.float32(r["q_refine"]) - off, (255, 0, 0))]
            lab = (f"#{r['k']} err {r['err_learned']:.1f}% ref {r['err_refine']:.1f}%\n"
                   f"s={r['score_learned']:.2f} {r.get('split', '-')} {r['card_px']:.0f}px")
            tiles.append(_tile(img[t:b, l:rr], qs, lab, size=240))
        _sheet(tiles, REPORT_DIR / "tcgar_worst.jpg", cols=8)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["forb", "tcgar", "summary", "compare", "sheets"])
    ap.add_argument("--embedder", default="mnv3_256_real")
    ap.add_argument("--corners", default=DEFAULT_CORNERS, help="corner run: <data>/export/<corners>_w8.tflite")
    ap.add_argument("--test-only", action="store_true", help="forb: only the test split (a fine-tuned detector)")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--n", type=int, default=48, help="sheets: tiles per sheet")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--threads", type=int, default=2, help="TFLite / OpenCV threads (Vulcan is shared)")
    args = ap.parse_args()
    global THREADS
    THREADS = args.threads
    cv2.setNumThreads(args.threads)
    if args.cmd == "forb":
        cmd_forb(args.embedder, args.corners, args.limit, args.test_only)
    elif args.cmd == "tcgar":
        cmd_tcgar(args.embedder, args.corners)
    elif args.cmd == "summary":
        cmd_summary(args.embedder, args.corners)
    elif args.cmd == "compare":
        cmd_compare(args.embedder, args.corners)
    else:
        cmd_sheets(args.embedder, args.n, args.seed)


if __name__ == "__main__":
    main()
