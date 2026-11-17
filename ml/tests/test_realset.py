# SPDX-License-Identifier: GPL-3.0-or-later
from collections import Counter

import numpy as np
import pytest

from common import split_of
from conftest import make_card
from realset import balanced_weights, plan_classes, real_samples, role_of

IDS = [f"set{s}-{n}" for s in range(20) for n in range(1, 201)]


def test_split_of_is_fixed_and_about_20_percent():
    assert split_of("sv03.5-006") == split_of("sv03.5-006")
    share = sum(split_of(c) == "test" for c in IDS) / len(IDS)
    assert 0.17 < share < 0.23


def test_role_never_moves_a_test_card():
    for c in IDS:
        assert (role_of(c) == "test") == (split_of(c) == "test")


def test_role_val_is_about_10_percent_of_the_rest():
    roles = Counter(role_of(c) for c in IDS)
    assert set(roles) == {"test", "val", "train"}
    assert 0.07 < roles["val"] / (roles["val"] + roles["train"]) < 0.13


def _fake_role(card_id):
    return {"a-1": "test", "a-2": "val"}.get(card_id, "train")


def test_plan_classes_keeps_base_order_and_excludes_test_and_val():
    base = [make_card("en/a-1", "1"), make_card("de/a-1", "1"), make_card("en/a-2", "2"), make_card("en/a-3", "3")]
    extra = [make_card("en/a-3", "3"), make_card("en/b-1", "1"), make_card("en/a-1", "1")]
    cards, cls = plan_classes(base, extra, role=_fake_role)
    assert [c.key for c in cards[:4]] == [c.key for c in base]  # synthetic labels index into the base
    assert [c.key for c in cards] == ["en/a-1", "de/a-1", "en/a-2", "en/a-3", "en/b-1"]  # no duplicates
    # test card a-1 (both languages) and val card a-2 stay in the gallery but never become a class
    assert [cards[i].key for i in cls] == ["en/a-3", "en/b-1"]


def test_real_samples_rejects_a_test_card_in_train():
    bad = next(c for c in IDS if split_of(c) == "test")
    with pytest.raises(AssertionError):
        real_samples([{"card_id": bad, "role": "train"}], "train")


def test_real_samples_filters_by_role_and_class():
    tr = [c for c in IDS if role_of(c) == "train"][:3]
    va = next(c for c in IDS if role_of(c) == "val")
    s = [{"card_id": tr[0], "role": "train"}, {"card_id": tr[1], "role": "train"}, {"card_id": va, "role": "val"}]
    assert real_samples(s, "train", {tr[0]}) == [s[0]]
    assert real_samples(s, "val") == [s[2]]


def test_real_samples_drops_detector_failures_from_train_only():
    tr = next(c for c in IDS if role_of(c) == "train")
    va = next(c for c in IDS if role_of(c) == "val")
    s = [{"card_id": tr, "role": "train", "sim_ref_baseline": 0.05},
         {"card_id": tr, "role": "train", "sim_ref_baseline": 0.4},
         {"card_id": va, "role": "val", "sim_ref_baseline": 0.05}]
    assert real_samples(s, "train") == [s[1]]
    assert real_samples(s, "val") == [s[2]]


def test_balanced_weights_give_each_card_equal_mass():
    ids = ["x", "x", "x", "y"]
    w = balanced_weights(ids)
    assert np.isclose(w.sum(), 1)
    assert np.isclose(w[:3].sum(), w[3])
