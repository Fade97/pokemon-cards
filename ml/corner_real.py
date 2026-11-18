# SPDX-License-Identifier: GPL-3.0-or-later
"""Real-photo samples for the corner fine-tune (issue #59): loads corner_realset.py's context windows and cuts
random guide boxes from them on the GPU (train_corners.py --real). Torch only, no kornia, so the CPU tests run it.
"""
from __future__ import annotations

import numpy as np
import torch

from corner_model import IN_H, IN_W, order_corners


class RealCorners:
    """Real context windows (uint8 RGB [N, 384, 288, 3] in CPU RAM) and their card quads; samples random guide
    boxes on the GPU. TCG-AR samples (true corners) are drawn with probability `tcgar_share`, FORB pseudo-labels
    otherwise."""

    def __init__(self, path, dev, tcgar_share: float):
        d = np.load(path)
        self.win = torch.from_numpy(d["win"])
        self.quad = torch.from_numpy(d["quad"]).to(dev)
        self.valid = torch.from_numpy(d["valid"]).to(dev)
        kind = d["kind"]
        self.idx_t = torch.from_numpy(np.flatnonzero(kind == "tcgar"))
        self.idx_f = torch.from_numpy(np.flatnonzero(kind == "forb"))
        if not len(self.idx_t):
            raise SystemExit(f"{path}: no TCG-AR samples")
        self.tcgar_share = tcgar_share if len(self.idx_f) else 1.0
        self.val_x = torch.from_numpy(d["val_x"]).permute(0, 3, 1, 2).contiguous()  # [N,3,256,192] uint8
        self.val_q = d["val_q"]
        self.dev = dev
        self.counts = {"tcgar": int(len(self.idx_t)), "forb": int(len(self.idx_f)), "val": int(len(self.val_q))}

    def sample(self, n: int):
        """n random guide boxes -> (images [n,3,IN_H,IN_W] in [0,1], corners [n,4,2] in input pixels)."""
        dev = self.dev
        use_t = torch.rand(n) < self.tcgar_share
        pick_t = self.idx_t[torch.randint(len(self.idx_t), (n,))]
        pick_f = self.idx_f[torch.randint(len(self.idx_f), (n,))] if len(self.idx_f) else pick_t
        sel = torch.where(use_t, pick_t, pick_f)
        x = self.win[sel].to(dev, non_blocking=True).permute(0, 3, 1, 2).float() / 255  # [n,3,H,W]
        H, W = x.shape[-2:]
        q, v = self.quad[sel.to(dev)], self.valid[sel.to(dev)]
        u = lambda lo, hi: lo + (hi - lo) * torch.rand(n, device=dev)
        # box: card at ~50-97 % of the box height, guide-like aspect, card centre off by up to 15 % / 12 %
        bh = u(0.5, 1.0) * H
        bw = torch.minimum(bh * u(0.68, 0.80), torch.full_like(bh, W))
        c = q.mean(1) + torch.stack([u(-0.15, 0.15) * bw, u(-0.12, 0.12) * bh], 1)
        l, t = c[:, 0] - bw / 2, c[:, 1] - bh / 2
        r, b = l + bw, t + bh
        # keep the whole card (plus 2 %) inside the box, then clip the box to the photo (the app clips its guide
        # area to the frame and stretches it to the model input)
        lo, hi = q.min(1).values, q.max(1).values
        m = 0.02 * bh
        l, t = torch.minimum(l, lo[:, 0] - m), torch.minimum(t, lo[:, 1] - m)
        r, b = torch.maximum(r, hi[:, 0] + m), torch.maximum(b, hi[:, 1] + m)
        l, t = torch.maximum(l, v[:, 0]), torch.maximum(t, v[:, 1])
        r, b = torch.minimum(r, v[:, 2]), torch.minimum(b, v[:, 3])
        r, b = torch.maximum(r, l + 8), torch.maximum(b, t + 8)
        theta = torch.zeros(n, 2, 3, device=dev)
        theta[:, 0, 0], theta[:, 0, 2] = (r - l) / W, (l + r) / W - 1
        theta[:, 1, 1], theta[:, 1, 2] = (b - t) / H, (t + b) / H - 1
        grid = torch.nn.functional.affine_grid(theta, (n, 3, IN_H, IN_W), align_corners=False)
        out = torch.nn.functional.grid_sample(x, grid, mode="bilinear", padding_mode="border", align_corners=False)
        cq = torch.stack([(q[..., 0] - l[:, None]) / (r - l)[:, None] * IN_W,
                          (q[..., 1] - t[:, None]) / (b - t)[:, None] * IN_H], -1)
        # light photometric jitter; the photos already carry real light, glare and sleeves
        out = ((out - 0.5) * u(0.85, 1.15)[:, None, None, None] + 0.5 + u(-0.08, 0.08)[:, None, None, None])
        return out.clamp(0, 1), order_corners(cq)


def real_val_metrics(pred, gt) -> dict:
    """Corner error in % of the card diagonal (as eval_tcgar.corner_error, min over the 4 corner rotations)."""
    diag = np.linalg.norm(gt[:, 0] - gt[:, 2], axis=-1)
    errs = np.stack([np.linalg.norm(np.roll(pred, k, 1) - gt, axis=-1).mean(-1) for k in range(4)]).min(0) / diag * 100
    return {"n": int(len(gt)), "err_mean_pct_diag": float(errs.mean()), "err_median_pct_diag": float(np.median(errs)),
            "within_3pct": float((errs <= 3).mean()), "over_10pct": float((errs > 10).mean())}
