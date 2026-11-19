# SPDX-License-Identifier: GPL-3.0-or-later
"""Loader of the finish-detection photo set (#144), on tiny fake bursts."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import finish_set  # noqa: E402


def burst(root: Path, name: str, card: str = "sv03.5-006", finish: str = "normal", holder: str = "bare",
          frames: int = 2, lang: str = "en", **extra) -> Path:
    d = root / name
    d.mkdir(parents=True)
    entries = []
    for i in range(frames):
        (d / f"frame-{i:02d}.jpg").write_bytes(b"jpg")
        (d / f"crop-{i:02d}.jpg").write_bytes(b"jpg")
        entries.append({"frame": f"frame-{i:02d}.jpg", "crop": f"crop-{i:02d}.jpg",
                        "quad": [[0, 0], [10, 0], [10, 14], [0, 14]], "width": 1280, "height": 960,
                        "similarity": 0.8, "atMs": i * 200})
    doc = {"format": 1, "id": name, "cardId": card, "lang": lang, "finish": finish, "holder": holder,
           "light": "room", "torch": False, "createdAt": "2026-11-19T12:00:00Z", "device": "test", "app": "test",
           "frames": entries, **extra}
    (d / "burst.json").write_text(json.dumps(doc))
    return d


def test_load_reads_bursts_and_skips_incomplete_ones(tmp_path):
    burst(tmp_path, "b1", finish="holo", holder="toploader")
    (tmp_path / "b2-cancelled").mkdir()
    bursts, problems = finish_set.load(tmp_path)
    assert [b.id for b in bursts] == ["b1"]
    b = bursts[0]
    assert (b.card_id, b.set_id, b.finish, b.holder, len(b.frames)) == ("sv03.5-006", "sv03.5", "holo", "toploader", 2)
    assert b.frames[1].crop == tmp_path / "b1" / "crop-01.jpg" and b.frames[1].at_ms == 200
    assert len(problems) == 1 and "no burst.json" in problems[0]
    with pytest.raises(finish_set.SetError):
        finish_set.load(tmp_path, strict=True)


def test_bad_labels_and_missing_files_are_problems(tmp_path):
    burst(tmp_path, "b1", finish="shiny")
    d = burst(tmp_path, "b2")
    (d / "crop-01.jpg").unlink()
    burst(tmp_path, "b3", format=2)
    bursts, problems = finish_set.load(tmp_path)
    assert bursts == []
    assert any("finish 'shiny'" in p for p in problems)
    assert any("missing crop-01.jpg" in p for p in problems)
    assert any("format 2" in p for p in problems)


def test_overrides_fix_a_wrong_card_and_drop_a_burst(tmp_path):
    burst(tmp_path, "b1", card="sv03.5-007")
    burst(tmp_path, "b2")
    (tmp_path / "overrides.json").write_text(json.dumps({"b1": {"cardId": "sv03.5-006", "finish": "reverse"},
                                                         "b2": None, "gone": None}))
    bursts, problems = finish_set.load(tmp_path)
    assert [(b.id, b.card_id, b.finish) for b in bursts] == [("b1", "sv03.5-006", "reverse")]
    assert problems == ["overrides.json: no burst gone"]


def test_summary_counts_pairs_cover_and_targets(tmp_path):
    burst(tmp_path, "n1", card="sv01-001", finish="normal", holder="penny")
    burst(tmp_path, "r1", card="sv01-001", finish="reverse")
    burst(tmp_path, "h1", card="sv01-002", finish="holo")
    burst(tmp_path, "r2", card="sv01-002", finish="reverse", holder="double")
    # The same number in another language is another card.
    burst(tmp_path, "n2", card="sv01-002", finish="normal", lang="de")
    s = finish_set.summary(finish_set.load(tmp_path)[0])
    assert s["perFinish"] == {"normal": 2, "holo": 1, "reverse": 2}
    assert s["coveredPerFinish"] == {"normal": 1, "holo": 0, "reverse": 1}
    assert s["normalReversePairs"] == ["en/sv01-001"]
    assert s["holoReversePairs"] == ["en/sv01-002"]
    assert s["frames"] == 10 and s["sets"] == 1
    assert not any(s["checks"].values())


def test_cli_summary_runs_on_an_empty_root(tmp_path, capsys):
    assert finish_set.main(["summary", "--root", str(tmp_path / "none"), "--json"]) == 0
    assert json.loads(capsys.readouterr().out)["bursts"] == 0
