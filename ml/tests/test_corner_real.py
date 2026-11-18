# SPDX-License-Identifier: GPL-3.0-or-later
"""Real-photo corner data (issue #59): context windows, pseudo-label choice, the GPU guide-box sampler."""
import cv2
import numpy as np
import torch

from corner_real import RealCorners, real_val_metrics
from corner_realset import CTX_H, CTX_W, context, eval_input, pick_pseudo_label
from realset import role_of

QUAD = np.float32([[400, 300], [652, 310], [640, 662], [390, 650]])  # a slightly rotated card, ~252 x 350 px


def photo(w=1200, h=900, quad=QUAD):
    img = np.full((h, w, 3), 30, np.uint8)
    cv2.fillConvexPoly(img, quad.astype(np.int32), (230, 230, 230))
    return img


def test_context_window_keeps_the_card_where_the_quad_says():
    win, q, valid = context(photo(), QUAD)
    assert win.shape == (CTX_H, CTX_W, 3)
    c = q.mean(0)
    assert abs(c[0] - CTX_W / 2) < 1 and abs(c[1] - CTX_H / 2) < 1
    card_h = (np.linalg.norm(q[3] - q[0]) + np.linalg.norm(q[2] - q[1])) / 2
    assert 0.45 < card_h / CTX_H < 0.53
    inside = cv2.fillConvexPoly(np.zeros(win.shape[:2], np.uint8), q.astype(np.int32), 1).astype(bool)
    assert win[inside].mean() > 200 and win[~inside].mean() < 60
    assert (valid >= 0).all() and valid[2] <= CTX_W and valid[3] <= CTX_H


def test_context_valid_box_marks_the_photo_edge():
    q = QUAD - np.float32([380, 0])  # card near the left edge: the window reaches past the photo
    _, _, valid = context(photo(quad=q), q)
    assert valid[0] > 0 and valid[2] == CTX_W


def test_eval_input_maps_corners_to_model_pixels():
    x, q = eval_input(photo(), QUAD)
    assert x.shape == (256, 192, 3)
    assert (q > 0).all() and (q[:, 0] < 192).all() and (q[:, 1] < 256).all()
    assert x[int(q[:, 1].mean()), int(q[:, 0].mean())].mean() > 200


def _row(truth, learned_ok=True, refine_ok=True, sim_l=0.7, sim_r=0.6, card_like=True):
    r = lambda ok, s: {"ok": ok, "sim_truth": s}
    shape = {"card_like": card_like}
    return {"truth": truth, "learned": r(learned_ok, sim_l), "refine": r(refine_ok, sim_r),
            "shape_learned": shape, "shape_refine": shape, "q_learned": QUAD.tolist(), "q_refine": (QUAD + 1).tolist()}


def _ids(role):
    return next(f"x{i}-{i}" for i in range(1000) if role_of(f"x{i}-{i}") == role)


def test_pseudo_label_only_for_confident_train_cards():
    train, test, val = _ids("train"), _ids("test"), _ids("val")
    q, which = pick_pseudo_label(_row(train), 0.5)
    assert which == "learned" and np.allclose(q, QUAD)
    assert pick_pseudo_label(_row(train, sim_l=0.4, sim_r=0.45), 0.5) == (None, None)
    assert pick_pseudo_label(_row(train, card_like=False), 0.5) == (None, None)
    assert pick_pseudo_label(_row(test), 0.5) == (None, None)
    assert pick_pseudo_label(_row(val), 0.5) == (None, None)
    assert pick_pseudo_label(_row(train, learned_ok=False), 0.5)[1] == "refine"


