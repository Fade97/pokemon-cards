# SPDX-License-Identifier: GPL-3.0-or-later
"""Classic-CV card detection and rectification.

edges/contours -> largest card-shaped quadrilateral -> side-line refinement -> perspective warp to 63:88.

As a script: evaluate on the synthetic test set against the known placement (IoU + corner error)
and write ml/results/detection.json. Rectified crops go to <data>/synth/<name>-crops/ for later steps.
"""
from __future__ import annotations

import argparse
import json
from multiprocessing import Pool

import cv2
import numpy as np

from common import CARD_H, CARD_W, RESULTS_DIR, SYNTH_DIR

ASPECT = 63 / 88
WORK_SIZE = 640


def order_quad(q: np.ndarray) -> np.ndarray:
    """Order 4 points as TL, TR, BR, BL with the long side vertical (portrait)."""
    q = np.asarray(q, np.float32).reshape(4, 2)
    c = q.mean(0)
    ang = np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0])
    q = q[np.argsort(ang)]  # clockwise in image coords, starting near -pi (left)
    # pick start so that the first point is top-left: minimal x+y
    s = np.argmin(q.sum(1))
    q = np.roll(q, -s, 0)
    w = (np.linalg.norm(q[1] - q[0]) + np.linalg.norm(q[2] - q[3])) / 2
    h = (np.linalg.norm(q[3] - q[0]) + np.linalg.norm(q[2] - q[1])) / 2
    if w > h:  # landscape: rotate so that the long side becomes vertical
        q = np.roll(q, -1, 0)
    return q


