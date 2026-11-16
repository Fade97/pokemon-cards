# SPDX-License-Identifier: GPL-3.0-or-later
"""Backbones, preprocessing and the fine-tuned embedding model."""
from __future__ import annotations

import cv2
import numpy as np
import timm
import torch
import torch.nn as nn
import torch.nn.functional as F

INPUT = 224  # square input; the 63:88 crop is squashed (cheap and identical on the phone)
MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)

# zero-shot backbones: name -> timm id
ZERO_SHOT = {
    "mobilenetv3_large": "mobilenetv3_large_100.ra_in1k",
    "efficientnet_lite0": "tf_efficientnet_lite0.in1k",
    "efficientnet_b0": "efficientnet_b0.ra_in1k",
    "dinov2_vits14": "vit_small_patch14_dinov2.lvd142m",
}


def preprocess(bgr: np.ndarray, size: int = INPUT) -> np.ndarray:
    """BGR uint8 card crop -> float32 CHW normalized."""
    if bgr.ndim == 3 and bgr.shape[2] == 4:  # BGRA reference: composite on white
        a = bgr[..., 3:4].astype(np.float32) / 255
        bgr = (bgr[..., :3] * a + 255 * (1 - a)).astype(np.uint8)
    rgb = cv2.cvtColor(cv2.resize(bgr, (size, size), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
    x = (rgb.astype(np.float32) / 255 - MEAN) / STD
    return x.transpose(2, 0, 1)


def load_backbone(name: str) -> nn.Module:
    tid = ZERO_SHOT[name]
    kw = {"img_size": INPUT} if "dinov2" in tid else {}
    return timm.create_model(tid, pretrained=True, num_classes=0, **kw)


class EmbeddingNet(nn.Module):
    """Backbone -> global pool -> linear -> BN -> L2 normalize."""

    def __init__(self, backbone: str = "mobilenetv3_large_100.ra_in1k", dim: int = 256, pretrained: bool = True):
        super().__init__()
        self.backbone = timm.create_model(backbone, pretrained=pretrained, num_classes=0)
        with torch.no_grad():  # num_features is pre-conv_head for MobileNetV3; measure the real pooled width
            self.backbone.eval()
            feat = self.backbone(torch.zeros(1, 3, INPUT, INPUT)).shape[1]
        self.head = nn.Sequential(nn.Linear(feat, dim, bias=False), nn.BatchNorm1d(dim))
        self.dim = dim
        self.backbone_name = backbone

    def forward(self, x):
        return F.normalize(self.head(self.backbone(x)), dim=-1)


@torch.no_grad()
def embed_arrays(model: nn.Module, arrays: list[np.ndarray] | np.ndarray, device, batch: int = 64,
                 normalize: bool = True) -> np.ndarray:
    model.eval()
    out = []
    for i in range(0, len(arrays), batch):
        x = torch.from_numpy(np.stack(arrays[i:i + batch])).to(device)
        with torch.autocast("cuda", dtype=torch.float16, enabled=device.type == "cuda"):
            f = model(x)
        f = f.float()
        if normalize:
            f = F.normalize(f, dim=-1)
        out.append(f.cpu().numpy())
    return np.concatenate(out)
