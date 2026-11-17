# SPDX-License-Identifier: GPL-3.0-or-later
"""Leak check of a train.py --real checkpoint against the real-photo test split. Counts and card ids only (no file
names, no per-photo rows) -> results/leak_check_<run>.json.

  classes      ArcFace classes whose id is test/val, or that are a same-art twin/reprint of a test card
               (realset.py twins, baseline embedding cos >= 0.9)
  crops        training crops (after the train filters) of test/val cards in the samples file used for the run
  FORB/TCG-AR  test-split photos whose truth is a class, a manifest card (seen as a class by the init model
               mnv3_256_real), or has a twin class; top-1 with those photos left out (bound on the effect)

  uv run python leak_check.py --run mnv3_256_forb --samples <data>/real-train/samples_mnv3_256_forb.json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch

import realset
from common import CKPT_DIR, RESULTS_DIR, load_manifest
from eval_forb import FORB
from eval_tcgar import ROOT as TCGAR_ROOT


def score(rows, key):
    return {"n": len(rows), "top1": round(100 * sum(r[key] for r in rows) / len(rows), 1) if rows else None}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", required=True)
    ap.add_argument("--samples", type=Path, default=realset.SAMPLES)
    args = ap.parse_args()
    ck = torch.load(CKPT_DIR / f"{args.run}.pt", map_location="cpu", weights_only=True)
    classes = set(ck["classes"])
    class_ids = {k.split("/", 1)[1] for k in classes}
    twin_of: dict[str, set[str]] = {}
    for k, other, _ in json.loads(realset.TWINS.read_text())["pairs"]:
        twin_of.setdefault(other, set()).add(k)
    twin_class = {t: sorted(k for k in ks if k in classes) for t, ks in twin_of.items()}
    manifest_ids = {c.card_id for c in load_manifest()}

    samples = json.loads(args.samples.read_text())
    tr = realset.real_samples(samples, "train", {k.split("/", 1)[1] for k in classes if k.startswith("en/")})
    out = {
        "run": args.run, "epoch": ck["epoch"], "classes": len(classes),
        "classes_with_test_or_val_id": sum(realset.role_of(i) != "train" for i in class_ids),
        "classes_that_twin_a_test_card": sorted({k for t, ks in twin_class.items() if realset.role_of(t) == "test"
                                                 for k in ks}),
        "classes_that_twin_a_val_card": len({k for t, ks in twin_class.items() if realset.role_of(t) == "val"
                                             for k in ks}),
        "train_crops": len(tr), "train_crops_of_test_or_val_cards": sum(realset.role_of(s["card_id"]) != "train"
                                                                        for s in tr),
        "samples_file_test_split_crops": sum(realset.role_of(s["card_id"]) == "test" for s in samples),
    }
    for name, path, key in (("forb", FORB / f"forb_{args.run}_w8_learned_rows.json", "ok_exact"),
                            ("tcgar", TCGAR_ROOT.parent.parent / f"tcgar_{args.run}_rows.json", "top1_learned")):
        if not path.exists():
            out[name] = "rows missing: run the evaluator first"
            continue
        rows = [r for r in json.loads(path.read_text()) if r.get("split") == "test" and "truth" in r]
        twinned = [r for r in rows if twin_class.get(r["truth"])]
        out[name] = {
            "test_photos": len(rows),
            "truth_is_a_class": sum(r["truth"] in class_ids for r in rows),
            "truth_is_a_manifest_card": sum(r["truth"] in manifest_ids for r in rows),
            "truth_has_a_twin_class": {"photos": len(twinned), "cards": sorted({r["truth"] for r in twinned})},
            "top1_all": score(rows, key),
            "top1_without_twinned": score([r for r in rows if not twin_class.get(r["truth"])], key),
        }
    RESULTS_DIR.mkdir(exist_ok=True)
    path = RESULTS_DIR / f"leak_check_{args.run}.json"
    path.write_text(json.dumps(out, indent=1))
    print(json.dumps(out, indent=1), "->", path)


if __name__ == "__main__":
    main()
