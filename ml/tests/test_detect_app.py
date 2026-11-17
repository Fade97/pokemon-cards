# SPDX-License-Identifier: GPL-3.0-or-later
"""The app's detection on a photo (detect.py): guide area like ScanGuide.kt, learned quad mapped back to the frame."""
import numpy as np
import pytest

from detect import CORNER_MIN_SCORE, app_resize, detect_app, guide_area


class TestGuideArea:
    def test_portrait_photo_matches_scan_guide(self):
        # 1536x2048: guide 1143.6 x 1597.4 at (196.2, 225.3), plus 7 % margin, truncated like Kotlin's toInt()
        assert guide_area(1536, 2048) == (116, 113, 1419, 1934)

    def test_narrow_frame_caps_the_guide_at_90_percent_width_and_clips(self):
        # 1000x2048: the 78 % height guide would be 1143.6 wide, so it is capped to 900 and the margin clipped
        assert guide_area(1000, 2048) == (0, 307, 1000, 1740)

    def test_area_is_centred(self):
        left, top, right, bottom = guide_area(1536, 2048)
        assert abs((left + right) / 2 - 768) <= 1
        assert abs((top + bottom) / 2 - 1024) <= 1


class TestAppResize:
    def test_scales_long_side_down_to_2048(self):
        assert app_resize(np.zeros((4000, 3000, 3), np.uint8)).shape[:2] == (2048, 1536)

    def test_never_scales_up(self):
        assert app_resize(np.zeros((960, 720, 3), np.uint8)).shape[:2] == (960, 720)


class FakeCorners:
    """Stands in for LearnedDetector: a fixed quad as fractions of the guide crop it is given."""

    def __init__(self, score: float):
        self.score = score
        self.seen = None

    def __call__(self, guide):
        self.seen = guide.shape[:2]
        h, w = guide.shape[:2]
        return np.float32([[0.1, 0.1], [0.9, 0.1], [0.9, 0.9], [0.1, 0.9]]) * np.float32([w, h]), self.score


class TestDetectApp:
    def test_learned_quad_is_mapped_from_the_guide_crop_to_the_frame(self):
        img = np.zeros((2048, 1536, 3), np.uint8)
        corners = FakeCorners(0.15)
        quad, found, score = detect_app(img, corners)
        left, top, right, bottom = guide_area(1536, 2048)
        assert corners.seen == (bottom - top, right - left)
        w, h = right - left, bottom - top
        assert quad[0] == pytest.approx([left + 0.1 * w, top + 0.1 * h], abs=1e-3)
        assert quad[2] == pytest.approx([left + 0.9 * w, top + 0.9 * h], abs=1e-3)
        assert found and score == pytest.approx(0.15)

    def test_low_score_keeps_the_quad_but_is_not_found(self):
        quad, found, _ = detect_app(np.zeros((2048, 1536, 3), np.uint8), FakeCorners(CORNER_MIN_SCORE - 0.01))
        assert not found
        assert quad.shape == (4, 2)

    def test_classic_on_an_empty_frame_falls_back_to_the_centre_crop(self):
        quad, found, score = detect_app(np.zeros((2048, 1536, 3), np.uint8))
        assert not found and score == 0.0
        assert quad.mean(0) == pytest.approx([768, 1024], abs=1)
