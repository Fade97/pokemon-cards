# SPDX-License-Identifier: GPL-3.0-or-later
"""Real-photo training data for the learned corner detector (issue #59): TCG-AR hand-annotated corners plus FORB
pseudo-labels, for `train_corners.py --real`.

Holdout: card roles from realset.role_of (test = the common.split_of holdout, val = 10 % of the rest, train).
  - test cards never give a training sample, so eval_tcgar.py / eval_forb.py / corner_analysis.py on the test
    split stay fair. Caveat (TCG-AR only): a train card's context crop can show a test card next to it as an
    unlabelled neighbour. Nearly every TCG-AR photo holds a test card, so a photo-level split is not possible.
  - TCG-AR val cards are the real validation set for checkpoint selection (annotated corners, the evaluator's
    fixed guide-box framing). TCG-AR cards without a TCGdex id get a role from "tcgar:<image>:<k>".
  - FORB: train-role photos only, and only where recognition with the detector's quad is confidently right
    (corner_analysis.py rows: top-1 = the true card with similarity >= --min-sim, card-shaped quad). Of the
    learned and the refine quad, the one with the higher similarity to the true card is the label.

Each sample is stored as a context window around the card: 1.6x the app's guide box (card at ~49 % of the
window height), 3:4 like the model input, 288x384 px, plus the part of the window that lies inside the photo.
train_corners.py cuts random guide boxes from it on the GPU (card at ~50-97 % of the height, as in the synthetic
scenes) and stretches them to 192x256 like the app, so the framing varies every epoch.

  CARDINDEX_DATA=... python corner_analysis.py forb     # once: detector + recognition per FORB photo (rows)
  CARDINDEX_DATA=... python corner_realset.py build     # -> <data>/corner-train/real_corners.npz (~1.5 GB)
  CARDINDEX_DATA=... python corner_realset.py stats     # -> results/corner_realset.json (counts only)

The images stay in <data>; nothing here is committed.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter

import cv2
import numpy as np

from common import DATA_DIR, RESULTS_DIR
from detect import order_quad
from eval_forb import FORB, load_gt
from eval_tcgar import annotations, guide_crop, tcgdex_id, upright
from realset import role_of

OUT_DIR = DATA_DIR / "corner-train"
OUT = OUT_DIR / "real_corners.npz"
CTX_W, CTX_H = 288, 384      # context window, 3:4 like the model input (192x256)
CTX_SCALE = 1.6              # context window height = 1.6 x the guide box height (card at 0.78 / 1.6 = 49 %)
IN_W, IN_H = 192, 256        # corner_model.IN_W / IN_H (not imported: corner_model needs torch)


def context(img: np.ndarray, quad: np.ndarray):
    """Cut the context window around a card quad (image pixels). Returns (window uint8 [CTX_H, CTX_W, 3], quad in
    window pixels, valid box (l, t, r, b) in window pixels = the part that lies inside the photo)."""
    quad = np.asarray(quad, np.float32)
    side = sorted(np.linalg.norm(quad - np.roll(quad, 1, 0), axis=1))
    card_h = (side[2] + side[3]) / 2
    wh = card_h / 0.78 * CTX_SCALE
    ww = wh * CTX_W / CTX_H
    c = quad.mean(0)
    x0, y0 = c[0] - ww / 2, c[1] - wh / 2
    s = CTX_H / wh
    M = np.float32([[s, 0, -x0 * s], [0, s, -y0 * s]])
    win = cv2.warpAffine(img, M, (CTX_W, CTX_H), flags=cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_CONSTANT)
    h, w = img.shape[:2]
    valid = np.float32([max(0, -x0 * s), max(0, -y0 * s), min(CTX_W, (w - x0) * s), min(CTX_H, (h - y0) * s)])
    return win, (quad - np.float32([x0, y0])) * s, valid


def eval_input(img: np.ndarray, quad: np.ndarray):
    """The evaluator's fixed framing (eval_tcgar.guide_crop), stretched to the model input. Returns (RGB uint8
    [IN_H, IN_W, 3], quad in input pixels)."""
    l, t, r, b = guide_crop(img, quad)
    x = cv2.resize(img[t:b, l:r], (IN_W, IN_H), interpolation=cv2.INTER_AREA)
    q = (np.asarray(quad, np.float32) - np.float32([l, t])) * np.float32([IN_W / (r - l), IN_H / (b - t)])
    return cv2.cvtColor(x, cv2.COLOR_BGR2RGB), q


def tcgar_role(img_stem: str, k: int, ptcg: str, known: set[str]) -> tuple[str, str | None]:
    cid = tcgdex_id(ptcg, known)
    return role_of(cid or f"tcgar:{img_stem}:{k}"), cid


def pick_pseudo_label(row: dict, min_sim: float):
    """The FORB label quad of a corner_analysis row, or None. Only train-role cards; recognition with the quad must
    be top-1 right at similarity >= min_sim, and the quad card-shaped."""
    if role_of(row["truth"]) != "train":
        return None, None
    best = None
    for k in ("learned", "refine"):
        r = row[k]
        if r["ok"] and r["sim_truth"] >= min_sim and row[f"shape_{k}"]["card_like"]:
            if best is None or r["sim_truth"] > row[best]["sim_truth"]:
                best = k
    return (np.float32(row[f"q_{best}"]), best) if best else (None, None)


def known_ids() -> set[str] | None:
    p = DATA_DIR / "packs-work" / "en" / "metadata.json"
    if not p.exists():
        return None
    return {c["id"] for c in json.loads(p.read_text(encoding="utf-8"))["cards"]}


def cmd_build(rows_model: str, min_sim: float, limit: int | None):
    known = known_ids()
    if known is None:
        # without the TCGdex ids every TCG-AR card would get its role from its image key, so test-split cards
        # (common.split_of on the id, as the evaluators score them) could land in the training set
        raise SystemExit(f"{DATA_DIR / 'packs-work' / 'en' / 'metadata.json'} missing: run build_packs.py for EN "
                         "first (TCG-AR cards need their TCGdex id for the test holdout)")
    stats = Counter()
    rows_path = FORB / f"corner_rows_{rows_model}.json"
    if not rows_path.exists():
        raise SystemExit(f"{rows_path} missing: run corner_analysis.py forb --embedder {rows_model} first")
    rows = json.loads(rows_path.read_text())
    rows = rows[:limit] if limit else rows
    forb = []  # (row, label quad) chosen first, so the window array can be allocated once (~330 KB per sample)
    for row in rows:
        role = role_of(row["truth"])
        stats[f"forb_{role}"] += 1
        q, which = pick_pseudo_label(row, min_sim)
        if q is None:
            stats["forb_train_rejected"] += role == "train"
            continue
        stats[f"forb_label_{which}"] += 1
        stats["forb_label_where_learned_failed"] += not row["learned"]["ok"]
        forb.append((row, q))
    anns = list(annotations())
    cap = len(anns) + len(forb)
    win = np.empty((cap, CTX_H, CTX_W, 3), np.uint8)
    quads = np.empty((cap, 4, 2), np.float32)
    valid = np.empty((cap, 4), np.float32)
    kind, src, val_x, val_q, val_key = [], [], [], [], []

    def add(img_bgr, q, k, name):
        i = len(kind)
        w, quads[i], valid[i] = context(img_bgr, q)
        win[i] = cv2.cvtColor(w, cv2.COLOR_BGR2RGB)
        kind.append(k)
        src.append(name)

    cache = {}
    for k, (img_path, quad, ptcg) in enumerate(anns):
        role, _ = tcgar_role(img_path.stem, k, ptcg, known)
        stats[f"tcgar_{role}"] += 1
        if role == "test":
            continue
        if img_path not in cache:
            cache = {img_path: cv2.imread(str(img_path))}
        img, q = upright(cache[img_path], quad)
        q = order_quad(q)
        if role == "val":
            x, qi = eval_input(img, q)
            val_x.append(x)
            val_q.append(qi)
            val_key.append(f"tcgar:{img_path.stem}:{k}")
        else:
            add(img, q, "tcgar", f"tcgar:{img_path.stem}:{k}")
    gt = {g["query_id"]: g for g in load_gt()}
    for n, (row, q) in enumerate(forb):
        img = cv2.imread(str(FORB / gt[row["query"]]["query_image"]))
        s = max(img.shape[:2]) / max(row["w"], row["h"])  # rows are in app_resize pixels (long side <= 2048)
        add(img, order_quad(q * s), "forb", f"forb:{row['query']}")
        if n % 500 == 0:
            print(f"FORB {n}/{len(forb)}", flush=True)
    n = len(kind)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    tmp = OUT.with_suffix(".npz.tmp")  # written whole, then renamed: run_corner_finetune.ps1 skips an existing OUT
    with open(tmp, "wb") as f:
        np.savez(f, win=win[:n], quad=quads[:n], valid=valid[:n], kind=np.array(kind), src=np.array(src),
                 val_x=np.stack(val_x), val_q=np.stack(val_q).astype(np.float32), val_key=np.array(val_key),
                 min_sim=np.float32(min_sim), rows_model=np.array(rows_model))
    tmp.replace(OUT)
    stats["train_samples"] = n
    stats["val_samples"] = len(val_x)
    print(dict(stats), "->", OUT)
    cmd_stats(dict(stats))


def cmd_stats(build_stats: dict | None = None):
    d = np.load(OUT)
    kind = Counter(d["kind"].tolist())
    rep = {"train_samples": int(len(d["kind"])), "by_source": dict(kind), "val_tcgar_cards": int(len(d["val_key"])),
           "min_sim": float(d["min_sim"]), "pseudo_labels_from": str(d["rows_model"]),
           "context": f"{CTX_W}x{CTX_H}, {CTX_SCALE}x guide box"}
    if build_stats:
        rep["build"] = build_stats
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / "corner_realset.json").write_text(json.dumps(rep, indent=1))
    print(json.dumps(rep, indent=1))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["build", "stats"])
    ap.add_argument("--rows", default="mnv3_256_real", help="embedder of the corner_analysis.py forb rows")
    ap.add_argument("--min-sim", type=float, default=0.5, help="FORB pseudo-label: similarity of the true card")
    ap.add_argument("--limit", type=int, default=None)
    args = ap.parse_args()
    cv2.setNumThreads(2)
    if args.cmd == "build":
        cmd_build(args.rows, args.min_sim, args.limit)
    else:
        cmd_stats()


if __name__ == "__main__":
    main()
