# SPDX-License-Identifier: GPL-3.0-or-later
"""Export the learned corner detector to LiteRT (float32 and int8 weights) and verify parity with PyTorch.

Runs in the WSL export environment (export_env.sh):
  wsl -d Ubuntu -- ~/cardindex-export/.venv/bin/python /mnt/d/AI/pokemon-cards/ml/export_corners.py --run corners_mnv3

I/O of the exported model: see corner_model.py / README ("Learned corner detector").
Parity: max/mean absolute coordinate difference (input pixels) between .tflite and PyTorch on the fixed synthetic
corner test scenes, plus the corner metrics of every variant against the true corners.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import torch

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))
from corner_model import IN_H, IN_W, CornerNet, PhoneCornerNet  # noqa: E402

DATA = Path(os.environ.get("CARDINDEX_DATA_POSIX", "/mnt/d/AI/pokemon-cards-data"))


def run_tflite(path, xs):
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=str(path), num_threads=4)
    it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    res, t = [], time.time()
    for x in xs:
        it.set_tensor(i["index"], x)
        it.invoke()
        res.append(it.get_tensor(o["index"])[0].copy())
    return np.stack(res), (time.time() - t) / len(xs) * 1000, ([int(v) for v in i["shape"]], [int(v) for v in o["shape"]])


def metrics(pred_px, gt):
    h = (np.linalg.norm(gt[:, 3] - gt[:, 0], axis=-1) + np.linalg.norm(gt[:, 2] - gt[:, 1], axis=-1)) / 2
    err = np.linalg.norm(pred_px - gt, axis=-1) / h[:, None]
    return {"corner_err_mean_pct_h": float(err.mean() * 100), "corner_err_median_pct_h": float(np.median(err) * 100),
            "pck_2pct": float((err < 0.02).mean()), "pck_5pct": float((err < 0.05).mean())}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run", default="corners_mnv3")
    ap.add_argument("--n-check", type=int, default=400)
    args = ap.parse_args()
    import litert_torch
    from ai_edge_quantizer import quantizer, recipe

    ck = torch.load(DATA / "checkpoints" / f"{args.run}.pt", map_location="cpu", weights_only=True)
    net = CornerNet(ck["backbone"], pretrained=False)
    net.load_state_dict(ck["model"])
    model = PhoneCornerNet(net).eval()
    out = DATA / "export"
    out.mkdir(exist_ok=True)
    sample = (torch.rand(1, IN_H, IN_W, 3),)
    f32 = out / f"{args.run}_f32.tflite"
    litert_torch.convert(model, sample).export(str(f32))
    w8 = out / f"{args.run}_w8.tflite"
    qt = quantizer.Quantizer(str(f32))
    qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
    qt.quantize().export_model(str(w8), overwrite=True)

    test = torch.load(DATA / "synth" / "corners_test.pt", weights_only=True)
    n = min(args.n_check, len(test["x"]))
    xs = [(test["x"][i].permute(1, 2, 0).float().numpy() / 255)[None].astype(np.float32) for i in range(n)]
    gt = test["c"][:n].numpy()
    with torch.no_grad():
        pt = np.stack([model(torch.from_numpy(x))[0].numpy() for x in xs])
    scale = np.array([IN_W, IN_H], np.float32)
    rep = {"run": args.run, "backbone": ck["backbone"], "epoch": ck["epoch"], "n_check": n,
           "params_m": round(sum(p.numel() for p in net.parameters()) / 1e6, 2),
           "pytorch": metrics(pt[..., :2] * scale, gt)}
    for tag, path in (("f32", f32), ("w8", w8)):
        tf, ms, shapes = run_tflite(path, xs)
        d = np.abs(tf[..., :2] - pt[..., :2]) * scale
        rep[tag] = {"size_mb": round(path.stat().st_size / 1e6, 2), "io_shapes": shapes,
                    "coord_diff_vs_pytorch_px": {"max": float(d.max()), "mean": float(d.mean())},
                    "conf_diff_max": float(np.abs(tf[..., 2] - pt[..., 2]).max()),
                    "x86_cpu_ms_4threads": round(ms, 2), **metrics(tf[..., :2] * scale, gt)}
    print(json.dumps(rep, indent=1))
    (ML / "results").mkdir(exist_ok=True)
    (ML / "results" / f"export_{args.run}.json").write_text(json.dumps(rep, indent=2))


if __name__ == "__main__":
    main()
