# SPDX-License-Identifier: GPL-3.0-or-later
"""Evaluate the app pipeline on the real photos with the user's manual labels.

Reproduces the Android test app: resize to 2048 px (long side) -> card guide (63:88 box at 78 % of the frame
height, centred, plus 7 % margin) -> detector inside the guide (classic CV or the learned corner model; classic
falls back to a centred crop) -> rectify -> embedding (.tflite, both 180-degree orientations) -> kNN over the
full gallery (trained + unseen sets).

Labels: <data>/real-photos-labels-manual.json, file -> accepted keys "<lang>/<set>:<number>". Lists with more than
4 keys (all Pikachu of a set, number unreadable) are scored at name level. Photos labelled with en/me04 show the
German print (de/me04 = holdout twin); they are scored exactly (de/me04) and at artwork level (en or de).

  uv run --extra litert python eval_real_manual.py --embedder mnv3_256_real --detector learned
  uv run --extra litert python eval_real_manual.py --all       # the full comparison matrix
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from common import DATA_DIR, EXPORT_DIR, INDEX_DIR, REAL_DIR, RESULTS_DIR, cached_index, load_manifest
from detect import LearnedDetector, app_resize, detect_app, rectify

LABELS = DATA_DIR / "real-photos-labels-manual.json"
THRESH = 0.5
TWIN_SETS = {"en/me04": "de/me04", "en/me03": "de/me03"}


def to_input(bgr, size=224):
    if bgr.shape[2] == 4:
        a = bgr[..., 3:4].astype(np.float32) / 255
        bgr = (bgr[..., :3] * a + 255 * (1 - a)).astype(np.uint8)
    rgb = cv2.cvtColor(cv2.resize(bgr, (size, size), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
    return (rgb.astype(np.float32) / 255)[None]


class TFLite:
    def __init__(self, path: Path, threads: int = 8):
        from ai_edge_litert.interpreter import Interpreter
        self.it = Interpreter(model_path=str(path), num_threads=threads)
        self.it.allocate_tensors()
        self.i = self.it.get_input_details()[0]["index"]
        self.o = self.it.get_output_details()[0]["index"]

    def __call__(self, x):
        self.it.set_tensor(self.i, x.astype(np.float32))
        self.it.invoke()
        return self.it.get_tensor(self.o)[0].copy()


class Embedder:
    def __init__(self, run: str, variant: str = "w8", threads: int = 8):
        self.tag = f"{run}_{variant}"
        self.model_file = EXPORT_DIR / f"{run}_{variant}.tflite"
        self.m = TFLite(self.model_file, threads)

    def __call__(self, bgr):
        e = self.m(to_input(bgr))
        return e / np.linalg.norm(e)

    def index(self, cards):
        from synth import load_ref_rgba
        return cached_index(INDEX_DIR / f"{self.tag}_tflite_full{len(cards)}.npy", self.model_file, cards,
                            lambda: np.stack([self(load_ref_rgba(str(c.ref_path))) for c in cards]))


def run_pipeline(img, emb, R, det):
    img = app_resize(img)
    q, _, score = detect_app(img, None if det == "classic" else det)
    conf = None if det == "classic" else score
    crop = rectify(img, q)
    s0 = R @ emb(crop)
    s1 = R @ emb(cv2.rotate(crop, cv2.ROTATE_180))
    sims = s1 if s1.max() > s0.max() else s0
    return np.argsort(-sims)[:5], sims, conf, crop


def summarize(rows, key="ok_exact", top5="top5_exact"):
    n = len(rows)
    if not n:
        return {"n": 0}
    shown = [r for r in rows if r["sim"] >= THRESH]
    ok_s = [r["sim"] for r in rows if r[key]]
    bad_s = [r["sim"] for r in rows if not r[key]]
    q = lambda v: [round(float(x), 3) for x in np.percentile(v, [0, 50, 100])] if v else None
    sweep = {f"{th:.2f}": {"shown": sum(r["sim"] >= th for r in rows),
                           "correct": sum(r["sim"] >= th and r[key] for r in rows),
                           "wrong": sum(r["sim"] >= th and not r[key] for r in rows)}
             for th in (0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6)}
    return {"n": n, "top1": sum(r[key] for r in rows), "top5": sum(r[top5] for r in rows),
            "shown@0.5": len(shown), "shown_correct": sum(r[key] for r in shown),
            "shown_wrong": sum(not r[key] for r in shown),
            "sim_correct_min_med_max": q(ok_s), "sim_wrong_min_med_max": q(bad_s), "threshold_sweep": sweep}


def evaluate(embedder_run, detector, photos_dir=REAL_DIR, save_crops=False):
    cards = load_manifest(include_holdout=True)
    key = lambda c: f"{c.lang}/{c.set_id}:{c.local_id}"
    keys = [key(c) for c in cards]
    emb = Embedder(embedder_run)
    R = emb.index(cards)
    det = "classic" if detector == "classic" else LearnedDetector()
    labels = json.loads(LABELS.read_text(encoding="utf-8"))
    rows = []
    crop_dir = DATA_DIR / "real-photos-eval-manual" / f"{embedder_run}_{detector}"
    if save_crops:
        crop_dir.mkdir(parents=True, exist_ok=True)
    for f in sorted(labels):
        acc = set(labels[f])
        twin = any(a.split(":")[0] in TWIN_SETS for a in acc)
        exact = {TWIN_SETS[a.split(":")[0]] + ":" + a.split(":")[1] for a in acc} if twin else acc
        art = acc | exact
        top, sims, conf, crop = run_pipeline(cv2.imread(str(photos_dir / f)), emb, R, det)
        tk = [keys[i] for i in top]
        if save_crops:
            cv2.imwrite(str(crop_dir / f), crop)
        rows.append({"file": f, "group": "de/me04 (twin)" if twin else "de/30th", "name_level": len(acc) > 4,
                     "top1": tk[0], "sim": float(sims[top[0]]), "detector_conf": conf,
                     "ok_exact": tk[0] in exact, "top5_exact": any(k in exact for k in tk),
                     "ok_art": tk[0] in art, "top5_art": any(k in art for k in tk)})
    res = {"embedder": f"{embedder_run} (w8 .tflite)", "detector": detector, "gallery": len(cards),
           "threshold": THRESH, "groups": {}}
    for g in ("de/30th", "de/me04 (twin)"):
        rs = [r for r in rows if r["group"] == g]
        res["groups"][g] = summarize(rs)
        if g.startswith("de/me04"):
            res["groups"][g + " artwork level (EN or DE accepted)"] = summarize(rs, "ok_art", "top5_art")
        else:
            res["groups"][g]["name_level_photos"] = sum(r["name_level"] for r in rs)
    res["all"] = summarize(rows)
    res["photos"] = rows
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--embedder", default="mnv3_256_real")
    ap.add_argument("--detector", choices=["classic", "learned"], default="learned")
    ap.add_argument("--all", action="store_true", help="old/new embedder x classic/learned detector")
    ap.add_argument("--save-crops", action="store_true")
    args = ap.parse_args()
    combos = ([(e, d) for e in ("mnv3_256", "mnv3_256_real") for d in ("classic", "learned")] if args.all
              else [(args.embedder, args.detector)])
    out = {}
    for e, d in combos:
        r = evaluate(e, d, save_crops=args.save_crops)
        out[f"{e}+{d}"] = r
        g1, g2 = r["groups"]["de/30th"], r["groups"]["de/me04 (twin)"]
        print(f"{e:>14} + {d:<8} | 30th top1 {g1['top1']}/{g1['n']} top5 {g1['top5']}/{g1['n']} "
              f"shown {g1['shown@0.5']} ({g1['shown_correct']} ok, {g1['shown_wrong']} wrong) | "
              f"me04 DE exact top1 {g2['top1']}/{g2['n']} top5 {g2['top5']}/{g2['n']} | "
              f"art top1 {r['groups']['de/me04 (twin) artwork level (EN or DE accepted)']['top1']}", flush=True)
    RESULTS_DIR.mkdir(exist_ok=True)
    name = "real_manual_matrix.json" if args.all else f"real_manual_{args.embedder}_{args.detector}.json"
    (RESULTS_DIR / name).write_text(json.dumps(out, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
