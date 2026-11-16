# SPDX-License-Identifier: GPL-3.0-or-later
"""Render reference cards into synthetic phone-photo conditions.

Used two ways:
  * `render()` is imported by train.py for on-the-fly training augmentation.
  * As a script it writes a held-out synthetic test set with a fixed seed:
      <data>/synth/test/<idx>.jpg + <data>/synth/test/labels.json (card key + true corners)
"""
from __future__ import annotations

import argparse
import json
import math
from functools import lru_cache
from multiprocessing import Pool

import cv2
import numpy as np

from common import SYNTH_DIR, load_manifest

FRAME_W, FRAME_H = 720, 960  # portrait phone frame (downscaled)


# ---------------------------------------------------------------- reference loading
@lru_cache(maxsize=1024)
def load_ref_rgba(path: str) -> np.ndarray:
    """BGRA uint8. TCGdex PNGs are palette images with transparent rounded corners."""
    img = cv2.imread(path, cv2.IMREAD_UNCHANGED)
    if img is None:
        raise FileNotFoundError(path)
    if img.ndim == 2:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGRA)
    elif img.shape[2] == 3:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2BGRA)
    return img


# ---------------------------------------------------------------- backgrounds
def _noise(rng, h, w, scale):
    small = rng.random((max(2, h // scale), max(2, w // scale))).astype(np.float32)
    return cv2.resize(small, (w, h), interpolation=cv2.INTER_CUBIC)


def background(rng: np.random.Generator, h: int, w: int) -> np.ndarray:
    kind = rng.choice(["wood", "cloth", "plain", "skin", "paper", "dark"],
                      p=[0.25, 0.2, 0.15, 0.15, 0.15, 0.10])
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    if kind == "wood":
        base = np.array(rng.choice([[40, 80, 140], [60, 110, 170], [30, 55, 90], [150, 190, 220]]), np.float32)
        ang = rng.uniform(0, math.pi)
        t = xx * math.cos(ang) + yy * math.sin(ang)
        grain = np.sin(t / rng.uniform(4, 12) + 6 * _noise(rng, h, w, 40)) * 0.5 + 0.5
        img = base[None, None] * (0.7 + 0.45 * grain[..., None])
    elif kind == "cloth":
        base = rng.uniform(30, 220, 3).astype(np.float32)
        p = rng.uniform(2, 5)
        weave = (np.sin(xx / p) * np.sin(yy / p)) * 0.5 + 0.5
        img = base[None, None] * (0.8 + 0.3 * weave[..., None]) * (0.85 + 0.3 * _noise(rng, h, w, 60)[..., None])
    elif kind == "skin":
        base = np.array([rng.uniform(80, 170), rng.uniform(120, 190), rng.uniform(170, 235)], np.float32)
        img = base[None, None] * (0.8 + 0.35 * _noise(rng, h, w, 120)[..., None])
    elif kind == "paper":
        v = rng.uniform(180, 245)
        img = np.full((h, w, 3), v, np.float32) * (0.92 + 0.12 * _noise(rng, h, w, 8)[..., None])
    elif kind == "dark":
        img = np.full((h, w, 3), rng.uniform(10, 50), np.float32) + 15 * _noise(rng, h, w, 30)[..., None]
    else:
        c1, c2 = rng.uniform(20, 230, 3), rng.uniform(20, 230, 3)
        g = (yy / h)[..., None]
        img = c1 * (1 - g) + c2 * g
    img = img + rng.normal(0, 4, img.shape)
    # distractor rectangles (other paper / objects) sometimes
    for _ in range(rng.integers(0, 3)):
        if rng.random() < 0.5:
            x0, y0 = rng.integers(0, w), rng.integers(0, h)
            pts = np.array([[x0, y0], [x0 + rng.integers(40, 300), y0 + rng.integers(-50, 50)],
                            [x0 + rng.integers(40, 300), y0 + rng.integers(40, 300)],
                            [x0 + rng.integers(-50, 50), y0 + rng.integers(40, 300)]], np.int32)
            cv2.fillConvexPoly(img, pts, rng.uniform(0, 255, 3).tolist())
    return np.clip(img, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- geometry
def random_quad(rng, card_h_frac=(0.55, 0.9), rot_deg=20.0, tilt=0.07):
    """Destination corners (TL, TR, BR, BL) of the card in the frame."""
    ch = rng.uniform(*card_h_frac) * FRAME_H
    cw = ch * 63 / 88
    cw = min(cw, FRAME_W * 0.95)
    ch = cw * 88 / 63
    base = np.array([[-cw / 2, -ch / 2], [cw / 2, -ch / 2], [cw / 2, ch / 2], [-cw / 2, ch / 2]], np.float32)
    base += rng.normal(0, tilt * ch / 2, base.shape).astype(np.float32)  # perspective
    a = math.radians(rng.normal(0, rot_deg / 2))
    a = float(np.clip(a, -math.radians(rot_deg * 1.5), math.radians(rot_deg * 1.5)))
    R = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]], np.float32)
    q = base @ R.T
    # keep it (mostly) inside the frame with a small random offset
    span = q.max(0) - q.min(0)
    cx = rng.uniform(span[0] / 2, FRAME_W - span[0] / 2) if span[0] < FRAME_W else FRAME_W / 2
    cy = rng.uniform(span[1] / 2, FRAME_H - span[1] / 2) if span[1] < FRAME_H else FRAME_H / 2
    return q + np.array([cx, cy], np.float32)


# ---------------------------------------------------------------- photometric effects
def add_sleeve(rng, img, quad):
    """Semi-transparent penny sleeve: slightly larger quad, milky tint, glossy edge band."""
    c = quad.mean(0)
    q = c + (quad - c) * rng.uniform(1.02, 1.06)
    mask = np.zeros(img.shape[:2], np.uint8)
    cv2.fillConvexPoly(mask, q.astype(np.int32), 255)
    m = cv2.GaussianBlur(mask, (5, 5), 0).astype(np.float32)[..., None] / 255
    tint = np.array(rng.uniform(215, 255, 3), np.float32)
    alpha = rng.uniform(0.06, 0.18)
    out = img.astype(np.float32) * (1 - m * alpha) + tint * m * alpha
    cv2.polylines(out, [q.astype(np.int32)], True, (235, 235, 235), int(rng.integers(1, 3)), cv2.LINE_AA)
    return np.clip(out, 0, 255).astype(np.uint8)


def add_glare(rng, img, quad):
    h, w = img.shape[:2]
    out = img.astype(np.float32)
    for _ in range(rng.integers(1, 3)):
        p = quad[rng.integers(0, 4)] * rng.uniform(0, 1) + quad.mean(0) * 0
        p = quad.mean(0) + (p - quad.mean(0)) * rng.uniform(0, 0.9)
        ax = rng.uniform(15, 120)
        ay = ax * rng.uniform(0.2, 1.0)
        blob = np.zeros((h, w), np.float32)
        cv2.ellipse(blob, (int(p[0]), int(p[1])), (int(ax), int(ay)), rng.uniform(0, 180), 0, 360, 1.0, -1)
        k = int(ax) | 1
        blob = cv2.GaussianBlur(blob, (k * 2 + 1, k * 2 + 1), ax / 2)
        out += blob[..., None] * rng.uniform(80, 230)
    if rng.random() < 0.4:  # specular streak
        blob = np.zeros((h, w), np.float32)
        c = quad.mean(0)
        d = rng.normal(0, 1, 2)
        d /= np.linalg.norm(d) + 1e-6
        p1, p2 = c - d * 400, c + d * 400
        cv2.line(blob, tuple(p1.astype(int)), tuple(p2.astype(int)), 1.0, int(rng.integers(6, 30)))
        blob = cv2.GaussianBlur(blob, (31, 31), 8)
        out += blob[..., None] * rng.uniform(40, 140)
    return np.clip(out, 0, 255).astype(np.uint8)


def add_thumb(rng, img, quad):
    """Skin-coloured blob overlapping one card edge (hand holding the card)."""
    i = rng.integers(0, 4)
    a, b = quad[i], quad[(i + 1) % 4]
    p = a + (b - a) * rng.uniform(0.2, 0.8)
    col = (rng.uniform(80, 170), rng.uniform(120, 190), rng.uniform(170, 235))
    out = img.copy()
    ang = math.degrees(math.atan2(*(b - a)[::-1]))
    cv2.ellipse(out, (int(p[0]), int(p[1])), (int(rng.uniform(25, 45)), int(rng.uniform(40, 70))),
                ang + 90, 0, 360, col, -1, cv2.LINE_AA)
    return out


def motion_blur(rng, img):
    k = int(rng.integers(5, 15))
    kern = np.zeros((k, k), np.float32)
    kern[k // 2, :] = 1
    M = cv2.getRotationMatrix2D((k / 2 - 0.5, k / 2 - 0.5), rng.uniform(0, 180), 1)
    kern = cv2.warpAffine(kern, M, (k, k))
    kern /= kern.sum() + 1e-6
    return cv2.filter2D(img, -1, kern)


def defocus(rng, img):
    r = int(rng.integers(2, 6))
    kern = np.zeros((2 * r + 1, 2 * r + 1), np.float32)
    cv2.circle(kern, (r, r), r, 1, -1)
    kern /= kern.sum()
    return cv2.filter2D(img, -1, kern)


def color_temp(rng, img):
    t = rng.uniform(-0.18, 0.18)
    gain = np.array([1 - t, 1.0, 1 + t], np.float32) * rng.uniform(0.7, 1.2)  # BGR
    out = img.astype(np.float32) * gain
    c = rng.uniform(0.75, 1.15)
    out = (out - 128) * c + 128 + rng.uniform(-20, 20)
    return np.clip(out, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- main renderer
def render(card_bgra: np.ndarray, rng: np.random.Generator, strength: float = 1.0):
    """Return (frame BGR uint8, quad float32[4,2] TL,TR,BR,BL in frame pixels)."""
    bg = background(rng, FRAME_H, FRAME_W)
    quad = random_quad(rng)
    h, w = card_bgra.shape[:2]
    src = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
    M = cv2.getPerspectiveTransform(src, quad)
    warped = cv2.warpPerspective(card_bgra, M, (FRAME_W, FRAME_H), flags=cv2.INTER_AREA,
                                 borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0, 0))
    a = warped[..., 3:4].astype(np.float32) / 255
    # soft drop shadow
    if rng.random() < 0.6:
        sh = cv2.GaussianBlur(a[..., 0], (0, 0), rng.uniform(3, 10))
        off = rng.integers(-8, 9, 2)
        sh = np.roll(sh, tuple(off), (0, 1))[..., None]
        bg = (bg.astype(np.float32) * (1 - 0.45 * sh)).astype(np.uint8)
    img = (warped[..., :3].astype(np.float32) * a + bg.astype(np.float32) * (1 - a)).astype(np.uint8)

    s = strength
    if rng.random() < 0.5 * s:
        img = add_sleeve(rng, img, quad)
    if rng.random() < 0.6 * s:
        img = add_glare(rng, img, quad)
    if rng.random() < 0.3 * s:
        img = add_thumb(rng, img, quad)
    img = color_temp(rng, img)
    r = rng.random()
    if r < 0.3 * s:
        img = motion_blur(rng, img)
    elif r < 0.6 * s:
        img = defocus(rng, img)
    if rng.random() < 0.8:
        img = np.clip(img.astype(np.float32) + rng.normal(0, rng.uniform(2, 10 * s + 2), img.shape), 0, 255
                      ).astype(np.uint8)
    q = int(rng.integers(35, 92))
    _, enc = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])
    img = cv2.imdecode(enc, cv2.IMREAD_COLOR)
    return img, quad


