# SPDX-License-Identifier: GPL-3.0-or-later
"""Two-pass corner detection (issue #78): detect, re-detect on the guide box around the first quad, keep the crop with
the higher top-1 similarity, never the second pass alone."""
import numpy as np
import pytest

from detect import MIN_REFINE_BOX, guide_crop, keep_higher_sim, two_pass
from eval_forb import two_pass_summary


class TestKeepHigherSim:
    def test_second_pass_wins_only_when_strictly_higher(self):
        assert keep_higher_sim((0.61, "a"), (0.62, "b")) == ("b", True)

    def test_tie_keeps_the_first_pass(self):
        assert keep_higher_sim((0.6, "a"), (0.6, "b")) == ("a", False)

    def test_lower_second_pass_keeps_the_first(self):
        assert keep_higher_sim((0.6, "a"), (0.3, "b")) == ("a", False)

    def test_missing_second_pass_keeps_the_first(self):
        assert keep_higher_sim((0.1, "a"), None) == ("a", False)


def card(x, y, w=630, h=880):
    return np.float32([[x, y], [x + w, y], [x + w, y + h], [x, y + h]])


class FakeCorners:
    """Stands in for LearnedDetector: a fixed quad as fractions of whatever box it gets, records the boxes."""

    def __init__(self, score=0.4):
        self.scores, self.seen = list(score) if isinstance(score, (list, tuple)) else [score], []

    def __call__(self, box):
        self.seen.append(box.shape[:2])
        h, w = box.shape[:2]
        score = self.scores[min(len(self.seen), len(self.scores)) - 1]  # one score per call, the last one repeats
        return np.float32([[0.2, 0.1], [0.8, 0.1], [0.8, 0.9], [0.2, 0.9]]) * np.float32([w, h]), score


class FakeMatch:
    """`match(quad) -> (top-1 similarity, value)`: the first call is the first pass, the second call the second."""

    def __init__(self, sims):
        self.sims, self.quads = list(sims), []

    def __call__(self, quad):
        self.quads.append(np.asarray(quad).copy())
        i = len(self.quads) - 1
        return self.sims[i], f"pass{i + 1}"


IMG = np.zeros((2048, 1536, 3), np.uint8)


class TestTwoPass:
    def test_keeps_the_second_pass_when_its_similarity_is_higher(self):
        det, match, q1 = FakeCorners(0.3), FakeMatch([0.5, 0.7]), card(400, 500)
        quad, score, value, used = two_pass(det, IMG, q1, 0.2, match)
        assert used and value == "pass2" and score == pytest.approx(0.3)
        l, t, r, b = guide_crop(IMG, q1)
        assert det.seen == [(b - t, r - l)]                   # re-detected on the guide box around q1 ...
        assert quad[0] == pytest.approx([l + 0.2 * (r - l), t + 0.1 * (b - t)], abs=1e-3)  # ... mapped back
        np.testing.assert_array_equal(match.quads[0], q1)     # the first pass is always recognised

    def test_keeps_the_first_pass_when_the_second_is_not_better(self):
        q1 = card(400, 500)
        for sims in ([0.7, 0.5], [0.6, 0.6]):
            quad, score, value, used = two_pass(FakeCorners(), IMG, q1, 0.2, FakeMatch(sims))
            assert not used and value == "pass1" and score == 0.2
            np.testing.assert_array_equal(quad, q1)

    def test_second_pass_is_never_used_alone(self):
        # a confident second detection does not matter: only the similarity decides, and it is lower
        quad, score, value, used = two_pass(FakeCorners(0.99), IMG, card(400, 500), 0.05, FakeMatch([0.4, 0.39]))
        assert (value, used, score) == ("pass1", False, 0.05)

    def test_tiny_first_quad_skips_the_second_pass(self):
        det, match = FakeCorners(), FakeMatch([0.4, 0.9])
        q1 = card(10, 10, w=8, h=11)
        assert guide_crop(IMG, q1)[2] - guide_crop(IMG, q1)[0] < MIN_REFINE_BOX
        quad, _, value, used = two_pass(det, IMG, q1, 0.2, match)
        assert not used and value == "pass1" and det.seen == [] and len(match.quads) == 1


def test_guide_crop_frames_the_card_at_78_percent_of_the_box_height():
    l, t, r, b = guide_crop(IMG, card(453, 584))           # card 630 x 880 centred at (768, 1024)
    assert (b - t) == pytest.approx(880 / 0.78 * 1.07, abs=1.5)
    assert (r - l) == pytest.approx(880 / 0.78 * 63 / 88 * 1.07, abs=1.5)
    assert (l + r) / 2 == pytest.approx(768, abs=1) and (t + b) / 2 == pytest.approx(1024, abs=1)


class TestPipelineTwoPass:
    """pipeline.Pipeline.run with two_pass=True, without a checkpoint: fake corners, fake embedding."""

    @staticmethod
    def pipe(first_sims, second_sims, scores):
        from conftest import make_card
        from pipeline import Pipeline

        p = Pipeline.__new__(Pipeline)
        p.detector, p.two_pass, p.ocr = "learned", True, None
        p.corners = FakeCorners(scores)
        p.cards = [make_card(f"en/s1-{i}", str(i)) for i in range(3)]
        calls = []

        def embed(img, quad):
            calls.append(np.asarray(quad).copy())
            sims = first_sims if len(calls) == 1 else second_sims
            return np.zeros((10, 10, 3), np.uint8), np.float32(sims)

        p.embed = embed
        return p, calls

    def test_second_crop_wins_and_its_score_gates_found(self):
        # first pass: card 0 at 0.4, score 0.3; second pass: card 2 at 0.8, score 0.05 (under the gate)
        p, calls = self.pipe([[0.4, 0.1], [0.2, 0.1], [0.3, 0.1]], [[0.1, 0.1], [0.2, 0.1], [0.8, 0.1]], (0.3, 0.05))
        r = p.run(IMG.copy(), ocr_number=(None, None))
        assert r.second_pass and r.top5[0] == "en/s1-2" and r.sims[0] == pytest.approx(0.8)
        assert not r.found and r.detector_score == pytest.approx(0.05)
        assert len(calls) == 2

    def test_first_crop_kept_when_the_second_is_not_better(self):
        p, _ = self.pipe([[0.1, 0.7], [0.2, 0.1], [0.3, 0.1]], [[0.1, 0.1], [0.6, 0.1], [0.2, 0.1]], (0.3, 0.9))
        r = p.run(IMG.copy(), ocr_number=(None, None))
        assert not r.second_pass and r.top5[0] == "en/s1-0" and r.sims[0] == pytest.approx(0.7)  # flipped crop
        assert r.found and r.detector_score == pytest.approx(0.3)


def test_pipeline_two_pass_needs_the_learned_detector():
    from pipeline import Pipeline

    with pytest.raises(ValueError, match="learned"):
        Pipeline("x", use_ocr=False, detector="classic", two_pass=True)


def test_two_pass_summary_counts_gains_and_losses():
    def row(one, two, used):
        return {"ok_exact": two, "top5_exact": True, "one_pass": {"ok_exact": one, "top5_exact": one},
                "second_pass_used": used}

    rows = [row(True, True, False), row(False, True, True), row(True, False, True), row(False, False, False)]
    assert two_pass_summary(rows) == {"n": 4, "one_pass_top1": 2, "two_pass_top1": 2, "one_pass_top5": 2,
                                      "two_pass_top5": 4, "second_pass_kept": 2, "gained": 1, "lost": 1}
