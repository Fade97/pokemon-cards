# SPDX-License-Identifier: GPL-3.0-or-later
"""Preprocessing and geometry helpers that the app mirrors (crop -> model input)."""
import numpy as np
import pytest

from common import CARD_H, CARD_W
from detect import ASPECT, center_crop_fallback, order_quad, quad_iou, rectify
from models import INPUT, MEAN, STD, preprocess


def test_preprocess_shape_and_normalization():
    bgr = np.zeros((CARD_H, CARD_W, 3), np.uint8)
    bgr[..., 2] = 255  # pure red in BGR
    x = preprocess(bgr)
    assert x.shape == (3, INPUT, INPUT) and x.dtype == np.float32
    want = (np.array([1.0, 0.0, 0.0], np.float32) - MEAN) / STD  # RGB channel order
    np.testing.assert_allclose(x[:, 100, 100], want, rtol=1e-5)


def test_preprocess_composites_alpha_on_white():
    bgra = np.zeros((CARD_H, CARD_W, 4), np.uint8)  # fully transparent black
    x = preprocess(bgra, size=32)
    np.testing.assert_allclose(x[:, 16, 16], (1.0 - MEAN) / STD, rtol=1e-5)


def test_order_quad_portrait_from_any_order():
    q = np.array([[10, 10], [73, 10], [73, 98], [10, 98]], np.float32)  # TL TR BR BL, portrait
    for perm in ([2, 0, 3, 1], [3, 2, 1, 0], [1, 2, 3, 0]):
        np.testing.assert_allclose(order_quad(q[perm]), q)


def test_order_quad_rotates_landscape_to_portrait():
    land = np.array([[0, 0], [88, 0], [88, 63], [0, 63]], np.float32)
    o = order_quad(land)
    w = np.linalg.norm(o[1] - o[0])
    h = np.linalg.norm(o[3] - o[0])
    assert h > w


@pytest.mark.parametrize(("h", "w"), [(960, 720), (480, 1280), (1000, 300)])
def test_center_crop_fallback_inside_frame_with_card_aspect(h, w):
    q = center_crop_fallback(np.zeros((h, w, 3), np.uint8))
    cw, ch = q[1, 0] - q[0, 0], q[3, 1] - q[0, 1]
    assert cw / ch == pytest.approx(ASPECT, rel=1e-4)
    assert q[:, 0].min() >= 0 and q[:, 0].max() <= w and q[:, 1].min() >= 0 and q[:, 1].max() <= h


def test_quad_iou():
    a = np.array([[0, 0], [10, 0], [10, 10], [0, 10]], np.float32)
    assert quad_iou(a, a) == pytest.approx(1.0)
    assert quad_iou(a, a + [5, 0]) == pytest.approx(50 / 150, rel=1e-3)
    assert quad_iou(a, a + [20, 0]) == 0.0


def test_rectify_recovers_card_region():
    img = np.zeros((400, 300, 3), np.uint8)
    img[100:276, 50:176] = 200  # 126 x 176 "card" (63:88 x2)
    quad = np.array([[50, 100], [176, 100], [176, 276], [50, 276]], np.float32)
    crop = rectify(img, quad)
    assert crop.shape == (CARD_H, CARD_W, 3)
    assert crop[5:-5, 5:-5].min() == 200