def _refine(contour: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """Fit a line to the contour points of each side and intersect neighbouring lines.

    Card corners are rounded, so approxPolyDP corners sit slightly inside; line intersection fixes that.
    """
    pts = contour.reshape(-1, 2).astype(np.float32)
    lines = []
    for i in range(4):
        a, b = quad[i], quad[(i + 1) % 4]
        d = b - a
        L = np.linalg.norm(d) + 1e-6
        n = np.array([-d[1], d[0]]) / L
        t = ((pts - a) @ d) / (L * L)
        dist = np.abs((pts - a) @ n)
        sel = pts[(t > 0.15) & (t < 0.85) & (dist < max(4.0, 0.03 * L))]
        if len(sel) < 8:
            return quad
        vx, vy, x0, y0 = cv2.fitLine(sel, cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        lines.append((np.array([x0, y0]), np.array([vx, vy])))
    out = []
    for i in range(4):
        (p1, d1), (p2, d2) = lines[i - 1], lines[i]
        A = np.array([d1, -d2]).T
        if abs(np.linalg.det(A)) < 1e-6:
            return quad
        t = np.linalg.solve(A, p2 - p1)
        out.append(p1 + d1 * t[0])
    out = np.array(out, np.float32)
    if np.abs(out - quad).max() > 0.08 * np.linalg.norm(quad[2] - quad[0]):
        return quad
    return out


def _edge_maps(img: np.ndarray):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    for ch in (gray, hsv[..., 1], lab[..., 1], lab[..., 2]):
        ch = cv2.bilateralFilter(ch, 7, 40, 7)
        v = float(np.median(ch))
        for lo, hi in ((0.66 * v, 1.33 * v), (20, 60)):
            e = cv2.Canny(ch, max(5, lo), max(20, hi))
            yield cv2.morphologyEx(cv2.dilate(e, k), cv2.MORPH_CLOSE, k, iterations=2)


def _intersect(lines):
    out = []
    for i in range(4):
        (p1, d1), (p2, d2) = lines[i - 1], lines[i]
        A = np.array([d1, -d2], np.float32).T
        if abs(np.linalg.det(A)) < 1e-6:
            return None
        tt = np.linalg.solve(A, p2 - p1)
        out.append(p1 + d1 * tt[0])
    return np.array(out, np.float32)


def snap_sides(grads: list, quad: np.ndarray, band: float = 0.07) -> np.ndarray:
    """Snap each side of a rough quad to the strongest straight edge nearby.

    Contour hulls are biased outward (shadow halos, dilation) or catch the inner art frame of
    silver-bordered cards. For each half of each side we scan offsets along the normal within +-band*h and
    take the outermost offset whose robust gradient response reaches 60 % of the best one. Two halves per side
    give a point pair, so small angle errors are corrected too.
    """
    H, W = grads[0][0].shape
    c = quad.mean(0)
    h = (np.linalg.norm(quad[3] - quad[0]) + np.linalg.norm(quad[2] - quad[1])) / 2
    R = max(3, int(band * h))
    offs = np.arange(-R, R + 1, dtype=np.float32)
    lines = []
    for i in range(4):
        a, b = quad[i], quad[(i + 1) % 4]
        d = b - a
        n = np.array([-d[1], d[0]], np.float32) / (np.linalg.norm(d) + 1e-6)
        if np.dot((a + b) / 2 - c, n) < 0:
            n = -n
        pts = []
        for t0, t1 in ((0.12, 0.45), (0.55, 0.88)):
            t = np.linspace(t0, t1, 24, dtype=np.float32)
            base = a[None] + d[None] * t[:, None]  # (24, 2)
            p = base[None] + offs[:, None, None] * n[None, None]  # (O, 24, 2)
            xs = np.clip(p[..., 0], 0, W - 1).astype(np.float32)
            ys = np.clip(p[..., 1], 0, H - 1).astype(np.float32)
            g = sum(np.abs(cv2.remap(gx, xs, ys, cv2.INTER_LINEAR) * n[0] + cv2.remap(gy, xs, ys, cv2.INTER_LINEAR) * n[1])
                    for gx, gy in grads)
            prof = np.percentile(g, 40, axis=1)
            prof = np.convolve(prof, [0.25, 0.5, 0.25], mode="same")
            if prof.max() < 8:
                o = 0.0
            else:
                pk = int(np.argmax(prof))
                ok = np.where(prof >= 0.6 * prof.max())[0]
                ok = [k for k in ok if (k == 0 or prof[k] >= prof[k - 1]) and (k == len(prof) - 1 or prof[k] >= prof[k + 1])
                      and k > pk and (k - pk) <= 0.05 * h]
                # outer edge close to the strongest one (silver border vs. inner frame), else the strongest
                o = float(offs[ok[-1]] if ok else offs[pk])
            tm = (t0 + t1) / 2
            pts.append((a + d * tm, n, o))
        (p0, n0, o0), (p1, n1, o1) = pts
        if abs(o0 - o1) > max(3.0, 0.02 * h):  # halves disagree: likely texture, keep the rough side
            o0 = o1 = 0.0
        pts = [p0 + n0 * o0, p1 + n1 * o1]
        lines.append((pts[0], pts[1] - pts[0]))
    out = _intersect(lines)
    if out is None or np.abs(out - quad).max() > 0.15 * h:
        return quad
    if _aspect_err(order_quad(out)) > _aspect_err(order_quad(quad)) + 0.015:
        return quad
    return out


def _aspect_err(q):
    w = (np.linalg.norm(q[1] - q[0]) + np.linalg.norm(q[2] - q[3])) / 2
    h = (np.linalg.norm(q[3] - q[0]) + np.linalg.norm(q[2] - q[1])) / 2
    return abs(w / max(h, 1e-6) - ASPECT)


def _candidates(small: np.ndarray):
    H, W = small.shape[:2]
    frame_area = H * W
    for edges in _edge_maps(small):
        cnts, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
        for c in cnts:
            hull = cv2.convexHull(c)
            area = cv2.contourArea(hull)
            if area < 0.05 * frame_area or area > 0.98 * frame_area:
                continue
            peri = cv2.arcLength(hull, True)
            quad, rect_bonus = None, 1.0
            for eps in (0.01, 0.02, 0.035, 0.05):
                approx = cv2.approxPolyDP(hull, eps * peri, True)
                if len(approx) == 4:
                    quad = approx.reshape(4, 2).astype(np.float32)
                    break
            if quad is None:
                quad, rect_bonus = cv2.boxPoints(cv2.minAreaRect(hull)).astype(np.float32), 0.8
            quad = order_quad(quad)
            qa = cv2.contourArea(quad)
            if qa <= 0:
                continue
            fill = area / qa
            fill = min(fill, 1 / fill)
            w = (np.linalg.norm(quad[1] - quad[0]) + np.linalg.norm(quad[2] - quad[3])) / 2
            hh = (np.linalg.norm(quad[3] - quad[0]) + np.linalg.norm(quad[2] - quad[1])) / 2
            aspect_ok = float(np.exp(-((w / max(hh, 1e-6) - ASPECT) / 0.08) ** 2))
            if aspect_ok < 0.2 or fill < 0.8:
                continue
            score = (area / frame_area) ** 0.5 * aspect_ok * fill ** 6 * rect_bonus
            yield score, order_quad(_refine(hull, quad))


def _contains(outer, inner):
    return all(cv2.pointPolygonTest(outer.reshape(-1, 1, 2), (float(x), float(y)), False) >= 0 for x, y in inner)


def detect(img: np.ndarray, expand: bool = True):
    """Return (quad TL,TR,BR,BL in input pixels, score) or (None, 0)."""
    h0, w0 = img.shape[:2]
    s = min(WORK_SIZE / max(h0, w0), 1.0)
    small = cv2.resize(img, (round(w0 * s), round(h0 * s)), interpolation=cv2.INTER_AREA) if s < 1 else img
    cands = sorted(_candidates(small), key=lambda x: -x[0])
    if not cands:
        return None, 0.0
    best_score, best = cands[0]
    # prefer a slightly larger candidate that encloses the best one (outer card edge vs. inner art frame)
    ba = cv2.contourArea(best)
    for sc, q in cands[1:]:
        r = cv2.contourArea(q) / ba
        if 1.04 < r < 1.35 and sc > 0.5 * best_score and _contains(q, best):
            best, best_score = q, sc
            ba = cv2.contourArea(best)
    if expand:
        lab = cv2.cvtColor(cv2.GaussianBlur(small, (3, 3), 0), cv2.COLOR_BGR2LAB).astype(np.float32)
        # signed gradients of L, a, b (colour edges matter for silver borders on grey tables)
        grads = [(cv2.Sobel(lab[..., k], cv2.CV_32F, 1, 0, ksize=3), cv2.Sobel(lab[..., k], cv2.CV_32F, 0, 1, ksize=3))
                 for k in range(3)]
        best = order_quad(snap_sides(grads, best))
    return best / s, float(best_score)


def rectify(img: np.ndarray, quad: np.ndarray, size=(CARD_W, CARD_H)) -> np.ndarray:
    w, h = size
    dst = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
    M = cv2.getPerspectiveTransform(np.asarray(quad, np.float32), dst)
    return cv2.warpPerspective(img, M, (w, h), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REPLICATE)


def center_crop_fallback(img: np.ndarray) -> np.ndarray:
    """When detection fails, use a centred 63:88 box covering most of the frame."""
    h, w = img.shape[:2]
    ch = 0.8 * h
    cw = ch * ASPECT
    if cw > 0.95 * w:
        cw = 0.95 * w
        ch = cw / ASPECT
    x0, y0 = (w - cw) / 2, (h - ch) / 2
    return np.array([[x0, y0], [x0 + cw, y0], [x0 + cw, y0 + ch], [x0, y0 + ch]], np.float32)


def quad_iou(a: np.ndarray, b: np.ndarray) -> float:
    a = np.asarray(a, np.float32)
    b = np.asarray(b, np.float32)
    inter, _ = cv2.intersectConvexConvex(a, b)
    ua = cv2.contourArea(a) + cv2.contourArea(b) - inter
    return float(inter / ua) if ua > 0 else 0.0


# ---------------------------------------------------------------- evaluation
def _init():
    cv2.setNumThreads(1)


def _eval_one(args):
    path, gt, out_path = args
    img = cv2.imread(path)
    quad, score = detect(img)
    found = quad is not None
    q = quad if found else center_crop_fallback(img)
    cv2.imwrite(out_path, rectify(img, q), [cv2.IMWRITE_JPEG_QUALITY, 95])
    gt = np.array(gt, np.float32)
    iou = quad_iou(q, gt)
    diag = np.linalg.norm(gt[2] - gt[0])
    err = float(np.linalg.norm(q - gt, axis=1).mean() / diag)
    return {"found": found, "iou": iou, "corner_err": err, "score": score}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--name", default="test")
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--limit", type=int, default=0, help="evaluate every n-th image only (quick tuning)")
    args = ap.parse_args()
    src = SYNTH_DIR / args.name
    out = SYNTH_DIR / f"{args.name}-crops"
    out.mkdir(parents=True, exist_ok=True)
    labels = json.loads((src / "labels.json").read_text())
    if args.limit:
        labels = labels[:: args.limit]
    jobs = [(str(src / l["file"]), l["quad"], str(out / l["file"])) for l in labels]
    with Pool(args.workers, initializer=_init) as pool:
        res = pool.map(_eval_one, jobs, chunksize=8)
    iou = np.array([r["iou"] for r in res])
    err = np.array([r["corner_err"] for r in res])
    found = np.array([r["found"] for r in res])
    summary = {
        "n": len(res),
        "found_rate": float(found.mean()),
        "iou>=0.9": float((iou >= 0.9).mean()),
        "iou>=0.8": float((iou >= 0.8).mean()),
        "iou_median": float(np.median(iou)),
        "corner_err_median_pct_diag": float(np.median(err) * 100),
        "corner_err<2pct_diag": float((err < 0.02).mean()),
    }
    print(json.dumps(summary, indent=2))
    (out / "detections.json").write_text(json.dumps([dict(r, file=l["file"]) for r, l in zip(res, labels)]))
    if args.limit:
        return
    RESULTS_DIR.mkdir(exist_ok=True)
    (RESULTS_DIR / f"detection_{args.name}.json").write_text(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
