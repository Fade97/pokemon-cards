# SPDX-License-Identifier: GPL-3.0-or-later
"""Train the learned corner detector on GPU-synthetic scenes (corner_scenes.py).

Scenes are generated on the GPU into a cache (uint8, CPU RAM); a quarter of the cache is regenerated every epoch
and samples are flipped horizontally (with TL<->TR, BL<->BR swapped) for extra variety. Training scenes use the
870 trained cards; the fixed test scenes use all cards incl. the unseen sets.

A fixed synthetic corner test set (seed 4711) is evaluated for both the learned and the classic detector:
mean corner error (% of card height), PCK@2 % / 5 %, and quad IoU >= 0.9 / 0.8.

  uv run python train_corners.py --run corners_mnv3
"""
from __future__ import annotations

import argparse
import json
import math
import time

import cv2
import numpy as np
import torch

import gpu_aug
from common import CKPT_DIR, RESULTS_DIR, SYNTH_DIR, device, load_manifest
from corner_model import IN_H, IN_W, CornerNet, corner_loss
from corner_scenes import make_scenes
from detect import detect, quad_iou
from models import MEAN, STD

TEST_PATH = SYNTH_DIR / "corners_test.pt"


def normalize(x):
    m = torch.tensor(MEAN, device=x.device)[None, :, None, None]
    s = torch.tensor(STD, device=x.device)[None, :, None, None]
    return (x - m) / s


def gen_cache(refs, bg, n, dev, out_hw=(IN_H, IN_W), chunk=64, seed=None):
    xs, cs = [], []
    for i in range(0, n, chunk):
        x, c = make_scenes(refs, bg, min(chunk, n - i), dev, out_hw=out_hw,
                           gen=None if seed is None else seed + i)
        xs.append((x * 255).round().byte().cpu())
        cs.append(c.cpu())
    return torch.cat(xs), torch.cat(cs)


def build_test(dev, n=2000):
    """Same scenes at the model size (192x256) and at 288x384 for the classic detector."""
    if TEST_PATH.exists():
        return torch.load(TEST_PATH)
    cards = load_manifest(include_holdout=True)
    refs = gpu_aug.load_refs(cards, dev)
    bg = gpu_aug.make_bg_pool(64, dev, seed=999)
    x, c = gen_cache(refs, bg, n, dev, seed=4711)
    xb, cb = gen_cache(refs, bg, n, dev, out_hw=(384, 288), seed=4711)
    d = {"x": x, "c": c, "x_big": xb, "c_big": cb}
    TEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    torch.save(d, TEST_PATH)
    del refs
    torch.cuda.empty_cache()
    return d


def quad_metrics(pred, gt):
    """pred, gt: [N,4,2] numpy in the same pixel space."""
    h = (np.linalg.norm(gt[:, 3] - gt[:, 0], axis=-1) + np.linalg.norm(gt[:, 2] - gt[:, 1], axis=-1)) / 2
    err = np.linalg.norm(pred - gt, axis=-1) / h[:, None]  # [N,4] relative to card height
    iou = np.array([quad_iou(p, g) for p, g in zip(pred, gt)])
    return {"n": int(len(gt)), "corner_err_mean_pct_h": float(err.mean() * 100),
            "corner_err_median_pct_h": float(np.median(err) * 100),
            "pck_2pct": float((err < 0.02).mean()), "pck_5pct": float((err < 0.05).mean()),
            "iou>=0.9": float((iou >= 0.9).mean()), "iou>=0.8": float((iou >= 0.8).mean()),
            "iou_median": float(np.median(iou))}


@torch.no_grad()
def predict(net, x_uint8, dev, batch=128):
    net.eval()
    out, conf = [], []
    for i in range(0, len(x_uint8), batch):
        x = normalize(x_uint8[i:i + batch].to(dev).float() / 255)
        xy, c = net(x)
        out.append((xy * torch.tensor([IN_W, IN_H], device=dev)).cpu())
        conf.append(c.cpu())
    return torch.cat(out).numpy(), torch.cat(conf).numpy()


def eval_test(net, test, dev):
    pred, conf = predict(net, test["x"], dev)
    r = quad_metrics(pred, test["c"].numpy())
    r["conf_mean"] = float(conf.mean())
    return r


