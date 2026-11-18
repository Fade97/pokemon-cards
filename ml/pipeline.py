# SPDX-License-Identifier: GPL-3.0-or-later
"""The full recognition pipeline as the app runs it on a photo: detect -> embed -> kNN -> collector-number rerank.

Detection (`detector`):
- "learned" (default, the app): scale to 2048 px, search the live-scan guide area with the learned corner model.
- "classic": the same guide area with the classic CV detector (the app's DetectorKind.CLASSIC).
- "full": the classic detector on the whole, unscaled image (the pipeline before issue #61; synthetic scenes whose
  card is not centred).

`two_pass=True` (learned only, issue #78): cut the guide box around the first quad, detect again, embed both crops and
keep the one with the higher top-1 similarity (detect.two_pass). The second pass is never used on its own.

The reference index (one L2-normalized embedding per card, from the clean TCGdex image) is cached in
<data>/index/<run>.npy. OCR is EasyOCR on the bottom-left number strip of the rectified card (stand-in for ML Kit).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

import cv2
import numpy as np
import torch

from common import CKPT_DIR, INDEX_DIR, Card, cached_index, device, load_manifest, number_key
from detect import (
    CORNER_MIN_SCORE,
    LearnedDetector,
    app_resize,
    center_crop_fallback,
    detect,
    detect_app,
    looks_like_card,
    rectify,
    two_pass,
)
from models import EmbeddingNet, embed_arrays, preprocess
from synth import load_ref_rgba

# Collector number strip in a rectified card (fractions of width/height). Covers SV-era and ME cards:
# number like "006/165" sits bottom-left under the attack box, set code + regulation mark to its left.
NUM_BOX = (0.0, 0.905, 0.50, 0.99)
NUM_RE = re.compile(r"(\d{1,3})\s*[/|\\]\s*(\d{2,3})")
DETECTORS = ("learned", "classic", "full")


@dataclass
class Result:
    found: bool
    quad: list
    top5: list[str]          # card keys by embedding similarity
    sims: list[float]
    number: str | None       # collector number read by OCR (normalized), None = unreadable
    total: str | None
    top5_reranked: list[str] = field(default_factory=list)
    detector_score: float = 0.0  # learned: mean corner peak; classic: contour score
    second_pass: bool = False    # two-pass: the second detector pass gave the higher similarity and was kept


def load_model(run: str, dev=None):
    dev = dev or device()
    ck = torch.load(CKPT_DIR / f"{run}.pt", map_location="cpu", weights_only=True)
    m = EmbeddingNet(ck["backbone"], ck["dim"], pretrained=False)
    m.load_state_dict(ck["model"])
    return m.to(dev).eval()


def build_index(model, cards: list[Card], run: str, dev=None, cache=True) -> np.ndarray:
    def build():
        return embed_arrays(model, [preprocess(load_ref_rgba(str(c.ref_path))) for c in cards], dev or device())

    if not cache:
        return build().astype(np.float32)
    return cached_index(INDEX_DIR / f"{run}.npy", CKPT_DIR / f"{run}.pt", cards, build)


class NumberOCR:
    def __init__(self, gpu: bool = True):
        import easyocr  # optional dependency (ml[ocr])
        self.reader = easyocr.Reader(["en"], gpu=gpu, verbose=False)

    def strip(self, img: np.ndarray, quad: np.ndarray) -> np.ndarray:
        big = rectify(img, quad, (630, 880))
        x0, y0, x1, y1 = NUM_BOX
        h, w = big.shape[:2]
        s = big[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)]
        return cv2.resize(s, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)

    def read(self, img: np.ndarray, quad: np.ndarray):
        """Return (number, total) as normalized strings, or (None, None)."""
        s = self.strip(img, quad)
        texts = self.reader.readtext(s, detail=0, allowlist="0123456789/", paragraph=False)
        for t in texts:
            m = NUM_RE.search(t)
            if m:
                return number_key(m.group(1)), m.group(2)
        return None, None


class Pipeline:
    def __init__(self, run: str, use_ocr: bool = True, detector: str = "learned", two_pass: bool = False):
        if detector not in DETECTORS:
            raise ValueError(f"detector must be one of {DETECTORS}, not {detector!r}")
        if two_pass and detector != "learned":
            raise ValueError("two_pass needs the learned detector")
        self.detector = detector
        self.two_pass = two_pass
        self.corners = LearnedDetector() if detector == "learned" else None
        self.dev = device()
        self.cards = load_manifest(include_holdout=True)  # app gallery: trained + unseen sets
        self.model = load_model(run, self.dev)
        self.index = build_index(self.model, self.cards, run, self.dev)
        self.ocr = NumberOCR(self.dev.type == "cuda") if use_ocr else None

    def run(self, img: np.ndarray, ocr_number: tuple | None = None) -> Result:
        if self.detector == "full":
            quad, score = detect(img)
            found = quad is not None
            if not found:
                quad = center_crop_fallback(img)
        else:
            img = app_resize(img)
            quad, found, score = detect_app(img, self.corners)
        second = False
        if self.two_pass:
            def match(q):
                crop_sims = self.embed(img, q)
                return float(crop_sims[1].max()), crop_sims  # top-1 over both orientations

            quad, score, (crop, all_sims), second = two_pass(self.corners, img, quad, score, match)
            if second:
                found = score >= CORNER_MIN_SCORE and looks_like_card(quad)
        else:
            crop, all_sims = self.embed(img, quad)
        flip = int(all_sims[:, 1].max() > all_sims[:, 0].max())
        if flip:
            quad = np.roll(np.asarray(quad), 2, 0)
            crop = cv2.rotate(crop, cv2.ROTATE_180)
        self.last_crop = crop
        sims = all_sims[:, flip]
        order = np.argsort(-sims)[:5]
        if ocr_number is not None:
            num, total = ocr_number
        elif self.ocr is not None:
            num, total = self.ocr.read(img, quad)
        else:
            num, total = None, None
        reranked = rerank_one(order, self.cards, num, total)
        return Result(found, np.asarray(quad).round(1).tolist(), [self.cards[i].key for i in order],
                      [float(sims[i]) for i in order], num, total, [self.cards[i].key for i in reranked], float(score),
                      second)

    def embed(self, img: np.ndarray, quad) -> tuple[np.ndarray, np.ndarray]:
        """Rectify `quad` and embed both orientations (the detector cannot tell an upside-down card).
        Returns (crop, similarities [N, 2]: upright, rotated by 180 degrees)."""
        crop = rectify(img, quad)
        qs = embed_arrays(self.model, [preprocess(crop), preprocess(cv2.rotate(crop, cv2.ROTATE_180))], self.dev)
        return crop, self.index @ qs.T


def rerank_one(order, cards: list[Card], num: str | None, total: str | None = None) -> list[int]:
    """Candidates whose number (and printed total, if read) match move to the front; otherwise keep order."""
    if num is None:
        return list(order)

    def score(i):
        c = cards[i]
        if number_key(c.local_id) != num:
            return 0
        return 2 if total is None or str(c.official) == total else 1

    return sorted(order, key=lambda i: -score(i))  # sorted() is stable: similarity order inside each tier
