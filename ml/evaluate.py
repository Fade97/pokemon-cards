# SPDX-License-Identifier: GPL-3.0-or-later
"""Evaluate a fine-tuned run on the held-out synthetic test sets.

* three views (metrics.full_report): trained sets vs. the 870-card gallery (headline), trained sets vs. the full
  gallery incl. the unseen set, and the unseen set (de/30th, never trained on) vs. the full gallery
* top-1/top-5 by set and language, error categories, simulated collector-number rerank
* ArcFace class weights as an alternative index (trained cards only)
* optional real OCR (EasyOCR) on the number strip of every n-th test image: read rate + rerank
Writes ml/results/eval_<run>.json.
"""
from __future__ import annotations

import argparse
import json

import cv2
import numpy as np
import torch

from baselines import load_crops
from common import CKPT_DIR, RESULTS_DIR, SYNTH_DIR, device, load_manifest, number_key
from metrics import evaluate, full_report, rerank, topk_scores
from models import embed_arrays, preprocess
from pipeline import NumberOCR, build_index, load_model


def real_ocr(name, labels, truth, scores, cards, every, ocr):
    from detect import center_crop_fallback, detect
    sel = list(range(0, len(labels), every))
    read = correct = 0
    nums = []
    for i in sel:
        img = cv2.imread(str(SYNTH_DIR / name / labels[i]["file"]))
        q, _ = detect(img)
        q = q if q is not None else center_crop_fallback(img)
        num, _total = ocr.read(img, q)
        nums.append(num)
        read += num is not None
        correct += num == number_key(cards[truth[i]].local_id)
    sub = np.array(sel)
    topk = topk_scores(scores[sub], 5)
    rr = rerank(topk, None, cards, nums)
    return {"n": len(sel), "read_rate": read / len(sel), "correct_rate": correct / len(sel),
            "wrong_given_read": (read - correct) / max(read, 1),
            "top1_before": float((topk[:, 0] == truth[sub]).mean()),
            "top1_after_rerank": float((rr[:, 0] == truth[sub]).mean())}


def learned_crops(name: str, key2i: dict):
    """Synthetic test frames (3:4 portrait, like the model input) -> learned corners -> rectified crops."""
    from detect import rectify
    from eval_real_manual import LearnedDetector
    det = LearnedDetector()
    labels = json.loads((SYNTH_DIR / name / "labels.json").read_text())
    crops = []
    for l in labels:
        img = cv2.imread(str(SYNTH_DIR / name / l["file"]))
        q, _ = det(img)
        crops.append(rectify(img, q))
    return crops, np.array([key2i[l["key"]] for l in labels])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run", required=True)
    ap.add_argument("--ocr-every", type=int, default=0, help="run EasyOCR on every n-th test image (0 = off)")
    ap.add_argument("--detector", choices=["classic", "learned"], default="classic",
                    help="crops from detect.py (classic) or from the learned corner model (.tflite w8, whole frame)")
    args = ap.parse_args()
    global load_crops
    if args.detector == "learned":
        load_crops = learned_crops
    dev = device()
    cards = load_manifest(include_holdout=True)
    n_trained = sum(not c.holdout for c in cards)
    key2i = {c.key: i for i, c in enumerate(cards)}
    model = load_model(args.run, dev)
    R = build_index(model, cards, args.run, dev, cache=False)
    test, t_test = load_crops("test", key2i)
    unseen, t_unseen = load_crops("test30th", key2i)
    s_test = embed_arrays(model, [preprocess(c) for c in test], dev) @ R.T
    s_unseen = embed_arrays(model, [preprocess(c) for c in unseen], dev) @ R.T
    res = {"run": args.run, **full_report(s_test, t_test, s_unseen, t_unseen, cards, n_trained)}
    # language twin never trained on: German de/me04 vs. the full gallery that also holds the trained en/me04
    if (SYNTH_DIR / "testdeme04" / "labels.json").exists():
        twin, t_twin = load_crops("testdeme04", key2i)
        s_twin = embed_arrays(model, [preprocess(c) for c in twin], dev) @ R.T
        tk = topk_scores(s_twin, 5)
        r = evaluate(tk, t_twin, cards)
        rng = np.random.default_rng(7)
        from metrics import simulate_ocr
        rr = rerank(tk, None, cards, [simulate_ocr(cards[i], rng) for i in t_twin])
        r["top1_after_rerank"] = float((rr[:, 0] == t_twin).mean())
        r["artwork_level_top1"] = float(np.mean([cards[p].card_id == cards[t].card_id for p, t in zip(tk[:, 0], t_twin)]))
        r.pop("by_group", None)
        res["twin_de_me04_in_full"] = r

    ck = torch.load(CKPT_DIR / f"{args.run}.pt", map_location="cpu", weights_only=False)
    if ck["classes"] == [c.key for c in cards[:n_trained]]:
        W = torch.nn.functional.normalize(ck["arc"]["W"], dim=-1).numpy()
        Q = embed_arrays(model, [preprocess(c) for c in test], dev)
        r = evaluate(topk_scores(Q @ W.T, 5), t_test, cards[:n_trained])
        res["arcface_weight_index_main"] = {"top1": r["top1"], "top5": r["top5"]}
    rr_perfect = rerank(topk_scores(s_test[:, :n_trained], 5), None, cards[:n_trained],
                        [number_key(cards[t].local_id) for t in t_test])
    res["main_top1_after_perfect_ocr"] = float((rr_perfect[:, 0] == t_test).mean())

    if args.ocr_every:
        ocr = NumberOCR(dev.type == "cuda")
        lab_test = json.loads((SYNTH_DIR / "test" / "labels.json").read_text())
        lab_unseen = json.loads((SYNTH_DIR / "test30th" / "labels.json").read_text())
        res["real_ocr_easyocr"] = {
            "main": real_ocr("test", lab_test, t_test, s_test[:, :n_trained], cards[:n_trained], args.ocr_every, ocr),
            "unseen_in_full": real_ocr("test30th", lab_unseen, t_unseen, s_unseen, cards, max(1, args.ocr_every // 2), ocr),
        }
    short = {k: ({kk: vv for kk, vv in v.items() if kk not in ("by_group",)} if isinstance(v, dict) else v)
             for k, v in res.items()}
    print(json.dumps(short, indent=1))
    RESULTS_DIR.mkdir(exist_ok=True)
    suffix = "" if args.detector == "classic" else "_learned_detector"
    (RESULTS_DIR / f"eval_{args.run}{suffix}.json").write_text(json.dumps(res, indent=2))


if __name__ == "__main__":
    main()
