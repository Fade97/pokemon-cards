# SPDX-License-Identifier: GPL-3.0-or-later
"""Export a fine-tuned embedding model to LiteRT (.tflite), float32 and int8, and verify it.

Runs in the separate export environment (export_env.sh; on Windows inside WSL because litert-converter has no
Windows wheels):

  wsl -d Ubuntu -- ~/cardindex-export/.venv/bin/python /mnt/d/AI/pokemon-cards/ml/export_litert.py --run mnv3_256

The exported model takes NHWC float32 RGB in [0, 1] (1 x 224 x 224 x 3, the rectified card squashed to a square)
and returns the L2-normalized embedding; ImageNet normalization is baked in.
Checks: cosine(TFLite, PyTorch) on real test crops, and top-1 of each model against its own reference index.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import torch
import torch.nn as nn

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))
from models import INPUT, MEAN, STD, EmbeddingNet  # noqa: E402

DATA = Path(os.environ.get("CARDINDEX_DATA_POSIX", "/mnt/d/AI/pokemon-cards-data"))


class PhoneModel(nn.Module):
    """NHWC RGB [0,1] -> normalize -> EmbeddingNet (NCHW inside)."""

    def __init__(self, net: EmbeddingNet):
        super().__init__()
        self.net = net
        self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

    def forward(self, x):
        x = x.permute(0, 3, 1, 2)
        return self.net((x - self.mean) / self.std)


def to_input(bgr) -> np.ndarray:
    if bgr.shape[2] == 4:
        a = bgr[..., 3:4].astype(np.float32) / 255
        bgr = (bgr[..., :3] * a + 255 * (1 - a)).astype(np.uint8)
    rgb = cv2.cvtColor(cv2.resize(bgr, (INPUT, INPUT), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
    return (rgb.astype(np.float32) / 255)[None]


def load_manifest_rows():
    import csv
    with open(ML / "manifest.csv", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def ref_path(r):
    return DATA / "ref" / r["lang"] / r["set_id"] / f"{r['local_id']}.png"


def run_tflite(path, xs):
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=str(path), num_threads=8)
    it.allocate_tensors()
    inp, out = it.get_input_details()[0], it.get_output_details()[0]
    res = []
    t = time.time()
    for x in xs:
        it.set_tensor(inp["index"], x)
        it.invoke()
        res.append(it.get_tensor(out["index"])[0].copy())
    return np.stack(res), (time.time() - t) / len(xs) * 1000


def l2n(a):
    return a / np.linalg.norm(a, axis=1, keepdims=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run", required=True)
    ap.add_argument("--n-check", type=int, default=300, help="test crops used for the equivalence check")
    ap.add_argument("--n-calib", type=int, default=256, help="val crops used for int8 calibration")
    args = ap.parse_args()
    import litert_torch
    from litert_torch.quantize import pt2e_quantizer
    from litert_torch.quantize.quant_config import QuantConfig
    from torchao.quantization.pt2e import quantize_pt2e

    ck = torch.load(DATA / "checkpoints" / f"{args.run}.pt", map_location="cpu", weights_only=True)
    net = EmbeddingNet(ck["backbone"], ck["dim"], pretrained=False)
    net.load_state_dict(ck["model"])
    model = PhoneModel(net).eval()
    out_dir = DATA / "export"
    out_dir.mkdir(exist_ok=True)
    sample = (torch.rand(1, INPUT, INPUT, 3),)
    report = {"run": args.run, "backbone": ck["backbone"], "dim": ck["dim"], "input": "1x224x224x3 float32 RGB [0,1]"}

    # float32
    f32 = out_dir / f"{args.run}_f32.tflite"
    litert_torch.convert(model, sample).export(str(f32))
    report["size_mb_f32"] = round(f32.stat().st_size / 1e6, 2)

    # int8 (static PT2E, per-channel weights, calibrated on val crops)
    all_rows = load_manifest_rows()
    rows = [r for r in all_rows if r.get("holdout", "0") != "1"]  # equivalence check on the trained gallery
    val_crops = sorted((DATA / "synth" / "val-crops").glob("*.jpg"))[: args.n_calib]
    calib = [torch.from_numpy(to_input(cv2.imread(str(p)))) for p in val_crops]
    i8 = out_dir / f"{args.run}_int8.tflite"
    try:
        q = pt2e_quantizer.PT2EQuantizer().set_global(
            pt2e_quantizer.get_symmetric_quantization_config(is_per_channel=True, is_dynamic=False))
        m = torch.export.export(model, sample).module()
        m = quantize_pt2e.prepare_pt2e(m, q)
        with torch.no_grad():
            for x in calib:
                m(x)
        m = quantize_pt2e.convert_pt2e(m, fold_quantize=False)
        litert_torch.convert(m, sample, quant_config=QuantConfig(pt2e_quantizer=q)).export(str(i8))
        report["int8_method"] = "PT2E static, per-channel int8 weights + int8 activations"
    except Exception as e:  # fall back to dynamic-range weight quantization of the float model
        report["int8_pt2e_error"] = repr(e)[:300]
        from ai_edge_quantizer import quantizer, recipe
        qt = quantizer.Quantizer(str(f32))
        qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
        qt.quantize().export_model(str(i8), overwrite=True)
        report["int8_method"] = "ai-edge-quantizer dynamic_wi8_afp32 (int8 weights, float activations)"
    report["size_mb_int8"] = round(i8.stat().st_size / 1e6, 2)

    # int8 weights only (dynamic range): float activations, keeps accuracy where full int8 hurts (hard-swish/SE)
    w8 = out_dir / f"{args.run}_w8.tflite"
    from ai_edge_quantizer import quantizer, recipe
    qt = quantizer.Quantizer(str(f32))
    qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
    qt.quantize().export_model(str(w8), overwrite=True)
    report["w8_method"] = "ai-edge-quantizer dynamic_wi8_afp32 (int8 weights, float activations)"
    report["size_mb_w8"] = round(w8.stat().st_size / 1e6, 2)

    # equivalence on real test crops
    labels = json.loads((DATA / "synth" / "test" / "labels.json").read_text())
    step = max(1, len(labels) // args.n_check)
    sel = labels[::step][: args.n_check]
    xs = [to_input(cv2.imread(str(DATA / "synth" / "test-crops" / l["file"]))) for l in sel]
    with torch.no_grad():
        ref = l2n(torch.cat([model(torch.from_numpy(x)) for x in xs]).numpy())
    key2i = {r["key"]: i for i, r in enumerate(rows)}
    truth = np.array([key2i[l["key"]] for l in sel])
    refs_in = [to_input(cv2.imread(str(ref_path(r)), cv2.IMREAD_UNCHANGED)) for r in rows]
    with torch.no_grad():
        idx_pt = l2n(torch.cat([model(torch.from_numpy(x)) for x in refs_in]).numpy())
    report["top1_pytorch_subset"] = float(((ref @ idx_pt.T).argmax(1) == truth).mean())
    for tag, path in (("f32", f32), ("int8", i8), ("w8", w8)):
        emb, ms = run_tflite(path, xs)
        emb = l2n(emb)
        cos = (emb * ref).sum(1)
        idx, _ = run_tflite(path, refs_in)
        idx = l2n(idx)
        report[f"{tag}_cosine_vs_pytorch"] = {"min": float(cos.min()), "mean": float(cos.mean()),
                                              "share_gt_0.99": float((cos > 0.99).mean())}
        report[f"{tag}_top1_subset_own_index"] = float(((emb @ idx.T).argmax(1) == truth).mean())
        report[f"{tag}_cpu_ms_per_image_x86_8threads"] = round(ms, 2)
        np.save(DATA / "index" / f"{args.run}_{tag}_tflite.npy", idx.astype(np.float32))
    d = ck["dim"]
    report["index_size"] = {
        tag: {"cards": n, "dim": d, "float32_kb": round(n * d * 4 / 1024, 1),
              "float16_kb": round(n * d * 2 / 1024, 1), "int8_kb": round(n * d / 1024, 1)}
        for tag, n in (("trained_sets", len(rows)), ("with_unseen", len(all_rows)))}
    report["n_check"] = len(sel)
    print(json.dumps(report, indent=1))
    (ML / "results").mkdir(exist_ok=True)
    (ML / "results" / f"export_{args.run}.json").write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
