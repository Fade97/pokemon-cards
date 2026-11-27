"""Puts a real app screenshot into the chroma-green phone screen of a generated image.

    python compose_screen.py <scene.png> <screenshot.png> <out.png>

Finds the green screen, fits its four corners, warps the screenshot onto it with a homography and keeps the
scene's rounded corners and edges by using the green-ness as the mask. Needs Pillow and numpy.
"""
import sys

import numpy as np
from PIL import Image


def green_mask(rgb: np.ndarray) -> np.ndarray:
    """Soft 0..1 mask of chroma-green pixels."""
    r, g, b = (rgb[..., i].astype(np.float32) for i in range(3))
    score = g - np.maximum(r, b)  # pure green ≈ 255, frame and table ≈ 0
    return np.clip((score - 60) / 120, 0, 1)


def corners(mask: np.ndarray) -> np.ndarray:
    """Four corners (tl, tr, br, bl) of the green quad from its extreme points."""
    ys, xs = np.nonzero(mask > 0.5)
    s, d = xs + ys, xs - ys
    tl = (xs[s.argmin()], ys[s.argmin()])
    br = (xs[s.argmax()], ys[s.argmax()])
    tr = (xs[d.argmax()], ys[d.argmax()])
    bl = (xs[d.argmin()], ys[d.argmin()])
    quad = np.array([tl, tr, br, bl], dtype=np.float64)
    # Rounded corners pull the extreme points inwards; grow the quad a little so the screenshot covers them.
    centre = quad.mean(axis=0)
    return centre + (quad - centre) * 1.035


def homography(src: np.ndarray, dst: np.ndarray) -> np.ndarray:
    rows = []
    for (x, y), (u, v) in zip(src, dst):
        rows.append([x, y, 1, 0, 0, 0, -u * x, -u * y, -u])
        rows.append([0, 0, 0, x, y, 1, -v * x, -v * y, -v])
    _, _, vt = np.linalg.svd(np.array(rows))
    return vt[-1].reshape(3, 3)


def main(scene_path: str, shot_path: str, out_path: str) -> None:
    scene = Image.open(scene_path).convert("RGB")
    rgb = np.asarray(scene)
    mask = green_mask(rgb)
    quad = corners(mask)
    shot = Image.open(shot_path).convert("RGB")
    w, h = shot.size
    # PIL's perspective transform maps output pixels back to input pixels: homography from scene to screenshot.
    hmat = homography(quad, np.array([[0, 0], [w, 0], [w, h], [0, h]], dtype=np.float64))
    coeffs = (hmat / hmat[2, 2]).flatten()[:8]
    warped = shot.transform(scene.size, Image.Transform.PERSPECTIVE, coeffs, Image.Resampling.BICUBIC)
    # Grow the mask by one pixel so no green fringe is left at the screen edge.
    m = Image.fromarray((mask * 255).astype(np.uint8)).filter(__import__("PIL.ImageFilter").ImageFilter.MaxFilter(3))
    out = Image.composite(warped, scene, m)
    # Pull any leftover green tint out of the frame edge.
    arr = np.asarray(out).astype(np.float32)
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    spill = g > np.maximum(r, b) + 25
    arr[..., 1] = np.where(spill, np.maximum(r, b) + 10, g)
    Image.fromarray(arr.clip(0, 255).astype(np.uint8)).save(out_path)
    print("screen corners", quad.round(1).tolist())


if __name__ == "__main__":
    main(*sys.argv[1:4])
