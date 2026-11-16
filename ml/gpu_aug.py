# SPDX-License-Identifier: GPL-3.0-or-later
"""Batched on-the-fly training augmentation on the GPU (Kornia + plain torch).

Simulates "phone photo -> detector -> rectified crop" per batch, entirely on CUDA tensors:
reference card (uint8 on GPU) -> random perspective into a small frame over a background texture (pool on GPU)
-> shadow, sleeve, glare/specular streak, thumb -> colour temperature, blur, noise, JPEG
-> re-rectify with a jittered quad (residual detection error) -> 224 x 224 normalized model input.

This is a second, independent renderer: the held-out test set is rendered by synth.py with OpenCV on the CPU.
"""
from __future__ import annotations

import math

import cv2
import numpy as np
import torch
import torch.nn.functional as F
from kornia.enhance import jpeg_codec_differentiable
from kornia.filters import gaussian_blur2d, motion_blur
from kornia.geometry.transform import get_perspective_transform, warp_perspective

from models import INPUT, MEAN, STD
from real_aug import broad_glare, exposure_and_noise, holo_sheen, phone_isp, white_balance
from synth import background, load_ref_rgba

REF_W, REF_H = 256, 352          # reference cards on the GPU (uint8 RGBA)
FRAME_W, FRAME_H = 448, 576      # simulated photo frame (multiples of 16 for the JPEG codec)


def load_refs(cards, dev) -> torch.Tensor:
    """[N, 4, REF_H, REF_W] uint8 RGBA on the GPU (~310 MB for 870 cards)."""
    arr = np.empty((len(cards), 4, REF_H, REF_W), np.uint8)
    for i, c in enumerate(cards):
        bgra = cv2.resize(load_ref_rgba(str(c.ref_path)), (REF_W, REF_H), interpolation=cv2.INTER_AREA)
        arr[i] = cv2.cvtColor(bgra, cv2.COLOR_BGRA2RGBA).transpose(2, 0, 1)
    return torch.from_numpy(arr).to(dev)


def make_bg_pool(n: int, dev, size: int = 640, seed: int = 4242) -> torch.Tensor:
    """[n, 3, size, size] uint8 RGB textures (wood, cloth, skin, paper, ...) from synth.background(), on the GPU."""
    rng = np.random.default_rng(seed)
    pool = np.stack([cv2.cvtColor(background(rng, size, size), cv2.COLOR_BGR2RGB).transpose(2, 0, 1)
                     for _ in range(n)])
    return torch.from_numpy(pool).to(dev)


def _corners(w, h, dev, b):
    return torch.tensor([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], dtype=torch.float32, device=dev
                        ).expand(b, 4, 2)


def _rand(b, lo, hi, dev):
    return torch.rand(b, device=dev) * (hi - lo) + lo


def random_quads(b, dev):
    """Card corners (TL, TR, BR, BL) in frame pixels: scale, rotation, perspective tilt, position."""
    ch = _rand(b, 0.55, 0.92, dev) * FRAME_H
    cw = torch.minimum(ch * 63 / 88, torch.full_like(ch, FRAME_W * 0.95))
    ch = cw * 88 / 63
    base = torch.stack([torch.stack([-cw, -ch], -1), torch.stack([cw, -ch], -1),
                        torch.stack([cw, ch], -1), torch.stack([-cw, ch], -1)], 1) / 2
    base = base + torch.randn(b, 4, 2, device=dev) * (0.07 * ch / 2)[:, None, None]
    a = (torch.randn(b, device=dev) * math.radians(10)).clamp(-math.radians(30), math.radians(30))
    R = torch.stack([torch.stack([a.cos(), -a.sin()], -1), torch.stack([a.sin(), a.cos()], -1)], 1)
    q = base @ R.transpose(1, 2)
    span = q.amax(1) - q.amin(1)
    lo = span / 2
    hi = torch.tensor([FRAME_W, FRAME_H], device=dev) - span / 2
    c = lo + torch.rand(b, 2, device=dev) * (hi - lo).clamp(min=0)
    return q + c[:, None]


def _grid(b, dev, step: int = 2):
    """Pixel-coordinate grid at 1/step resolution (smooth masks are computed small, then upsampled)."""
    yy, xx = torch.meshgrid(torch.arange(0, FRAME_H, step, device=dev, dtype=torch.float32),
                            torch.arange(0, FRAME_W, step, device=dev, dtype=torch.float32), indexing="ij")
    return xx.expand(b, -1, -1), yy.expand(b, -1, -1)


def _up(m):
    return F.interpolate(m[:, None] if m.dim() == 3 else m, size=(FRAME_H, FRAME_W), mode="bilinear",
                         align_corners=False)


