# SPDX-License-Identifier: GPL-3.0-or-later
"""GPU-synthetic scenes with exact card-corner labels, for the learned corner detector.

Each scene is what the app sees inside its card guide (portrait, 3:4): one main card plus clutter.
Scene types:
  binder   black 3x3 binder page, translucent pockets, neighbouring cards in pockets (some empty)
  sleeve   single card in a penny sleeve or a thick toploader
  loose    card on fabric / table, 0-3 neighbouring cards side by side or overlapping (also on top)
plus fingers on the card edge, perspective, glare and the realistic camera effects of real_aug.py.
The label is always the corners of the main **card**, never the sleeve, toploader or pocket edge.
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F
from kornia.enhance import jpeg_codec_differentiable
from kornia.filters import gaussian_blur2d, motion_blur
from kornia.geometry.linalg import transform_points
from kornia.geometry.transform import get_perspective_transform, warp_perspective

from gpu_aug import REF_H, REF_W, _corners
from real_aug import broad_glare, exposure_and_noise, holo_sheen, phone_isp, white_balance

SCENE_W, SCENE_H = 288, 384   # rendered scene (= guide area), downsampled to the model input
ASPECT = 63 / 88


def _rand(b, lo, hi, dev):
    return torch.rand(b, device=dev) * (hi - lo) + lo


def _bc(v):
    return v[:, None, None, None]


def rect_quads(center, w, h, ang):
    """[B,2] centre, [B] width/height/angle -> [B,4,2] corners TL,TR,BR,BL."""
    dx = torch.stack([-w, w, w, -w], 1) / 2
    dy = torch.stack([-h, -h, h, h], 1) / 2
    ca, sa = ang.cos()[:, None], ang.sin()[:, None]
    return torch.stack([center[:, 0:1] + dx * ca - dy * sa, center[:, 1:2] + dx * sa + dy * ca], -1)


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


def _mask(quad, dev, half=True):
    b = quad.shape[0]
    s = 2 if half else 1
    ones = torch.ones(b, 1, 8, 8, device=dev)
    m = warp_perspective(ones, get_perspective_transform(_corners(8, 8, dev, b), quad / s),
                         (SCENE_H // s, SCENE_W // s), align_corners=True)
    return F.interpolate(m, size=(SCENE_H, SCENE_W), mode="bilinear", align_corners=False) if half else m


def _paste(img, refs, idx, quad, present, holo=True):
    """Composite cards refs[idx] at quad onto img where present."""
    b, dev = img.shape[0], img.device
    card = refs[idx].float() / 255
    if holo:
        card = holo_sheen(card, p=0.4)
    M = get_perspective_transform(_corners(REF_W, REF_H, dev, b), quad)
    w = warp_perspective(card, M, (SCENE_H, SCENE_W), mode="bilinear", padding_mode="zeros", align_corners=True)
    a = w[:, 3:4] * _bc(present.float())
    return w[:, :3] * a + img * (1 - a)


def _plastic(img, quad, on, alpha, edge, dev):
    """Translucent sleeve / pocket / toploader over quad: milky tint plus a bright edge."""
    m = _mask(quad, dev)
    c = quad.mean(1, keepdim=True)
    inner = _mask(c + (quad - c) * 0.985, dev)
    ring = (m - inner).clamp(0, 1)
    on = _bc(on.float())
    img = img * (1 - m * alpha * on) + m * alpha * on * _bc(_rand(img.shape[0], 0.8, 1.0, dev))
    return img + ring * edge * on


@torch.no_grad()
def make_scenes(refs, bg_pool, b, dev, out_hw=(256, 192), gen=None):
    """Returns (input images [B,3,H,W] in [0,1], corners [B,4,2] in input pixels, TL,TR,BR,BL)."""
    if gen is not None:
        torch.manual_seed(gen)
    H, W = SCENE_H, SCENE_W
    kind = torch.multinomial(torch.tensor([0.4, 0.25, 0.35], device=dev), b, replacement=True)  # binder, sleeve, loose
    binder, sleeve, loose = kind == 0, kind == 1, kind == 2

    # background
    bi = torch.randint(0, bg_pool.shape[0], (b,), device=dev)
    S = bg_pool.shape[-1]
    ox, oy = int(torch.randint(0, S - W + 1, (1,))), int(torch.randint(0, S - H + 1, (1,)))
    bg = bg_pool[bi, :, oy:oy + H, ox:ox + W].float() / 255
    bg = bg * _bc(_rand(b, 0.6, 1.2, dev))
    page = bg * 0.15 + _bc(_rand(b, 0.02, 0.1, dev))
    img = torch.where(_bc(binder), page, bg).clamp(0, 1)

    # main card geometry (in scene pixels)
    ch = _rand(b, 0.45, 0.97, dev) * H
    cw = ch * ASPECT
    center = torch.stack([W / 2 + _rand(b, -0.15, 0.15, dev) * W, H / 2 + _rand(b, -0.12, 0.12, dev) * H], 1)
    ang = torch.where(binder, torch.randn(b, device=dev) * math.radians(3), torch.randn(b, device=dev) * math.radians(12))
    ang = ang + math.pi * (torch.rand(b, device=dev) < 0.1)  # upside-down card
    main_q = rect_quads(center, cw, ch, ang)

    # neighbours: binder grid around the main pocket, or loose cards side by side / stacked
    gap = _rand(b, 0.04, 0.09, dev)
    ca, sa = ang.cos(), ang.sin()
    slots = [(i, j) for i in (-1, 0, 1) for j in (-1, 0, 1) if (i, j) != (0, 0)]
    for (i, j) in slots:
        # binder: full pitch; loose: one neighbour row/col with random overlap
        dxs = torch.where(binder | sleeve, i * cw * (1 + gap), i * cw * _rand(b, 0.75, 1.15, dev))
        dys = torch.where(binder | sleeve, j * ch * (1 + gap), j * ch * _rand(b, 0.7, 1.1, dev))
        nc = center + torch.stack([dxs * ca - dys * sa, dxs * sa + dys * ca], 1)
        nang = ang + torch.where(loose, torch.randn(b, device=dev) * math.radians(6), torch.zeros(b, device=dev))
        nq = rect_quads(nc, cw, ch, nang)
        p_card = torch.where(binder, torch.full((b,), 0.75, device=dev),
                             torch.where(loose, torch.full((b,), 0.22 if i * j == 0 else 0.05, device=dev),
                                         torch.zeros(b, device=dev)))
        present = torch.rand(b, device=dev) < p_card
        img = _paste(img, refs, torch.randint(0, refs.shape[0], (b,), device=dev), nq, present, holo=False)
        pocket = rect_quads(nc + torch.randn(b, 2, device=dev) * 0.01 * ch[:, None], cw * 1.06, ch * 1.035, nang)
        img = _plastic(img, pocket, binder, _bc(_rand(b, 0.05, 0.2, dev)), _bc(_rand(b, 0.05, 0.35, dev)), dev)

    idx = torch.randint(0, refs.shape[0], (b,), device=dev)
    img = _paste(img, refs, idx, main_q, torch.ones(b, dtype=torch.bool, device=dev))

    # main card's own plastic: pocket (binder), sleeve or toploader (sleeve scenes), sometimes a sleeve when loose
    shift = torch.randn(b, 2, device=dev) * 0.012 * ch[:, None]
    pocket = rect_quads(center + shift, cw * 1.06, ch * 1.035, ang)
    toploader = (torch.rand(b, device=dev) < 0.45) & sleeve
    s_w = torch.where(toploader, _rand(b, 1.10, 1.17, dev), _rand(b, 1.025, 1.06, dev))
    s_h = torch.where(toploader, _rand(b, 1.06, 1.11, dev), _rand(b, 1.015, 1.04, dev))
    sl_q = rect_quads(center + shift * 0.5, cw * s_w, ch * s_h, ang)
    plast = torch.where(binder[:, None, None], pocket, sl_q)
    on = binder | sleeve | (loose & (torch.rand(b, device=dev) < 0.3))
    edge = torch.where(toploader, _rand(b, 0.3, 0.7, dev), _rand(b, 0.05, 0.4, dev))
    img = _plastic(img, plast, on, _bc(_rand(b, 0.05, 0.22, dev)), _bc(edge), dev)

    # loose neighbour lying on top of the main card (occludes an edge)
    top = loose & (torch.rand(b, device=dev) < 0.2)
    side = torch.where(torch.rand(b, device=dev) < 0.5, 1.0, -1.0)
    dys = side * ch * _rand(b, 0.82, 0.95, dev)
    tq = rect_quads(center + torch.stack([-dys * sa, dys * ca], 1), cw, ch, ang + torch.randn(b, device=dev) * 0.05)
    img = _paste(img, refs, torch.randint(0, refs.shape[0], (b,), device=dev), tq, top, holo=False)

    # fingers on the card edge
    yy, xx = torch.meshgrid(torch.arange(H, device=dev, dtype=torch.float32),
                            torch.arange(W, device=dev, dtype=torch.float32), indexing="ij")
    for _ in range(2):
        f_on = torch.rand(b, device=dev) < 0.25
        e = torch.randint(0, 4, (b,), device=dev)
        a0 = main_q[torch.arange(b), e]
        a1 = main_q[torch.arange(b), (e + 1) % 4]
        tp = a0 + (a1 - a0) * _rand(b, 0.15, 0.85, dev)[:, None]
        nrm = torch.stack([(a1 - a0)[:, 1], -(a1 - a0)[:, 0]], 1)
        nrm = nrm / nrm.norm(dim=-1, keepdim=True)
        out = (tp - center) * nrm
        nrm = torch.where((out.sum(-1) < 0)[:, None], -nrm, nrm)
        tp = tp + nrm * _rand(b, 0.0, 0.08, dev)[:, None] * ch[:, None]
        fa = torch.atan2(nrm[:, 1], nrm[:, 0])
        dx, dy = xx - tp[:, 0, None, None], yy - tp[:, 1, None, None]
        u = dx * fa.cos()[:, None, None] + dy * fa.sin()[:, None, None]
        v = -dx * fa.sin()[:, None, None] + dy * fa.cos()[:, None, None]
        rx, ry = (_rand(b, 0.12, 0.25, dev) * ch)[:, None, None], (_rand(b, 0.05, 0.08, dev) * ch)[:, None, None]
        fm = ((u / rx) ** 2 + (v / ry) ** 2 < 1).float()[:, None]
        fm = gaussian_blur2d(fm, (5, 5), (1.2, 1.2)) * _bc(f_on.float())
        skin = torch.stack([_rand(b, 0.67, 0.92, dev), _rand(b, 0.47, 0.75, dev), _rand(b, 0.31, 0.67, dev)], 1)
        img = img * (1 - fm) + skin[..., None, None] * fm

    # perspective of the whole scene
    src = _corners(W, H, dev, b)
    dst = src + torch.randn(b, 4, 2, device=dev) * 0.05 * torch.tensor([W, H], device=dev)
    Hm = get_perspective_transform(src, dst)
    img = warp_perspective(img, Hm, (H, W), mode="bilinear", padding_mode="reflection", align_corners=True)
    corners = transform_points(Hm, main_q)

    # light and camera
    center_px = corners.mean(1)
    yy2, xx2 = yy.expand(b, -1, -1), xx.expand(b, -1, -1)
    p = center_px + torch.randn(b, 2, device=dev) * 0.2 * ch[:, None]
    sx = _rand(b, 0.05, 0.3, dev) * ch
    blob = torch.exp(-0.5 * (((xx2 - p[:, 0, None, None]) / sx[:, None, None]) ** 2
                             + ((yy2 - p[:, 1, None, None]) / (sx * _rand(b, 0.3, 1, dev))[:, None, None]) ** 2))
    img = img + (blob * _rand(b, 0.2, 0.8, dev)[:, None, None] * (torch.rand(b, device=dev) < 0.5)[:, None, None])[:, None]
    img = (1.6 * img / (1 + 0.6 * img)).clamp(0, 1)
    img = broad_glare(img, center_px)
    img = white_balance(img)
    img = exposure_and_noise(img)
    r = torch.rand(b, device=dev)
    mb = r < 0.2
    if mb.any():
        img[mb] = motion_blur(img[mb], 7, _rand(int(mb.sum()), 0, 180, dev), torch.zeros(int(mb.sum()), device=dev))
    df = (r >= 0.2) & (r < 0.45)
    if df.any():
        img[df] = gaussian_blur2d(img[df], (9, 9), _rand(int(df.sum()), 0.6, 2.0, dev)[:, None].expand(-1, 2))
    img = phone_isp(img)

    oh, ow = out_hw
    x = F.interpolate(img, size=(oh, ow), mode="area").clamp(0, 1)
    x = jpeg_codec_differentiable(x, _rand(b, 40, 95, dev)).clamp(0, 1)
    corners = corners * torch.tensor([ow / W, oh / H], device=dev)
    return x, order_corners(corners)