# ---------------------------------------------------------------- test-set CLI
def _init_worker():
    cv2.setNumThreads(1)


def _job(args):
    idx, key, path, seed, out_dir = args
    rng = np.random.default_rng(seed)
    img, quad = render(load_ref_rgba(path), rng)
    cv2.imwrite(str(out_dir / f"{idx:05d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
    return {"file": f"{idx:05d}.jpg", "key": key, "quad": quad.round(2).tolist()}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--per-card", type=int, default=5)
    ap.add_argument("--seed", type=int, default=20261116)
    ap.add_argument("--name", default="test")
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()
    out_dir = SYNTH_DIR / args.name
    out_dir.mkdir(parents=True, exist_ok=True)
    cards = load_manifest()
    jobs = []
    for ci, c in enumerate(cards):
        for k in range(args.per_card):
            idx = ci * args.per_card + k
            jobs.append((idx, c.key, str(c.ref_path), args.seed * 1000003 + idx, out_dir))
    with Pool(args.workers, initializer=_init_worker) as pool:
        labels = pool.map(_job, jobs, chunksize=16)
    (out_dir / "labels.json").write_text(json.dumps(labels), encoding="utf-8")
    print(f"wrote {len(labels)} images to {out_dir}")


if __name__ == "__main__":
    main()
