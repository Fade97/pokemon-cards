# SPDX-License-Identifier: GPL-3.0-or-later
"""Fine-tune a phone-sized embedding model with ArcFace (one class per card).

Training samples are generated on the fly on the GPU (gpu_aug.py) from the reference images, which live on the
GPU as a uint8 tensor; the CPU does almost nothing. Model selection on the synthetic val set (rendered by
synth.py on the CPU, like the test set); the final numbers come from evaluate.py on the test set.

  uv run python train.py --run mnv3_256
  uv run python train.py --run mnv3_256_holdout --holdout en/me04   # generalization to unseen cards
  uv run python train.py --run mnv3_256_forb --realistic --init mnv3_256_real --real   # + real photos (realset.py)

With --real the class set grows to every EN card with a real train-split photo (realset.py build), the real crops
are mixed into every batch (--real-frac) with light augmentation, and the checkpoint is selected on the real
validation cards (never trained) against the full EN gallery instead of on the synthetic val set.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import threading
import time

import cv2
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

import gpu_aug
from common import CKPT_DIR, DATA_DIR, RESULTS_DIR, SYNTH_DIR, device, load_manifest
from metrics import evaluate, knn
from models import MEAN, STD, EmbeddingNet, embed_arrays, preprocess
from synth import load_ref_rgba


class ArcFace(nn.Module):
    def __init__(self, dim, n_classes, s=30.0, m=0.30):
        super().__init__()
        self.W = nn.Parameter(torch.randn(n_classes, dim) * 0.01)
        self.s, self.m = s, m

    def forward(self, emb, y):
        cos = F.linear(emb, F.normalize(self.W, dim=-1)).clamp(-1 + 1e-6, 1 - 1e-6)
        target = torch.cos(torch.acos(cos) + self.m)
        onehot = F.one_hot(y, cos.shape[1]).bool()
        logits = torch.where(onehot, target, cos) * self.s
        return F.cross_entropy(logits, y, label_smoothing=0.05)


class GpuMonitor(threading.Thread):
    """Samples GPU utilisation and memory with nvidia-smi every few seconds."""

    def __init__(self, every=5.0):
        super().__init__(daemon=True)
        self.every, self.samples, self.stop = every, [], threading.Event()

    def run(self):
        while not self.stop.is_set():
            try:
                out = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu,memory.used",
                                      "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=10)
                u, m = (float(x) for x in out.stdout.strip().split(","))
                self.samples.append((u, m))
            except Exception:
                pass
            self.stop.wait(self.every)

    def summary(self):
        if not self.samples:
            return {}
        u = np.array([s[0] for s in self.samples])
        m = np.array([s[1] for s in self.samples])
        return {"gpu_util_mean_pct": float(u.mean()), "gpu_util_median_pct": float(np.median(u)),
                "gpu_mem_used_max_mib": float(m.max()), "samples": len(u)}


def load_eval_set(name: str, cards):
    key2i = {c.key: i for i, c in enumerate(cards)}
    labels = json.loads((SYNTH_DIR / name / "labels.json").read_text())
    crops = [preprocess(cv2.imread(str(SYNTH_DIR / f"{name}-crops" / l["file"]))) for l in labels]
    return crops, np.array([key2i[l["key"]] for l in labels])


def ref_inputs(cards):
    return [preprocess(load_ref_rgba(str(c.ref_path))) for c in cards]


def run_eval(model, dev, cards, crops, truth, ref_x, mask=None):
    R = embed_arrays(model, ref_x, dev)
    Q = embed_arrays(model, crops, dev)
    if mask is not None:
        Q, truth = Q[mask], truth[mask]
    topk, _ = knn(Q, R)
    return evaluate(topk, truth, cards)


@torch.no_grad()
def embed_u8(model, x_u8, dev, batch: int = 256, nhwc: bool = False) -> torch.Tensor:
    """uint8 RGB images (CPU tensor or numpy; NCHW, or NHWC with nhwc=True) -> L2-normalized fp16 embeddings."""
    model.eval()
    mean = torch.tensor(MEAN, device=dev)[None, :, None, None]
    std = torch.tensor(STD, device=dev)[None, :, None, None]
    out = []
    for i in range(0, len(x_u8), batch):
        x = torch.as_tensor(np.ascontiguousarray(x_u8[i:i + batch])).to(dev)
        x = x.permute(0, 3, 1, 2) if nhwc else x
        with torch.autocast("cuda", dtype=torch.float16):
            out.append(F.normalize(model((x.float() / 255 - mean) / std).float(), dim=-1).half())
    return torch.cat(out)


def real_val_eval(model, dev, gallery_u8, gallery_ids, val_u8, val_ids) -> dict:
    """Real val crops (cards never trained) vs. the full EN gallery, best of both 180-degree orientations, like
    eval_forb.py. Top-1/top-5 exact print."""
    G = embed_u8(model, gallery_u8, dev, nhwc=True)
    q0 = embed_u8(model, val_u8, dev)
    q1 = embed_u8(model, val_u8.flip(-1).flip(-2), dev)
    s0, s1 = q0 @ G.T, q1 @ G.T
    sims = torch.where((s1.amax(1) > s0.amax(1))[:, None], s1, s0)
    top = sims.topk(5, dim=1).indices.cpu().numpy()
    pos = {c: i for i, c in enumerate(gallery_ids)}
    truth = np.array([pos[c] for c in val_ids])
    return {"n": len(truth), "top1": float((top[:, 0] == truth).mean()),
            "top5": float((top == truth[:, None]).any(1).mean())}


def free_vram_mib() -> float:
    out = subprocess.run(["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
                         capture_output=True, text=True)
    return float(out.stdout.strip().splitlines()[0])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run", required=True)
    ap.add_argument("--backbone", default="mobilenetv3_large_100.ra_in1k")
    ap.add_argument("--dim", type=int, default=256)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--per-class", type=int, default=32, help="samples per class per epoch")
    ap.add_argument("--batch", type=int, default=0, help="0 = pick from free VRAM")
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--holdout", nargs="*", default=[], help="card groups to exclude from training, e.g. en/me04")
    ap.add_argument("--realistic", action="store_true", help="add the real-photo effects of real_aug.py")
    ap.add_argument("--init", default=None, help="start from this run's checkpoint (fine-tune)")
    ap.add_argument("--real", action="store_true", help="grow the classes and mix in the real crops of realset.py")
    ap.add_argument("--real-frac", type=float, default=0.25, help="share of real crops per batch with --real")
    args = ap.parse_args()
    torch.backends.cudnn.benchmark = os.environ.get("CARDINDEX_CUDNN_BENCH", "1") == "1"
    dev = device()
    cards = load_manifest()

    free = free_vram_mib()
    batch = args.batch or (128 if free > 6500 else 96 if free > 4500 else 48)
    chunk = 64 if batch > 64 else batch
    print(f"free VRAM {free:.0f} MiB -> batch {batch}, aug chunk {chunk}")

    n_base = len(cards)  # the manifest: synthetic val/test labels index into these
    class_idx = [i for i, c in enumerate(cards) if c.group not in args.holdout]
    real = None
    if args.real:
        import realset
        samples = json.loads(realset.SAMPLES.read_text())
        pc = realset.pack_cards()
        extra = [pc[cid] for cid in sorted({s["card_id"] for s in samples if s["role"] == "train"})]
        cards, cls = realset.plan_classes(cards, extra)
        class_idx = [i for i in cls if cards[i].group not in args.holdout]
        key2i = {c.key: i for i, c in enumerate(cards)}
        tr = realset.real_samples(samples, "train", {cards[i].card_id for i in class_idx})
        va = realset.real_samples(samples, "val")
        crops = realset.REAL_TRAIN / "crops"
        real = {
            "x": gpu_aug.load_real_crops([crops / s["file"] for s in tr], [s["rot"] for s in tr]).pin_memory(),
            "card": torch.tensor([key2i[f"en/{s['card_id']}"] for s in tr], device=dev),
            "w": torch.tensor(realset.balanced_weights([s["card_id"] for s in tr]), device=dev),
            "val_x": gpu_aug.load_real_crops([crops / s["file"] for s in va], [0] * len(va)),
            "val_ids": [s["card_id"] for s in va],
            "gallery": np.load(realset.GALLERY_NPY, mmap_mode="r"),
            "gallery_ids": json.loads(realset.GALLERY_IDS.read_text()),
        }
        print(f"--real: {len(cards)} gallery cards, {len(class_idx)} classes "
              f"({sum(i < n_base for i in class_idx)} of the {n_base} manifest cards), {len(tr)} real train crops of "
              f"{len(set(real['card'].tolist()))} cards, {len(va)} real val crops of {len(set(real['val_ids']))} cards")
    train_classes = torch.tensor(class_idx, device=dev)
    cls_map = torch.full((len(cards),), -1, dtype=torch.long, device=dev)
    cls_map[train_classes] = torch.arange(len(train_classes), device=dev)
    refs = gpu_aug.load_refs(cards, dev)
    bg_pool = gpu_aug.make_bg_pool(64, dev)
    gpu_aug.save_grid(DATA_DIR / f"aug_grid_{args.run}.png", refs, bg_pool, realistic=args.realistic)

    # no channels_last: with BatchNorm in train mode it is ~10x slower on this torch 2.14/cu130 build
    model = EmbeddingNet(args.backbone, args.dim).to(dev)
    arc = ArcFace(args.dim, len(train_classes)).to(dev)
    class_keys = [cards[int(i)].key for i in train_classes]
    if args.init:
        ck0 = torch.load(CKPT_DIR / f"{args.init}.pt", map_location="cpu", weights_only=True)
        assert ck0["dim"] == args.dim and (args.real or ck0["classes"] == class_keys)
        model.load_state_dict(ck0["model"])
        if ck0["classes"] == class_keys:
            arc.load_state_dict(ck0["arc"])
        else:  # grown/shrunk class set: keep the trained rows, start new classes at their reference embedding
            W0, old = ck0["arc"]["W"], {k: j for j, k in enumerate(ck0["classes"])}
            refs_new = [i for i, k in zip(class_idx, class_keys) if k not in old]
            E = torch.from_numpy(embed_arrays(model, ref_inputs([cards[i] for i in refs_new]), dev))
            new_rows = iter(E * W0.norm(dim=1).mean())
            W = torch.stack([W0[old[k]] if k in old else next(new_rows) for k in class_keys])
            arc.W.data.copy_(W.to(dev))
            print(f"ArcFace: {len(class_keys) - len(refs_new)} classes kept, {len(refs_new)} new from references")
        print(f"initialised from {args.init}")
    opt = torch.optim.AdamW([
        {"params": model.backbone.parameters(), "lr": args.lr * 0.5},
        {"params": list(model.head.parameters()) + list(arc.parameters()), "lr": args.lr * 5},
    ], weight_decay=1e-4)
    n_real = round(batch * args.real_frac) if real else 0
    n_syn = batch - n_real
    steps_per_epoch = len(train_classes) * args.per_class // n_syn
    steps = args.epochs * steps_per_epoch
    warm = steps_per_epoch
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(s, steps) / steps)))
    scaler = torch.amp.GradScaler()

    val_crops, val_truth = load_eval_set("val", cards[:n_base])
    ref_x = ref_inputs(cards[:n_base])
    hold_mask = np.array([cards[t].group in args.holdout for t in val_truth]) if args.holdout else None
    log = {"args": vars(args), "batch": batch, "batch_real": n_real, "classes": len(class_keys), "free_vram_mib_at_start": free, "epochs": []}
    mon = GpuMonitor(every=float(os.environ.get("CARDINDEX_GPU_MON_EVERY", "30")))
    if os.environ.get("CARDINDEX_GPU_MON", "1") == "1":
        mon.start()
    best, t0, aug_time, n_samples, train_time = -1.0, time.time(), 0.0, 0, 0.0
    for ep in range(args.epochs):
        model.train()
        arc.train()
        # balanced: every class appears per_class times per epoch
        order = train_classes[torch.randperm(len(train_classes), device=dev)].repeat(args.per_class)
        order = order[torch.randperm(len(order), device=dev)]
        tl = 0.0
        te = time.time()
        for st in range(steps_per_epoch):
            idx = order[st * n_syn:(st + 1) * n_syn]
            ta = time.time()
            x = gpu_aug.augment(refs, idx, bg_pool, chunk=chunk, realistic=args.realistic)
            if real:
                ri = torch.multinomial(real["w"], n_real, replacement=True)
                xr = real["x"][ri.cpu()].to(dev, non_blocking=True)
                x = torch.cat([x, gpu_aug.augment_real(xr)])
                idx = torch.cat([idx, real["card"][ri]])
            if os.environ.get("CARDINDEX_STEP_TIMING") and st % 10 == 0:
                torch.cuda.synchronize()
                print(f"step {st} aug {time.time() - ta:.3f}s", flush=True)
            if st % 50 == 0:  # measure the augmentation share now and then (sync costs a little)
                torch.cuda.synchronize()
                aug_time += (time.time() - ta) * 50
            y = cls_map[idx]
            with torch.autocast("cuda", dtype=torch.float16):
                emb = model(x)
            loss = arc(emb.float(), y)
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            if os.environ.get("CARDINDEX_STEP_TIMING") and st % 10 == 0:
                torch.cuda.synchronize()
                print(f"step {st} total {time.time() - ta:.3f}s", flush=True)
            tl += loss.item()
            n_samples += len(idx)
        torch.cuda.synchronize()
        train_time += time.time() - te
        ev = run_eval(model, dev, cards[:n_base], val_crops, val_truth, ref_x)
        entry = {"epoch": ep + 1, "loss": tl / steps_per_epoch, "val_top1": ev["top1"], "val_top5": ev["top5"],
                 "minutes": (time.time() - t0) / 60, "samples_per_s": n_samples / train_time}
        score = ev["top1"]
        if real:
            rv = real_val_eval(model, dev, real["gallery"], real["gallery_ids"], real["val_x"], real["val_ids"])
            entry["val_real_top1"], entry["val_real_top5"] = rv["top1"], rv["top5"]
            score = rv["top1"]
        if hold_mask is not None:
            entry["val_holdout_top1"] = run_eval(model, dev, cards, val_crops, val_truth, ref_x, hold_mask)["top1"]
            entry["val_seen_top1"] = run_eval(model, dev, cards, val_crops, val_truth, ref_x, ~hold_mask)["top1"]
        log["epochs"].append(entry)
        print(json.dumps(entry), flush=True)
        if score >= best:
            best = score
            CKPT_DIR.mkdir(parents=True, exist_ok=True)
            torch.save({"model": model.state_dict(), "arc": arc.state_dict(), "backbone": args.backbone,
                        "dim": args.dim, "classes": class_keys, "epoch": ep + 1},
                       CKPT_DIR / f"{args.run}.pt")
    mon.stop.set()
    total = time.time() - t0
    log["train_minutes"] = total / 60
    log["samples_total"] = n_samples
    log["throughput_samples_per_s"] = n_samples / train_time  # augment + forward + backward, excl. val
    log["aug_share_of_train_time_est"] = min(1.0, aug_time / train_time)
    log["gpu"] = mon.summary()
    log["best_val_real_top1" if real else "best_val_top1"] = best
    print(json.dumps({k: v for k, v in log.items() if k != "epochs"}, indent=2))
    RESULTS_DIR.mkdir(exist_ok=True)
    (RESULTS_DIR / f"train_{args.run}.json").write_text(json.dumps(log, indent=2))


if __name__ == "__main__":
    main()
