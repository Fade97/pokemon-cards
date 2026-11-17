# SPDX-License-Identifier: GPL-3.0-or-later
"""Shared paths and helpers for the recognition prototype."""
from __future__ import annotations

import csv
import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path

ML_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("CARDINDEX_DATA", r"D:\AI\pokemon-cards-data"))
MANIFEST = ML_DIR / "manifest.csv"
RESULTS_DIR = ML_DIR / "results"

REF_DIR = DATA_DIR / "ref"            # reference images (high.png), one per card
API_CACHE = DATA_DIR / "api"          # cached TCGdex JSON
SYNTH_DIR = DATA_DIR / "synth"        # synthetic test set
CKPT_DIR = DATA_DIR / "checkpoints"
INDEX_DIR = DATA_DIR / "index"
EXPORT_DIR = DATA_DIR / "export"
REAL_DIR = DATA_DIR / "real-photos"
REVIEW_DIR = DATA_DIR / "review"

# Card aspect ratio 63 x 88 mm. Rectified crops use this size.
CARD_W, CARD_H = 252, 352

# (lang, set id, holdout). Holdout sets are never used for training (no augmentation, no ArcFace class);
# they test whether a new set can be supported by only adding its embeddings to the index.
SETS = [
    ("en", "sv03.5", False),
    ("de", "sv03.5", False),
    ("ja", "SV2a", False),
    ("en", "me03", False),
    ("en", "me04", False),
    ("de", "30th", True),   # "30 Jahre", released 2026-09-16, unseen
    ("de", "me04", True),   # "Wachsendes Chaos": German twin of the trained en/me04 (language-twin test)
    ("de", "me03", True),   # "Optimale Ordnung": German twin of the trained en/me03
]


@dataclass(frozen=True)
class Card:
    key: str          # unique: "<lang>/<card id>"
    card_id: str      # TCGdex id, e.g. sv03.5-006
    lang: str
    set_id: str
    set_name: str
    local_id: str     # collector number as printed (without total)
    official: int     # printed set total, e.g. 165
    name: str
    category: str     # Pokemon / Trainer / Energy (in the card's language)
    dex_ids: tuple    # national dex numbers, empty for trainers/energy
    image_url: str
    holdout: bool = False  # True = unseen set, never used in training

    @property
    def ref_path(self) -> Path:
        return REF_DIR / self.lang / self.set_id / f"{self.local_id}.png"

    @property
    def group(self) -> str:
        return f"{self.lang}/{self.set_id}"


def load_manifest(include_holdout: bool = False) -> list[Card]:
    """Cards of the trained sets (870); with include_holdout=True also the unseen sets (1,031).

    Trained cards always come first, so the indices of the 870-card gallery stay the same.
    """
    with open(MANIFEST, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    cards = [
        Card(r["key"], r["card_id"], r["lang"], r["set_id"], r["set_name"], r["local_id"],
             int(r["official"]), r["name"], r["category"],
             tuple(int(x) for x in r["dex_ids"].split(";") if x), r["image_url"], r.get("holdout", "0") == "1")
        for r in rows
    ]
    cards.sort(key=lambda c: c.holdout)  # stable: trained sets first
    return cards if include_holdout else [c for c in cards if not c.holdout]


HOLDOUT_PERCENT = 20


def split_of(card_id: str) -> str:
    """Fixed card-level real-photo split (FORB, TCG-AR): the same card is always in the same split, whatever else
    changes. A 'test' card gets no real photo and no ArcFace class in train.py --real, and neither does a same-art
    twin or reprint of it under another id (realset.py twins). Caveat: runs fine-tuned from mnv3_256_real inherit a
    backbone that saw all 870 manifest cards as classes, including the manifest's test-split ids; no FORB/TCG-AR
    test card is a manifest card, so the real-photo test scores are not affected."""
    return "test" if int(hashlib.sha1(card_id.encode()).hexdigest(), 16) % 100 < HOLDOUT_PERCENT else "train"


def number_key(local_id: str) -> str:
    """Normalize a collector number for comparison ("006" -> "6", "TG01" stays)."""
    s = local_id.strip().upper()
    return s.lstrip("0") or "0" if s.isdigit() else s


def device():
    import torch
    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


def cached_index(path: Path, model_file: Path, cards: list[Card], build):
    """Loads the reference index at `path`, or builds and saves it with `build()`.

    The cache is valid only for the same model file (size and mtime) and the same cards in the same order, recorded
    in a sidecar `<path>.json`; a retrained model or a reordered manifest of equal length rebuilds it.
    """
    import numpy as np

    st = model_file.stat()
    stamp = {"model": model_file.name, "modelSize": st.st_size, "modelMtimeNs": st.st_mtime_ns,
             "cards": hashlib.sha256("\n".join(c.key for c in cards).encode()).hexdigest()}
    meta = path.with_name(path.name + ".json")
    if path.exists() and meta.exists() and json.loads(meta.read_text()) == stamp:
        return np.load(path)
    R = np.asarray(build(), dtype=np.float32)
    path.parent.mkdir(parents=True, exist_ok=True)
    np.save(path, R)
    meta.write_text(json.dumps(stamp))
    return R