def test_sampler_cuts_guide_boxes_with_the_card_inside(tmp_path):
    wins, quads, valids = [], [], []
    for dx in (0, -380):  # one card in the middle, one at the photo's left edge
        q = QUAD + np.float32([dx, 0])
        w, qc, v = context(photo(quad=q), q)
        wins.append(cv2.cvtColor(w, cv2.COLOR_BGR2RGB))
        quads.append(qc)
        valids.append(v)
    x_val, q_val = eval_input(photo(), QUAD)
    path = tmp_path / "real.npz"
    np.savez(path, win=np.stack(wins), quad=np.stack(quads), valid=np.stack(valids), kind=np.array(["tcgar", "forb"]),
             src=np.array(["a", "b"]), val_x=x_val[None], val_q=q_val[None], val_key=np.array(["a"]))
    torch.manual_seed(0)
    real = RealCorners(path, "cpu", tcgar_share=0.5)
    x, c = real.sample(64)
    assert x.shape == (64, 3, 256, 192) and c.shape == (64, 4, 2)
    assert (c[..., 0] >= -1).all() and (c[..., 0] <= 193).all() and (c[..., 1] >= -1).all() and (c[..., 1] <= 257).all()
    h = ((c[:, 3] - c[:, 0]).norm(dim=-1) + (c[:, 2] - c[:, 1]).norm(dim=-1)) / 2 / 256
    assert h.min() > 0.4 and h.max() < 1.0
    for i in range(8):  # the labelled quad covers the bright card
        img = (x[i].permute(1, 2, 0).numpy() * 255).astype(np.uint8)
        m = cv2.fillConvexPoly(np.zeros((256, 192), np.uint8), c[i].numpy().round().astype(np.int32), 1).astype(bool)
        m = cv2.erode(m.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
        assert img[m].mean() > 170


def test_real_val_metrics_ignore_the_corner_rotation():
    gt = QUAD[None]
    assert real_val_metrics(np.roll(gt, 2, 1), gt)["err_mean_pct_diag"] < 1e-6
    r = real_val_metrics(gt + 8, gt)  # 11.3 px on a 434 px diagonal
    assert abs(r["err_mean_pct_diag"] - 2.61) < 0.05 and r["within_3pct"] == 1.0


def test_build_refuses_without_tcgdex_ids(monkeypatch):
    # without the EN metadata every TCG-AR card would get its role from the image key, so a test-split card
    # could land in the training set
    import pytest

    import corner_realset
    monkeypatch.setattr(corner_realset, "known_ids", lambda: None)
    with pytest.raises(SystemExit, match="metadata.json"):
        corner_realset.cmd_build("any", 0.5, None)


def _forb_row(q, split, learned, refine, full=False, sim_l=0.6, sim_r=0.5, shift=0.5, card_like=True):
    r = lambda ok, sim: {"ok": ok, "top5": ok, "sim": sim}
    return {"query": q, "split": split, "difficulty": "easy", "w": 600, "h": 800, "score_learned": 0.15,
            "shift_refine": shift, "learned": r(learned, sim_l), "refine": r(refine, sim_r), "full": r(full, 0.1),
            "shape_learned": {"card_like": card_like, "area_frac": 0.3}}


def test_forb_summary_two_pass_rule_noise_floor_and_suspects():
    from corner_analysis import forb_summary
    rows = [_forb_row(0, "test", True, True),
            _forb_row(1, "test", False, True, sim_l=0.4, sim_r=0.7, shift=8),   # refine wins on similarity
            _forb_row(2, "test", True, False, sim_l=0.7, sim_r=0.6, shift=0.2),  # near-same quad, flips: noise
            _forb_row(3, "test", False, False, full=True, card_like=False),     # only the whole photo is right
            _forb_row(4, "train", False, False)]
    s = forb_summary(rows, {})["split=test"]
    assert s["n"] == 4 and s["top1_learned"] == 50.0 and s["top1_refine"] == 50.0 and s["top1_any"] == 100.0
    assert s["top1_pick_higher_sim"] == 75.0  # rows 0, 1, 2
    assert s["learned_vs_refine"] == {"learned_only": 1, "refine_only": 1, "both": 1, "neither": 1}
    assert s["misses_fixed_by_other_crop"] == 2 and s["misses_fixed_by_refine"] == 1
    assert s["noise_flips_at_shift<1%"] == {"n": 3, "flip_pct": 33.3}  # rows 0, 2, 3; only row 2 flips
    assert s["geometric_suspects"]["n"] == 2 and s["geometric_suspects"]["top1_learned"] == 0.0  # rows 1, 3
    assert s["top1_classic"] is None
