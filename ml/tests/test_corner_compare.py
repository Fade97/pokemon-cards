# SPDX-License-Identifier: GPL-3.0-or-later
"""corner_analysis.paired: old vs. new corner detector on the same test photos (issue #59)."""
from corner_analysis import paired


def _row(k, split, learned_ok, refine_ok, sim_l=0.6, sim_r=0.5, score=0.2, err=None):
    r = {"query": k, "split": split, "score_learned": score,
         "learned": {"ok": learned_ok, "sim": sim_l}, "refine": {"ok": refine_ok, "sim": sim_r}}
    if err is not None:
        r["err_learned"] = err
    return r


def test_paired_counts_flips_on_test_photos_only():
    old = [_row(1, "test", True, True), _row(2, "test", True, False), _row(3, "test", False, False, score=0.05),
           _row(4, "train", False, False)]
    new = [_row(1, "test", True, False), _row(2, "test", False, True, sim_l=0.4), _row(3, "test", True, False),
           _row(4, "train", True, True), _row(5, "test", True, True)]  # 5 has no old row: not paired
    p = paired(old, new, "query")
    assert p["n"] == 3
    assert p["learned"] == {"old_top1": 66.7, "new_top1": 66.7, "old_only": 1, "new_only": 1}
    assert p["refine"] == {"old_top1": 33.3, "new_top1": 33.3, "old_only": 1, "new_only": 1}
    # photo 2: old keeps learned (0.6 > 0.5, right); new keeps refine (0.5 > 0.4, right)
    assert p["pick_higher_sim"]["old_only"] == 0 and p["pick_higher_sim"]["new_only"] == 1
    assert p["score_learned_below_0.10_pct"] == [33.3, 0.0]
    assert "err_learned_mean" not in p


def test_paired_corner_error_change():
    old = [_row(1, "test", True, True, err=5.0), _row(2, "test", True, True, err=1.0)]
    new = [_row(1, "test", True, True, err=2.0), _row(2, "test", True, True, err=2.5)]
    p = paired(old, new, "query")
    assert p["err_learned_mean"] == [3.0, 2.25]
    assert p["err_better_by_1pct"] == 1 and p["err_worse_by_1pct"] == 1


def test_paired_without_overlap():
    assert paired([_row(1, "test", True, True)], [_row(2, "test", True, True)], "query") == {"n": 0}
