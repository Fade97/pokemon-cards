# SPDX-License-Identifier: GPL-3.0-or-later
"""Learned card-corner detector: MobileNetV3 features -> FPN-lite -> 4 corner heatmaps -> soft-argmax.

Input  (phone model, see PhoneCornerNet): NHWC float32 RGB in [0, 1], 1 x 256 x 192 x 3 = the app's guide area
       resized (stretched, no letterbox) to 192 wide x 256 high.
Output: 1 x 4 x 3 = for TL, TR, BR, BL (image orientation, portrait): x, y normalized to [0, 1] of the input,
        and the peak probability of that corner's heatmap (confidence, 0..1).
Only standard ops (conv, hard-swish, nearest upsample, softmax, matmul), no custom ops.
"""
from __future__ import annotations

import timm
import torch
import torch.nn as nn
import torch.nn.functional as F

from models import MEAN, STD

IN_W, IN_H = 192, 256
STRIDE = 4
HM_W, HM_H = IN_W // STRIDE, IN_H // STRIDE


def _cbr(i, o, k=3):
    return nn.Sequential(nn.Conv2d(i, o, k, padding=k // 2, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True))


class CornerNet(nn.Module):
    def __init__(self, backbone: str = "mobilenetv3_large_100.ra_in1k", ch: int = 64, pretrained: bool = True):
        super().__init__()
        self.backbone_name = backbone
        self.body = timm.create_model(backbone, features_only=True, pretrained=pretrained, out_indices=(1, 2, 3, 4))
        chs = self.body.feature_info.channels()
        self.lat = nn.ModuleList([nn.Conv2d(c, ch, 1) for c in chs])
        self.head = nn.Sequential(_cbr(ch, ch), _cbr(ch, ch), nn.Conv2d(ch, 4, 1))
        xs = (torch.arange(HM_W) + 0.5) / HM_W
        ys = (torch.arange(HM_H) + 0.5) / HM_H
        gy, gx = torch.meshgrid(ys, xs, indexing="ij")
        self.register_buffer("grid", torch.stack([gx.flatten(), gy.flatten()], 1))  # [HW, 2]

    def logits(self, x):
        """x: NCHW, ImageNet-normalized -> [B, 4, HM_H, HM_W]."""
        f = self.body(x)
        y = self.lat[3](f[3])
        for i in (2, 1, 0):
            y = F.interpolate(y, scale_factor=2, mode="nearest") + self.lat[i](f[i])
        return self.head(y)

    def decode(self, logits):
        """Soft-argmax: [B,4,H,W] -> coords [B,4,2] in [0,1] and peak probability [B,4]."""
        b = logits.shape[0]
        p = torch.softmax(logits.reshape(b, 4, -1), -1)
        return p @ self.grid, p.max(-1).values, p

    def forward(self, x):
        xy, conf, _ = self.decode(self.logits(x))
        return xy, conf


class PhoneCornerNet(nn.Module):
    """Export wrapper: NHWC RGB [0,1] in, [1,4,3] (x, y, confidence) out."""

    def __init__(self, net: CornerNet):
        super().__init__()
        self.net = net
        self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

    def forward(self, x):
        x = (x.permute(0, 3, 1, 2) - self.mean) / self.std
        xy, conf = self.net(x)
        return torch.cat([xy, conf[..., None]], -1)


def order_corners(q):
    """Geometric order TL, TR, BR, BL (portrait, long side vertical), like detect.order_quad, batched."""
    c = q.mean(1, keepdim=True)
    ang = torch.atan2(q[..., 1] - c[..., 1], q[..., 0] - c[..., 0])
    q = torch.gather(q, 1, ang.argsort(1)[..., None].expand(-1, -1, 2))
    start = q.sum(-1).argmin(1)
    idx = (start[:, None] + torch.arange(4, device=q.device)[None]) % 4
    q = torch.gather(q, 1, idx[..., None].expand(-1, -1, 2))
    w = ((q[:, 1] - q[:, 0]).norm(dim=-1) + (q[:, 2] - q[:, 3]).norm(dim=-1)) / 2
    h = ((q[:, 3] - q[:, 0]).norm(dim=-1) + (q[:, 2] - q[:, 1]).norm(dim=-1)) / 2
    land = w > h
    return torch.where(land[:, None, None], torch.roll(q, -1, 1), q)


def corner_loss(net: CornerNet, logits, target_px, sigma: float = 1.0):
    """L1 on soft-argmax coordinates (input pixels) + cross-entropy against a Gaussian target heatmap."""
    xy, _, p = net.decode(logits)
    scale = torch.tensor([IN_W, IN_H], device=logits.device)
    l1 = (xy * scale - target_px).abs().mean()
    t = target_px / STRIDE  # heatmap pixels
    gxy = net.grid * torch.tensor([HM_W, HM_H], device=logits.device)  # [HW,2] centres in heatmap px
    d2 = ((gxy[None, None] - t[:, :, None]) ** 2).sum(-1)
    tgt = torch.softmax(-d2 / (2 * sigma ** 2), -1)
    ce = -(tgt * torch.log(p.clamp_min(1e-9))).sum(-1).mean()
    return l1 + ce, l1
