# SPDX-License-Identifier: GPL-3.0-or-later
"""eval_real.py eval: labels whose card is not in the index count as misses instead of crashing (issue #60)."""
from __future__ import annotations

import json
from pathlib import Path

import eval_real
from conftest import make_card


def pipe_entry(top: str) -> dict:
    return {"found": True, "top5": [top], "top5_reranked": [top], "number": None, "total": None}


def test_eval_counts_out_of_index_labels_as_misses(tmp_path, monkeypatch):
    cards = [make_card("de/30th-001", "001"), make_card("de/30th-002", "002")]
    monkeypatch.setattr(eval_real, "load_manifest", lambda include_holdout=False: cards)
    labels = {
        "a.jpg": {"key": "de/30th-001", "status": "reviewed"},   # right
        "b.jpg": {"key": "de/30th-002", "status": "auto"},       # wrong (pipeline says 001)
        "c.jpg": {"key": "de/mee-009", "status": "reviewed"},    # not in the index: a sure miss
        "d.jpg": {"key": None, "status": "unclear"},             # not scored
    }
    pipe = {"a.jpg": pipe_entry("de/30th-001"), "b.jpg": pipe_entry("de/30th-001"),
            "c.jpg": pipe_entry("de/30th-002"), "d.jpg": pipe_entry("de/30th-001")}
    (tmp_path / "labels.json").write_text(json.dumps(labels))
    (tmp_path / "pipeline.json").write_text(json.dumps(pipe))

    res = eval_real.step_eval([Path(f) for f in sorted(labels)], tmp_path, None)

    assert res["n_evaluated"] == 3
    assert res["all"]["n"] == 3 and res["all"]["top1_after_rerank"] == 1 / 3
    assert res["in_index_only"]["n"] == 2 and res["in_index_only"]["top1_after_rerank"] == 0.5
    assert res["out_of_index"]["n"] == 1 and res["out_of_index"]["labels"] == {"c.jpg": "de/mee-009"}
    # The unknown card is grouped by the set in its key and treated as an unseen set.
    assert (res["by_set"]["de/mee"]["n"], res["by_set"]["de/mee"]["top1_after_rerank"]) == (1, 0.0)
    assert res["unseen_sets"]["n"] == 1
    assert json.loads((tmp_path / "eval.json").read_text())["out_of_index"]["n"] == 1


def test_eval_without_out_of_index_labels_has_no_note(tmp_path, monkeypatch):
    cards = [make_card("de/30th-001", "001")]
    monkeypatch.setattr(eval_real, "load_manifest", lambda include_holdout=False: cards)
    (tmp_path / "labels.json").write_text(json.dumps({"a.jpg": {"key": "de/30th-001", "status": "auto"}}))
    (tmp_path / "pipeline.json").write_text(json.dumps({"a.jpg": pipe_entry("de/30th-001")}))

    res = eval_real.step_eval([Path("a.jpg")], tmp_path, None)

    assert res["out_of_index"] == {"n": 0, "labels": {}}
    assert res["all"] == res["in_index_only"]
