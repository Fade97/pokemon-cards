# SPDX-License-Identifier: GPL-3.0-or-later
import numpy as np

from conftest import make_card
from metrics import error_kind, evaluate, knn, same_art, topk_scores


def test_same_art():
    en = make_card("en/sv03.5-006", "006")
    de = make_card("de/sv03.5-006", "006")
    ja = make_card("ja/SV2a-006", "006", set_id="SV2a")
    ja_late = make_card("ja/SV2a-170", "170", set_id="SV2a")
    en_late = make_card("en/sv03.5-170", "170")
    assert same_art(en, de)
    assert same_art(en, ja) and same_art(ja, de)
    assert not same_art(en, en)           # the card itself is not "same art"
    assert not same_art(en_late, ja_late)  # above 165 the sets diverge
    assert not same_art(en, make_card("en/me03-006", "006"))


def test_error_kind():
    truth = make_card("en/sv03.5-006", "006", dex_ids=(6,))
    assert error_kind(truth, truth) == "correct"
    assert error_kind(truth, make_card("de/sv03.5-006", "006", dex_ids=(6,))) == "same_art_other_language"
    assert error_kind(truth, make_card("en/me03-010", "010", dex_ids=(6, 150))) == "same_pokemon_other_card"
    assert error_kind(truth, make_card("en/me03-011", "011", dex_ids=(25,))) == "other"
    trainer = make_card("en/sv03.5-170", "170")
    assert error_kind(trainer, make_card("en/me03-012", "012")) == "other"


def test_knn_and_topk_scores_agree():
    rng = np.random.default_rng(1)
    ref = rng.normal(size=(20, 8))
    ref /= np.linalg.norm(ref, axis=1, keepdims=True)
    q = ref[[3, 7]] + 0.01 * rng.normal(size=(2, 8))
    q /= np.linalg.norm(q, axis=1, keepdims=True)
    idx, sims = knn(q, ref, k=5)
    assert idx.shape == (2, 5) and list(idx[:, 0]) == [3, 7]
    assert np.all(np.diff(sims, axis=1) <= 0)
    np.testing.assert_array_equal(topk_scores(q @ ref.T, 5), idx)


def test_evaluate_counts():
    cards = [make_card("en/sv03.5-001", "001", dex_ids=(1,)), make_card("de/sv03.5-001", "001", dex_ids=(1,)),
             make_card("en/me03-002", "002", dex_ids=(2,))]
    truth = np.array([0, 1, 2, 2])
    topk = np.array([[0, 1], [0, 1], [1, 2], [2, 0]])
    r = evaluate(topk, truth, cards)
    assert r["n"] == 4
    assert r["top1"] == 0.5 and r["top5"] == 1.0
    assert r["by_lang"]["de"] == {"n": 1, "top1": 0.0, "top5": 1.0}
    assert r["by_group"]["en/me03"]["top1"] == 0.5
    assert r["top1_errors"] == {"same_art_other_language": 1, "other": 1}
