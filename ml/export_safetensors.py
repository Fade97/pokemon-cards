# SPDX-License-Identifier: GPL-3.0-or-later
"""Export a training checkpoint (.pt) to .safetensors for publishing (ADR 0008).

Published weights are .tflite (export_litert.py, export_corners.py) and .safetensors only. A .pt file is a pickle
and can run code when loaded, so it stays private; safetensors holds only tensors plus a str -> str header.

  uv run python export_safetensors.py --run mnv3_256_real   # embedding model
  uv run python export_safetensors.py --run corners_mnv3    # corner detector

Output: <data>/export/<run>.safetensors. Tensors are the model's state_dict (load_state_dict-ready). For embedding
models the ArcFace class weights follow under "arc." (evaluate.py uses them as a weight index). The header metadata
holds the plain fields of the checkpoint (kind, backbone, dim, epoch, classes as JSON) needed to rebuild the model:

  from safetensors import safe_open
  with safe_open(path, "pt") as f:
      meta = f.metadata()
      sd = {k: f.get_tensor(k) for k in f.keys() if not k.startswith("arc.")}
  net = EmbeddingNet(meta["backbone"], int(meta["dim"]), pretrained=False); net.load_state_dict(sd)
"""
from __future__ import annotations

import argparse
import json

import torch
from safetensors.torch import load_file, save_file

from common import CKPT_DIR, EXPORT_DIR


def to_safetensors(ck: dict) -> tuple[dict[str, torch.Tensor], dict[str, str]]:
    # clone: safetensors refuses tensors that share storage, and state_dicts can contain views
    tensors = {k: v.detach().clone().contiguous() for k, v in ck["model"].items()}
    if "arc" in ck:
        tensors.update({f"arc.{k}": v.detach().clone().contiguous() for k, v in ck["arc"].items()})
    meta = {"kind": "embedding" if "dim" in ck else "corners", "backbone": ck["backbone"], "epoch": str(ck["epoch"])}
    if "dim" in ck:
        meta["dim"] = str(ck["dim"])
    if "classes" in ck:
        meta["classes"] = json.dumps(ck["classes"])
    return tensors, meta


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run", required=True)
    args = ap.parse_args()
    ck = torch.load(CKPT_DIR / f"{args.run}.pt", map_location="cpu", weights_only=True)
    tensors, meta = to_safetensors(ck)
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    out = EXPORT_DIR / f"{args.run}.safetensors"
    save_file(tensors, str(out), metadata=meta)
    # round trip: every tensor must come back bit-identical
    back = load_file(str(out))
    assert back.keys() == tensors.keys() and all(torch.equal(back[k], tensors[k]) for k in tensors)
    print(json.dumps({"out": str(out), "tensors": len(tensors), "size_mb": round(out.stat().st_size / 1e6, 2),
                      **{k: v for k, v in meta.items() if k != "classes"}}, indent=1))


if __name__ == "__main__":
    main()