def _ellipse(xx, yy, cx, cy, sx, sy, ang):
    dx, dy = xx - cx[:, None, None], yy - cy[:, None, None]
    ca, sa = ang.cos()[:, None, None], ang.sin()[:, None, None]
    u, v = dx * ca + dy * sa, -dx * sa + dy * ca
    return (u / sx[:, None, None]) ** 2 + (v / sy[:, None, None]) ** 2


def _point_in_quad(q, dev):
    """Random point inside each quad (bilinear interpolation of the corners)."""
    s, t = torch.rand(q.shape[0], 1, device=dev), torch.rand(q.shape[0], 1, device=dev)
    top = q[:, 0] + (q[:, 1] - q[:, 0]) * s
    bot = q[:, 3] + (q[:, 2] - q[:, 3]) * s
    return top + (bot - top) * t


@torch.no_grad()
def augment(refs, idx, bg_pool, chunk: int = 64, **kw):
    """augment_batch() in chunks to bound peak VRAM.

    cuDNN autotuning is switched off here: blur/JPEG run on random-size sub-batches, and with benchmark=True
    every new shape triggers a slow autotune (this made training ~3x slower).
    """
    with torch.backends.cudnn.flags(enabled=True, benchmark=False):
        return torch.cat([augment_batch(refs, idx[i:i + chunk], bg_pool, **kw) for i in range(0, len(idx), chunk)])


