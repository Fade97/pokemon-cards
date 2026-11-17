# SPDX-License-Identifier: GPL-3.0-or-later
"""Collector-number rerank: the simulated one in metrics.py and the one the pipeline (and the app) runs."""
import numpy as np
import pytest

from conftest import make_card
from metrics import rerank, simulate_ocr
from pipeline import rerank_one

CARDS = [
    make_card("en/sv03.5-006", "006", 165),   # 0
    make_card("en/sv03.5-007", "007", 165),   # 1
    make_card("en/me03-006", "006", 88),      # 2: same number, other set total
    make_card("de/sv03.5-006", "006", 165),   # 3: same number and total, other language
    make_card("en/sv03.5-TG01", "TG01", 165), # 4
]


class TestRerankOne:
    def test_unreadable_number_keeps_order(self):
        assert rerank_one([1, 0, 2], CARDS, None) == [1, 0, 2]

    def test_no_match_keeps_order(self):
        assert rerank_one([1, 0, 2], CARDS, "99") == [1, 0, 2]

    def test_number_match_moves_to_front_stable(self):
        assert rerank_one([1, 2, 0, 3], CARDS, "6") == [2, 0, 3, 1]

    def test_matching_total_wins_over_number_only(self):
        assert rerank_one([1, 2, 0, 3], CARDS, "6", "165") == [0, 3, 2, 1]
        assert rerank_one([1, 0, 3, 2], CARDS, "6", "88") == [2, 0, 3, 1]

    def test_letter_numbers(self):
        assert rerank_one([0, 4], CARDS, "TG01") == [4, 0]

    def test_accepts_numpy_order(self):
        assert [int(i) for i in rerank_one(np.array([1, 0]), CARDS, "6")] == [0, 1]


class TestSimulatedRerank:
    def test_moves_matches_and_keeps_rest(self):
        topk = np.array([[1, 2, 0, 3, 4], [1, 2, 0, 3, 4], [4, 1, 0, 2, 3]])
        out = rerank(topk, None, CARDS, ["6", None, "TG01"])
        np.testing.assert_array_equal(out, [[2, 0, 3, 1, 4], [1, 2, 0, 3, 4], [4, 1, 0, 2, 3]])
        np.testing.assert_array_equal(topk[0], [1, 2, 0, 3, 4])  # input untouched

    def test_only_first_k_considered(self):
        topk = np.array([[1, 4, 0]])
        np.testing.assert_array_equal(rerank(topk, None, CARDS, ["6"], k=2), [[1, 4, 0]])


@pytest.mark.parametrize("seed", range(5))
def test_simulate_ocr_is_deterministic_and_plausible(seed):
    card = make_card("en/sv03.5-006", "006")
    a = [simulate_ocr(card, np.random.default_rng(seed)) for _ in range(3)]
    b = [simulate_ocr(card, np.random.default_rng(seed)) for _ in range(3)]
    assert a == b
    assert all(r is None or (r.isdigit() and len(r) == 1) for r in a)


def test_simulate_ocr_rates():
    rng = np.random.default_rng(0)
    card = make_card("en/sv03.5-123", "123")
    reads = [simulate_ocr(card, rng) for _ in range(4000)]
    unreadable = sum(r is None for r in reads) / len(reads)
    wrong = sum(r is not None and r != "123" for r in reads) / len(reads)
    assert 0.17 < unreadable < 0.23
    assert 0.03 < wrong < 0.07
