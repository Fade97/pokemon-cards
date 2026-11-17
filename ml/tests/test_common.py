# SPDX-License-Identifier: GPL-3.0-or-later
import json
import os

import numpy as np
import pytest

from common import cached_index, load_manifest, number_key
from conftest import make_card


@pytest.mark.parametrize(("raw", "want"), [
    ("006", "6"), ("6", "6"), (" 165 ", "165"), ("000", "0"), ("0", "0"),
    ("TG01", "TG01"), ("tg01", "TG01"), ("SV001", "SV001"),
])
def test_number_key(raw, want):
    assert number_key(raw) == want


def test_manifest_trained_sets_first():
    trained = load_manifest()
    full = load_manifest(include_holdout=True)
    assert trained and not any(c.holdout for c in trained)
    assert len(full) > len(trained)
    # the trained gallery is a prefix of the full one, so its indices stay valid
    assert [c.key for c in full[:len(trained)]] == [c.key for c in trained]
    assert all(c.holdout for c in full[len(trained):])
    assert len({c.key for c in full}) == len(full)


class TestCachedIndex:
    @pytest.fixture
    def setup(self, tmp_path):
        model = tmp_path / "run.pt"
        model.write_bytes(b"weights")
        cards = [make_card("en/sv03.5-001", "001"), make_card("en/sv03.5-002", "002")]
        calls = []

        def build(value=1.0):
            def _build():
                calls.append(value)
                return np.full((len(cards), 4), value)
            return _build

        return tmp_path / "index" / "run.npy", model, cards, build, calls

    def test_builds_then_reuses(self, setup):
        path, model, cards, build, calls = setup
        first = cached_index(path, model, cards, build(1.0))
        assert first.dtype == np.float32 and first.shape == (2, 4)
        assert path.exists() and path.with_name("run.npy.json").exists()
        again = cached_index(path, model, cards, build(2.0))
        assert calls == [1.0]
        np.testing.assert_array_equal(again, first)

    def test_rebuilds_when_model_changes(self, setup):
        path, model, cards, build, calls = setup
        cached_index(path, model, cards, build(1.0))
        model.write_bytes(b"retrained weights")
        st = model.stat()
        os.utime(model, ns=(st.st_atime_ns, st.st_mtime_ns + 1_000_000_000))
        out = cached_index(path, model, cards, build(2.0))
        assert calls == [1.0, 2.0]
        assert out[0, 0] == 2.0

    def test_rebuilds_when_model_touched(self, setup):
        path, model, cards, build, calls = setup
        cached_index(path, model, cards, build(1.0))
        st = model.stat()  # same size, new mtime: a retrain that happens to write an equal-sized file
        os.utime(model, ns=(st.st_atime_ns, st.st_mtime_ns + 1_000_000_000))
        cached_index(path, model, cards, build(2.0))
        assert calls == [1.0, 2.0]

    def test_rebuilds_when_cards_reordered(self, setup):
        path, model, cards, build, calls = setup
        cached_index(path, model, cards, build(1.0))
        cached_index(path, model, cards[::-1], build(2.0))
        assert calls == [1.0, 2.0]

    def test_rebuilds_without_sidecar(self, setup):
        path, model, cards, build, calls = setup
        cached_index(path, model, cards, build(1.0))
        path.with_name("run.npy.json").unlink()
        cached_index(path, model, cards, build(2.0))
        assert calls == [1.0, 2.0]
        assert json.loads(path.with_name("run.npy.json").read_text())["model"] == "run.pt"