def eval_classic(test):
    xb, gt = test["x_big"], test["c_big"].numpy()
    preds, found = [], 0
    for i in range(len(xb)):
        img = cv2.cvtColor(xb[i].permute(1, 2, 0).numpy(), cv2.COLOR_RGB2BGR)
        q, _ = detect(img)
        if q is None:
            q = np.array([[0, 0], [287, 0], [287, 383], [0, 383]], np.float32)  # no detection: whole guide
        else:
            found += 1
        preds.append(q)
    r = quad_metrics(np.stack(preds), gt)
    r["found_rate"] = found / len(xb)
    return r


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run", default="corners_mnv3")
    ap.add_argument("--backbone", default="mobilenetv3_large_100.ra_in1k")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--cache", type=int, default=24000)
    ap.add_argument("--refresh", type=float, default=0.25)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=1e-3)
    args = ap.parse_args()
    dev = device()
    torch.backends.cudnn.benchmark = True
    t0 = time.time()
    test = build_test(dev)
    print(f"test set ready ({time.time() - t0:.0f}s)", flush=True)
    classic_path = RESULTS_DIR / "corners_classic_synth.json"
    if not classic_path.exists():
        rc = eval_classic(test)
        RESULTS_DIR.mkdir(exist_ok=True)
        classic_path.write_text(json.dumps(rc, indent=2))
    print("classic:", json.loads(classic_path.read_text()), flush=True)

    cards = load_manifest()
    refs = gpu_aug.load_refs(cards, dev)
    bg = gpu_aug.make_bg_pool(64, dev)
    t1 = time.time()
    cx, cc = gen_cache(refs, bg, args.cache, dev)
    gen_rate = args.cache / (time.time() - t1)
    print(f"cache {args.cache} scenes at {gen_rate:.0f}/s", flush=True)

    net = CornerNet(args.backbone).to(dev)
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-4)
    spe = args.cache // args.batch
    steps = args.epochs * spe
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / spe) * 0.5 * (1 + math.cos(math.pi * min(s, steps) / steps)))
    scaler = torch.amp.GradScaler()
    flip_order = torch.tensor([1, 0, 3, 2])
    log = {"args": vars(args), "scene_gen_per_s": gen_rate, "epochs": []}
    best = 1e9
    train_time = 0.0
    for ep in range(args.epochs):
        net.train()
        te = time.time()
        perm = torch.randperm(args.cache)
        tl = tl1 = 0.0
        for st in range(spe):
            sel = perm[st * args.batch:(st + 1) * args.batch]
            x = cx[sel].to(dev, non_blocking=True).float() / 255
            c = cc[sel].to(dev)
            fl = torch.rand(len(sel), device=dev) < 0.5
            x = torch.where(fl[:, None, None, None], x.flip(-1), x)
            cf = c[:, flip_order.to(dev)].clone()
            cf[..., 0] = IN_W - cf[..., 0]
            c = torch.where(fl[:, None, None], cf, c)
            with torch.autocast("cuda", dtype=torch.float16):
                lg = net.logits(normalize(x))
            loss, l1 = corner_loss(net, lg.float(), c)
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            tl += loss.item()
            tl1 += l1.item()
        torch.cuda.synchronize()
        train_time += time.time() - te
        # refresh part of the cache with new scenes
        k = int(args.cache * args.refresh)
        if k and ep < args.epochs - 1:
            ri = torch.randperm(args.cache)[:k]
            nx, nc = gen_cache(refs, bg, k, dev)
            cx[ri], cc[ri] = nx, nc
        entry = {"epoch": ep + 1, "loss": tl / spe, "l1_px": tl1 / spe, "minutes": (time.time() - t0) / 60}
        if (ep + 1) % 5 == 0 or ep == args.epochs - 1:
            entry["test"] = eval_test(net, test, dev)
            if entry["test"]["corner_err_mean_pct_h"] < best:
                best = entry["test"]["corner_err_mean_pct_h"]
                CKPT_DIR.mkdir(parents=True, exist_ok=True)
                torch.save({"model": net.state_dict(), "backbone": args.backbone, "epoch": ep + 1},
                           CKPT_DIR / f"{args.run}.pt")
        log["epochs"].append(entry)
        print(json.dumps(entry), flush=True)
    log["train_minutes_total"] = (time.time() - t0) / 60
    log["train_minutes_optim_only"] = train_time / 60
    log["samples_per_s_optim"] = args.epochs * spe * args.batch / train_time
    log["classic_synth"] = json.loads(classic_path.read_text())
    RESULTS_DIR.mkdir(exist_ok=True)
    (RESULTS_DIR / f"train_{args.run}.json").write_text(json.dumps(log, indent=2))


if __name__ == "__main__":
    main()