@torch.no_grad()
def augment_batch(refs: torch.Tensor, idx: torch.Tensor, bg_pool: torch.Tensor, strength: float = 1.0,
                  jpeg: bool = True, return_frames: bool = False, realistic: bool = False):
    """refs[idx] -> normalized [B, 3, INPUT, INPUT] float model input (and optionally the frames).

    realistic=True adds the effects seen in the first real photos (real_aug.py): holo sheen, dark binder-pocket
    backgrounds, stronger sleeves, broad glare, white balance 2700-7500 K, low light + phone ISP, and much less
    precise crops (dark margins up to 12 % per side, corner jitter up to ~5 %, partial crops).
    """
    dev = refs.device
    b = idx.shape[0]
    card = refs[idx].float() / 255  # [B,4,H,W] RGBA
    if realistic:
        card = holo_sheen(card)
    quad = random_quads(b, dev)
    M = get_perspective_transform(_corners(REF_W, REF_H, dev, b), quad)
    warped = warp_perspective(card, M, (FRAME_H, FRAME_W), mode="bilinear", padding_mode="zeros",
                              align_corners=True)
    rgb, alpha = warped[:, :3], warped[:, 3:4]

    # background: random crop of a random pool texture + random tint
    bi = torch.randint(0, bg_pool.shape[0], (b,), device=dev)
    S = bg_pool.shape[-1]
    ox = torch.randint(0, S - FRAME_W, (1,)).item()
    oy = torch.randint(0, S - FRAME_H, (1,)).item()
    bg = bg_pool[bi, :, oy:oy + FRAME_H, ox:ox + FRAME_W].float() / 255
    flip = torch.rand(b, device=dev) < 0.5
    bg = torch.where(flip[:, None, None, None], bg.flip(-1), bg)
    bg = bg * _rand(b, 0.7, 1.2, dev)[:, None, None, None] * (1 + 0.15 * torch.randn(b, 3, 1, 1, device=dev))
    bg = bg.clamp(0, 1)
    if realistic:  # black binder page / pocket behind the card
        pocket = (torch.rand(b, device=dev) < 0.35)[:, None, None, None]
        bg = torch.where(pocket, bg * _rand(b, 0.04, 0.15, dev)[:, None, None, None], bg)

    # soft drop shadow
    sh = F.interpolate(gaussian_blur2d(F.avg_pool2d(alpha, 4), (9, 9), _rand(b, 0.7, 2.0, dev)[:, None].expand(b, 2)),
                       size=(FRAME_H, FRAME_W), mode="bilinear", align_corners=False)
    sh = torch.roll(sh, shifts=(int(torch.randint(-5, 6, (1,))), int(torch.randint(-5, 6, (1,)))), dims=(2, 3))
    use_sh = (torch.rand(b, device=dev) < 0.6)[:, None, None, None]
    bg = torch.where(use_sh, bg * (1 - 0.45 * sh), bg)
    img = rgb * alpha + bg * (1 - alpha)

    xx, yy = _grid(b, dev)
    center = quad.mean(1)
    # sleeve: slightly larger quad, milky tint
    s_on = (torch.rand(b, device=dev) < (0.7 if realistic else 0.5) * strength)[:, None, None, None]
    scale = _rand(b, 1.02, 1.10 if realistic else 1.06, dev)[:, None, None]
    sq = center[:, None] + (quad - center[:, None]) * scale
    ones = torch.ones(b, 1, 8, 8, device=dev)
    sm = _up(warp_perspective(ones, get_perspective_transform(_corners(8, 8, dev, b), sq / 2),
                              (FRAME_H // 2, FRAME_W // 2), align_corners=True))
    tint = _rand(b, 0.85, 1.0, dev)[:, None, None, None]
    sa = _rand(b, 0.06, 0.25 if realistic else 0.18, dev)[:, None, None, None]
    img = torch.where(s_on, img * (1 - sm * sa) + tint * sm * sa, img)
    if realistic:  # bright, translucent sleeve / pocket edge
        sq2 = center[:, None] + (quad - center[:, None]) * (scale - 0.012)
        inner = _up(warp_perspective(ones, get_perspective_transform(_corners(8, 8, dev, b), sq2 / 2),
                                     (FRAME_H // 2, FRAME_W // 2), align_corners=True))
        ring = (sm - inner).clamp(0, 1)
        img = torch.where(s_on, img + ring * _rand(b, 0.1, 0.5, dev)[:, None, None, None], img)

    # glare blob(s) + specular streak, additive
    g_on = (torch.rand(b, device=dev) < 0.6 * strength).float()[:, None, None]
    p = _point_in_quad(quad, dev)
    sx = _rand(b, 14, 85, dev)
    blob = torch.exp(-0.5 * _ellipse(xx, yy, p[:, 0], p[:, 1], sx, sx * _rand(b, 0.25, 1, dev), _rand(b, 0, math.pi, dev)))
    glare = blob * _rand(b, 0.3, 0.9, dev)[:, None, None] * g_on
    st_on = (torch.rand(b, device=dev) < 0.4 * strength).float()[:, None, None]
    th = _rand(b, 0, math.pi, dev)
    dist = (xx - center[:, 0, None, None]) * th.sin()[:, None, None] - (yy - center[:, 1, None, None]) * th.cos()[:, None, None]
    dist = dist + _rand(b, -85, 85, dev)[:, None, None]
    streak = torch.exp(-0.5 * (dist / _rand(b, 4, 20, dev)[:, None, None]) ** 2) * _rand(b, 0.15, 0.5, dev)[:, None, None]
    img = img + _up(glare + streak * st_on)
    if realistic:
        img = 1.6 * img / (1 + 0.6 * img)  # highlight roll-off like a camera tone curve, keeps detail under glare
        img = broad_glare(img.clamp(0, 1), center)

    # thumb: skin-coloured ellipse on one card edge
    t_on = (torch.rand(b, device=dev) < 0.3 * strength)[:, None, None, None]
    e = torch.randint(0, 4, (b,), device=dev)
    a0 = quad[torch.arange(b), e]
    a1 = quad[torch.arange(b), (e + 1) % 4]
    tp = a0 + (a1 - a0) * _rand(b, 0.2, 0.8, dev)[:, None]
    ang = torch.atan2((a1 - a0)[:, 1], (a1 - a0)[:, 0]) + math.pi / 2
    tm = _up((_ellipse(xx, yy, tp[:, 0], tp[:, 1], _rand(b, 17, 31, dev), _rand(b, 28, 49, dev), ang) < 1).float())
    skin = torch.stack([_rand(b, 0.67, 0.92, dev), _rand(b, 0.47, 0.75, dev), _rand(b, 0.31, 0.67, dev)], 1)[..., None, None]
    img = torch.where(t_on, img * (1 - tm) + skin * tm, img)

    # colour temperature, gain, contrast
    t = _rand(b, -0.18, 0.18, dev)
    gain = torch.stack([1 + t, torch.ones_like(t), 1 - t], 1) * _rand(b, 0.7, 1.2, dev)[:, None]
    img = img * gain[..., None, None]
    m = img.mean((1, 2, 3), keepdim=True)
    img = ((img - m) * _rand(b, 0.75, 1.15, dev)[:, None, None, None] + m + _rand(b, -0.08, 0.08, dev)[:, None, None, None])
    img = img.clamp(0, 1)
    if realistic:
        img = white_balance(img)
        img = exposure_and_noise(img)

    # blur: motion for ~30 %, defocus (gaussian, per-sample sigma) for ~30 %
    r = torch.rand(b, device=dev)
    mb = r < 0.3 * strength
    if mb.any():
        k = int(torch.randint(2, 6, (1,))) * 2 + 1
        img[mb] = motion_blur(img[mb], k, _rand(int(mb.sum()), 0, 180, dev), torch.zeros(int(mb.sum()), device=dev))
    df = (r >= 0.3 * strength) & (r < 0.6 * strength)
    if df.any():
        sig = _rand(int(df.sum()), 0.6, 2.2, dev)[:, None].expand(-1, 2)
        img[df] = gaussian_blur2d(img[df], (11, 11), sig)

    # sensor noise
    img = (img + torch.randn_like(img) * _rand(b, 0.005, 0.04 * strength + 0.01, dev)[:, None, None, None]).clamp(0, 1)
    if realistic:
        img = phone_isp(img)

    # re-rectify with residual detection error (corner noise + occasional inner-frame / sleeve-edge crop)
    diag = (quad[:, 2] - quad[:, 0]).norm(dim=-1)
    jq = quad + torch.randn(b, 4, 2, device=dev) * (0.012 * diag)[:, None, None]
    sc = torch.where(torch.rand(b, device=dev) < 0.25, _rand(b, 0.92, 1.05, dev), torch.ones(b, device=dev))
    jq = center[:, None] + (jq - center[:, None]) * sc[:, None, None]
    if realistic:
        jq = imprecise_crop(jq, quad, dev)
    Minv = get_perspective_transform(jq, _corners(INPUT, INPUT, dev, b))
    crop = warp_perspective(img, Minv, (INPUT, INPUT), mode="bilinear", padding_mode="border", align_corners=True)
    if jpeg:  # JPEG artifacts on the 224 crop: ~6x cheaper than on the frame, similar effect
        jp = torch.rand(b, device=dev) < 0.7
        if jp.any():
            crop[jp] = jpeg_codec_differentiable(crop[jp], _rand(int(jp.sum()), 30, 90, dev)).clamp(0, 1)
    mean = torch.tensor(MEAN, device=dev)[None, :, None, None]
    std = torch.tensor(STD, device=dev)[None, :, None, None]
    x = (crop - mean) / std
    return (x, img, crop) if return_frames else x


def imprecise_crop(jq, quad, dev):
    """Crop errors seen with sleeves and binder pockets: dark margins 0-12 % per side (the quad caught the pocket
    edge), corner jitter up to ~5 % of the card size, and occasional partial crops (one side cut 15-40 %)."""
    b = jq.shape[0]
    u = ((quad[:, 1] - quad[:, 0]) + (quad[:, 2] - quad[:, 3])) / 2  # card width vector
    v = ((quad[:, 3] - quad[:, 0]) + (quad[:, 2] - quad[:, 1])) / 2  # card height vector
    m = torch.rand(b, 4, device=dev) * 0.12 * (torch.rand(b, 1, device=dev) < 0.45)  # left, top, right, bottom
    part = torch.rand(b, device=dev) < 0.08
    side = torch.randint(0, 4, (b,), device=dev)
    m[torch.arange(b, device=dev)[part], side[part]] = -_rand(int(part.sum()), 0.15, 0.4, dev)
    ml, mt, mr, mb = (m[:, i:i + 1] for i in range(4))
    q = jq.clone()
    q[:, 0] += -ml * u - mt * v
    q[:, 1] += mr * u - mt * v
    q[:, 2] += mr * u + mb * v
    q[:, 3] += -ml * u + mb * v
    size = (u.norm(dim=-1) + v.norm(dim=-1)) / 2
    jit = (torch.randn(b, 4, 2, device=dev) * 0.02).clamp(-0.05, 0.05) * size[:, None, None]
    return q + jit * (torch.rand(b, 1, 1, device=dev) < 0.5)


def save_grid(path, refs, bg_pool, n=32, seed=0, realistic=False):
    """Visual check: top half frames, bottom half the matching rectified model inputs."""
    torch.manual_seed(seed)
    idx = torch.randint(0, refs.shape[0], (n,), device=refs.device)
    _, frames, crops = augment_batch(refs, idx, bg_pool, return_frames=True, realistic=realistic)
    f = F.interpolate(frames, size=(208, 160), mode="area")
    c = F.interpolate(crops, size=(208, 160), mode="area")
    rows = []
    for i in range(0, n, 8):
        rows.append(torch.cat(list(f[i:i + 8]), -1))
        rows.append(torch.cat(list(c[i:i + 8]), -1))
    grid = (torch.cat(rows, -2).clamp(0, 1) * 255).byte().permute(1, 2, 0).cpu().numpy()
    cv2.imwrite(str(path), cv2.cvtColor(grid, cv2.COLOR_RGB2BGR))
