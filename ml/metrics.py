# SPDX-License-Identifier: GPL-3.0-or-later
"""Retrieval metrics, error categories and the simulated collector-number rerank."""
from __future__ import annotations

from collections import defaultdict

import numpy as np

from common import Card, number_key

# 151 international (sv03.5) mirrors the Japanese SV2a: cards 1-165 share number and artwork.
SHARED_ART_SETS = {"sv03.5", "SV2a"}


def same_art(a: Card, b: Card) -> bool:
    if a.key == b.key:
        return False
    if a.card_id == b.card_id:  # EN vs DE print of the same card
        return True
    if {a.set_id, b.set_id} == SHARED_ART_SETS and a.local_id.isdigit() and b.local_id.isdigit():
        n = int(a.local_id)
        return n == int(b.local_id) and n <= 165
    return False


def error_kind(truth: Card, pred: Card) -> str:
    if pred.key == truth.key:
        return "correct"
    if same_art(truth, pred):
        return "same_art_other_language"
    if truth.dex_ids and set(truth.dex_ids) & set(pred.dex_ids):
        return "same_pokemon_other_card"
    return "other"


def knn(query: np.ndarray, ref: np.ndarray, k: int = 5):
    """Cosine kNN on L2-normalized vectors. Returns (indices [N,k], sims [N,k])."""
    sims = query @ ref.T
    idx = np.argsort(-sims, axis=1)[:, :k]
    return idx, np.take_along_axis(sims, idx, 1)


def evaluate(topk: np.ndarray, truth_idx: np.ndarray, cards: list[Card]) -> dict:
    """topk: [N,k] indices into cards, truth_idx: [N]."""
    hit1 = topk[:, 0] == truth_idx
    hit5 = (topk[:, :5] == truth_idx[:, None]).any(1)
    res = {"n": int(len(truth_idx)), "top1": float(hit1.mean()), "top5": float(hit5.mean())}
    groups = defaultdict(list)
    for i, t in enumerate(truth_idx):
        groups[cards[t].group].append(i)
    res["by_group"] = {g: {"n": len(ix), "top1": float(hit1[ix].mean()), "top5": float(hit5[ix].mean())}
                       for g, ix in sorted(groups.items())}
    langs = defaultdict(list)
    for i, t in enumerate(truth_idx):
        langs[cards[t].lang].append(i)
    res["by_lang"] = {g: {"n": len(ix), "top1": float(hit1[ix].mean()), "top5": float(hit5[ix].mean())}
                      for g, ix in sorted(langs.items())}
    kinds = defaultdict(int)
    for i, t in enumerate(truth_idx):
        kinds[error_kind(cards[t], cards[topk[i, 0]])] += 1
    res["top1_errors"] = {k: v for k, v in kinds.items() if k != "correct"}
    return res


def simulate_ocr(truth: Card, rng: np.random.Generator, p_unreadable=0.20, p_wrong=0.05) -> str | None:
    """Simulated read of the printed collector number: None = unreadable, else a (maybe wrong) number."""
    r = rng.random()
    if r < p_unreadable:
        return None
    s = number_key(truth.local_id)
    if r < p_unreadable + p_wrong:
        digits = [i for i, ch in enumerate(s) if ch.isdigit()]
        if digits:
            i = digits[rng.integers(0, len(digits))]
            s = s[:i] + str((int(s[i]) + rng.integers(1, 10)) % 10) + s[i + 1:]
    return s


def rerank(topk: np.ndarray, sims: np.ndarray, cards: list[Card], numbers: list[str | None],
           k: int = 5) -> np.ndarray:
    """Move candidates whose collector number matches the read number to the front (stable by similarity).

    If the number is unreadable or matches none of the top-k, the order is kept.
    """
    out = topk.copy()
    for i, num in enumerate(numbers):
        if num is None:
            continue
        cand = list(topk[i, :k])
        match = [c for c in cand if number_key(cards[c].local_id) == num]
        if match:
            rest = [c for c in cand if c not in match]
            out[i, :k] = match + rest
    return out
