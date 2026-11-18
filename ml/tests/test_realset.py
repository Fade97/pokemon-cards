# SPDX-License-Identifier: GPL-3.0-or-later
from collections import Counter

import numpy as np

from common import split_of
from conftest import make_card
from realset import balanced_weights, blocked_keys, find_twins, plan_classes, real_samples, role_of

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


def test_real_samples_recomputes_the_stored_role():
    # an old samples.json may say "train" for a card that is test today: never train on it
    bad = next(c for c in IDS if split_of(c) == "test")
    assert real_samples([{"card_id": bad, "role": "train"}], "train") == []
    assert real_samples([{"card_id": bad, "role": "train"}], "test") == [{"card_id": bad, "role": "train"}]


def test_real_samples_drops_crops_of_a_neighbouring_held_out_card():
    tr = [c for c in IDS if role_of(c) == "train"][:2]
    te = next(c for c in IDS if role_of(c) == "test")
    s = [{"card_id": tr[0], "role": "train", "top1_baseline": te},      # detector caught a test card
         {"card_id": tr[0], "role": "train", "top1_baseline": tr[1]},   # wrong but harmless neighbour
         {"card_id": tr[0], "role": "train", "top1_baseline": tr[0]}]
    assert real_samples(s, "train") == s[1:]


def test_plan_classes_blocks_twins_of_held_out_cards():
    base = [make_card("ja/SV2a-025", "25"), make_card("en/sv03.5-025", "25")]
    cards, cls = plan_classes(base, [], role=lambda c: "test" if c == "sv03.5-025" else "train",
                              blocked={"ja/SV2a-025"})
    assert cls == []


def test_find_twins_and_blocked_keys():
    E = np.eye(3, dtype=np.float32)
    E[1] = [0.95, np.sqrt(1 - 0.95 ** 2), 0]          # same art as row 0
    keys, ids = ["ja/SV2a-025", "en/sv03.5-025", "en/x-1"], ["SV2a-025", "sv03.5-025", "x-1"]
    pairs = find_twins(keys, ids, E, ids, E, thr=0.9)
    assert ("ja/SV2a-025", "sv03.5-025", 0.95) in pairs
    assert all(ids[keys.index(k)] != o for k, o, _ in pairs)  # never a card with itself
    assert not any(o == "x-1" for _, o, _ in pairs)
    assert blocked_keys(pairs, role=lambda c: "test" if c == "sv03.5-025" else "train") == {"ja/SV2a-025"}


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


def test_language_twins_by_number_and_secret_dex():
    from realset import language_twins
    ja = [make_card("ja/SV2a-025", "25"), make_card("ja/SV2a-182", "182", dex_ids=(3,))]
    en = [make_card("en/sv03.5-025", "25"), make_card("en/sv03.5-182", "182", dex_ids=(6,)),
          make_card("en/sv03.5-198", "198", dex_ids=(3,))]
    pairs = set(language_twins(ja + en))
    assert ("ja/SV2a-025", "sv03.5-025", "number") in pairs and ("en/sv03.5-025", "SV2a-025", "number") in pairs
    # secret rares: numbers differ between the languages, the same Pokemon pairs up
    assert ("ja/SV2a-182", "sv03.5-198", "dex") in pairs
    assert not any(k == "ja/SV2a-182" and o == "sv03.5-182" for k, o, _ in pairs)


def test_all_cards_mode_trains_on_every_card(monkeypatch):
    import realset
    monkeypatch.setattr(realset, "ALL_CARDS", True)
    assert {role_of(c) for c in IDS} == {"train"}
    te = next(c for c in IDS if split_of(c) == "test")
    assert blocked_keys([(f"en/{IDS[0]}", te, 0.95)]) == set()
    samples = [{"card_id": te, "sim_ref_baseline": 0.5, "top1_baseline": te}]
    assert real_samples(samples, "train") == samples
    assert real_samples(samples, "val") == []
    cards, cls = plan_classes([make_card(f"en/{te}", "1")], [])
    assert cls == [0]
