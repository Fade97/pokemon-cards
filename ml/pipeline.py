# SPDX-License-Identifier: GPL-3.0-or-later
"""The full recognition pipeline as the app would run it: detect -> embed -> kNN -> collector-number rerank.

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
from detect import center_crop_fallback, detect, rectify
from models import EmbeddingNet, embed_arrays, preprocess
from synth import load_ref_rgba

# Collector number strip in a rectified card (fractions of width/height). Covers SV-era and ME cards:
# number like "006/165" sits bottom-left under the attack box, set code + regulation mark to its left.
NUM_BOX = (0.0, 0.905, 0.50, 0.99)
NUM_RE = re.compile(r"(\d{1,3})\s*[/|\\]\s*(\d{2,3})")


@dataclass
class Result:
    found: bool
    quad: list
    top5: list[str]          # card keys by embedding similarity
    sims: list[float]
    number: str | None       # collector number read by OCR (normalized), None = unreadable
    total: str | None
    top5_reranked: list[str] = field(default_factory=list)


def load_model(run: str, dev=None):
    dev = dev or device()
    ck = torch.load(CKPT_DIR / f"{run}.pt", map_location="cpu", weights_only=False)
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
    def __init__(self, run: str, use_ocr: bool = True):
        self.dev = device()
        self.cards = load_manifest(include_holdout=True)  # app gallery: trained + unseen sets
        self.model = load_model(run, self.dev)
        self.index = build_index(self.model, self.cards, run, self.dev)
        self.ocr = NumberOCR(self.dev.type == "cuda") if use_ocr else None

    def run(self, img: np.ndarray, ocr_number: tuple | None = None) -> Result:
        quad, _ = detect(img)
        found = quad is not None
        if not found:
            quad = center_crop_fallback(img)
        crop = rectify(img, quad)
        # the detector cannot tell an upside-down card: embed both orientations, keep the better match
        qs = embed_arrays(self.model, [preprocess(crop), preprocess(cv2.rotate(crop, cv2.ROTATE_180))], self.dev)
        all_sims = self.index @ qs.T  # [N, 2]
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
                      [float(sims[i]) for i in order], num, total, [self.cards[i].key for i in reranked])


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
