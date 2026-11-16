# SPDX-License-Identifier: GPL-3.0-or-later
"""Realistic phone-photo effects on the GPU, learned from the first real photos (2026-11-16).

The real photos differ from the first synthetic renders mainly by: strong warm colour casts (tungsten light,
partial auto white balance), low light with noisy, denoised and sharpened images, holo / reverse-holo sheen,
broad glare and reflections on sleeves and binder pockets, and imprecise crops with dark pocket margins.
All functions work on float tensors in [0, 1], batched, on CUDA.
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F
from kornia.filters import gaussian_blur2d


def _rand(b, lo, hi, dev):
    return torch.rand(b, device=dev) * (hi - lo) + lo


def _bc(v):
    return v[:, None, None, None]


def kelvin_rgb(t: torch.Tensor) -> torch.Tensor:
    """Approximate black-body colour (Tanner Helland) for temperatures in Kelvin, [B] -> [B,3] in [0,1]."""
    t = t / 100
    r = torch.where(t <= 66, torch.ones_like(t), 1.292936186 * (t - 60).clamp(min=1e-3) ** -0.1332047592)
    g = torch.where(t <= 66, 0.39008157876 * torch.log(t) - 0.63184144378,
                    1.129890861 * (t - 60).clamp(min=1e-3) ** -0.0755148492)
    b = torch.where(t >= 66, torch.ones_like(t),
                    torch.where(t <= 19, torch.zeros_like(t), 0.54320678911 * torch.log((t - 10).clamp(min=1e-3)) - 1.19625408914))
    return torch.stack([r, g, b], 1).clamp(0.05, 1)


def white_balance(img, p=0.85):
    """Colour cast of a light between 2700 K (tungsten) and 7500 K, only partly corrected by the camera."""
    b, dev = img.shape[0], img.device
    # warm light is the common indoor case: half of the samples 2700-4000 K
    t = torch.where(torch.rand(b, device=dev) < 0.5, _rand(b, 2700, 4000, dev), _rand(b, 4000, 7500, dev))
    gain = kelvin_rgb(t) / kelvin_rgb(torch.full_like(t, 6500))
    gain = gain / gain[:, 1:2]
    keep = _rand(b, 0.25, 1.0, dev)[:, None]  # 1 = camera did not correct at all
    gain = gain ** keep
    on = torch.rand(b, device=dev) < p
    gain = torch.where(on[:, None], gain, torch.ones_like(gain))
    return (img * gain[..., None, None]).clamp(0, 1)


def exposure_and_noise(img, p_dark=0.4):
    """Exposure / gamma changes; dark frames get shot noise and coarse chroma noise like a phone at high ISO."""
    b, dev = img.shape[0], img.device
    dark = torch.rand(b, device=dev) < p_dark
    gain = torch.where(dark, torch.exp(_rand(b, math.log(0.35), math.log(0.75), dev)),
                       torch.exp(_rand(b, math.log(0.7), math.log(1.45), dev)))
    img = (img * _bc(gain)).clamp(0, 1) ** _bc(_rand(b, 0.8, 1.25, dev))
    lum = img.mean(1, keepdim=True)
    shot = torch.randn_like(img) * (lum + 0.02).sqrt() * _bc(torch.where(dark, _rand(b, 0.03, 0.09, dev),
                                                                        _rand(b, 0.0, 0.03, dev)))
    h, w = img.shape[-2:]
    chroma = F.interpolate(torch.randn(b, 3, max(2, h // 8), max(2, w // 8), device=dev), size=(h, w),
                           mode="bilinear", align_corners=False) * _bc(torch.where(dark, _rand(b, 0.01, 0.05, dev),
                                                                                  torch.zeros(b, device=dev)))
    return (img + shot + chroma).clamp(0, 1)


def phone_isp(img, p=0.6):
    """Noise reduction (smearing) followed by unsharp masking (halos), as phone pipelines do."""
    b, dev = img.shape[0], img.device
    on = torch.rand(b, device=dev) < p
    if not on.any():
        return img
    x = img[on]
    n = x.shape[0]
    den = gaussian_blur2d(x, (7, 7), _rand(n, 0.5, 1.4, dev)[:, None].expand(n, 2))
    x = x + (den - x) * _bc(_rand(n, 0.4, 1.0, dev))
    soft = gaussian_blur2d(x, (9, 9), _rand(n, 1.0, 2.5, dev)[:, None].expand(n, 2))
    x = x + (x - soft) * _bc(_rand(n, 0.4, 1.6, dev))
    out = img.clone()
    out[on] = x.clamp(0, 1)
    return out


def _rainbow(b, h, w, dev):
    yy, xx = torch.meshgrid(torch.linspace(0, 1, h, device=dev), torch.linspace(0, 1, w, device=dev), indexing="ij")
    th = _rand(b, 0, math.pi, dev)[:, None, None]
    per = _rand(b, 0.25, 1.2, dev)[:, None, None]
    warp = F.interpolate(torch.randn(b, 1, 6, 5, device=dev), size=(h, w), mode="bicubic", align_corners=False)[:, 0]
    phase = (xx * th.cos() + yy * th.sin()) / per + 0.25 * warp + _rand(b, 0, 1, dev)[:, None, None]
    off = torch.tensor([0, 1 / 3, 2 / 3], device=dev)[None, :, None, None]
    return 0.5 + 0.5 * torch.cos(2 * math.pi * (phase[:, None] + off))  # [B,3,H,W]


def holo_sheen(card: torch.Tensor, p=0.55):
    """Holo rainbow over the art box, or reverse-holo sparkle over the rest of the card. card: [B,4,H,W] RGBA."""
    b, _, h, w = card.shape
    dev = card.device
    on = torch.rand(b, device=dev) < p
    if not on.any():
        return card
    yy, xx = torch.meshgrid(torch.linspace(0, 1, h, device=dev), torch.linspace(0, 1, w, device=dev), indexing="ij")
    art = ((yy > 0.11) & (yy < 0.53) & (xx > 0.07) & (xx < 0.93)).float()[None, None]
    reverse = (torch.rand(b, device=dev) < 0.4)[:, None, None, None]
    region = torch.where(reverse, 1 - art, art.expand(b, 1, h, w))
    rb = _rainbow(b, h, w, dev)
    sparkle = (torch.rand(b, 1, h // 2, w // 2, device=dev) > 0.93).float()
    sparkle = F.interpolate(gaussian_blur2d(sparkle, (3, 3), (0.8, 0.8)), size=(h, w), mode="bilinear",
                            align_corners=False)
    tex = torch.where(reverse, rb * (0.4 + 2.0 * sparkle), rb)
    a = _bc(_rand(b, 0.08, 0.35, dev)) * region * _bc(on.float())
    rgb = card[:, :3] + a * (tex - 0.35)
    return torch.cat([rgb.clamp(0, 1), card[:, 3:4]], 1)


def broad_glare(img, center, p=0.45):
    """Wide, strong glare band and soft room reflection, as on sleeves and binder pockets."""
    b, _, h, w = img.shape
    dev = img.device
    on = (torch.rand(b, device=dev) < p).float()
    yy, xx = torch.meshgrid(torch.arange(h, device=dev, dtype=torch.float32),
                            torch.arange(w, device=dev, dtype=torch.float32), indexing="ij")
    th = _rand(b, 0, math.pi, dev)[:, None, None]
    d = (xx - center[:, 0, None, None]) * th.sin() - (yy - center[:, 1, None, None]) * th.cos()
    d = d + _rand(b, -0.4, 0.4, dev)[:, None, None] * h
    band = torch.exp(-0.5 * (d / (_rand(b, 0.04, 0.2, dev)[:, None, None] * h)) ** 2)
    band = band * _rand(b, 0.15, 0.55, dev)[:, None, None] * on[:, None, None]
    refl = F.interpolate(torch.rand(b, 3, 4, 3, device=dev), size=(h, w), mode="bicubic", align_corners=False)
    refl = refl.clamp(0, 1) * _bc(_rand(b, 0.0, 0.12, dev) * on)
    return (img + band[:, None] + refl).clamp(0, 1)
