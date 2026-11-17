# SPDX-License-Identifier: GPL-3.0-or-later
"""Real-photo training data for the embedding: FORB and TCG-AR photos of train-split cards, cropped like the app.

Every card id gets one fixed role (`role_of`):
  test   the 20 % holdout of common.split_of. Never used in training in any form: no photo, no ArcFace class,
         not even a crop here. Only scored at the end (eval_forb.py / eval_tcgar.py).
  val    10 % of the remaining cards (separate hash). Treated like test during training (no photo, no class), so
         that model selection measures the same thing as the final test: real photos of cards never trained on.
  train  real photos and ArcFace class allowed.

  build    crop every train/val photo exactly like the evaluators: FORB -> learned corners on the whole photo
           (long side 2048 px); TCG-AR -> learned corners inside the app guide box around the annotated card.
           Rectify, then pick the 180-degree orientation that matches the reference better (baseline .tflite).
           -> <data>/real-train/crops/{forb,tcgar}/*.jpg and samples.json
  gallery  the full EN pack gallery as 224x224 uint8 RGB (composited on white, like the app) for the real
           validation during training -> <data>/real-train/gallery_en_224.npy + gallery_en_224.json

  uv run --extra litert python realset.py build
  uv run --extra litert python realset.py gallery

The images stay in <data>; nothing here is committed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

from common import DATA_DIR, Card, split_of

REAL_TRAIN = DATA_DIR / "real-train"
SAMPLES = REAL_TRAIN / "samples.json"
GALLERY_NPY = REAL_TRAIN / "gallery_en_224.npy"
GALLERY_IDS = REAL_TRAIN / "gallery_en_224.json"
PACK_WORK = DATA_DIR / "packs-work"
VAL_PERCENT = 10
SIDE = 224
# Train crops whose baseline embedding is this far from the true reference are detector failures, not hard photos
# (checked by eye on a grid of the lowest-similarity crops).
MIN_SIM_TRAIN = 0.10


def role_of(card_id: str) -> str:
    """'test' (common.split_of holdout), 'val' (10 % of the rest, own hash) or 'train'."""
    if split_of(card_id) == "test":
        return "test"
    h = int(hashlib.sha1(f"val:{card_id}".encode()).hexdigest(), 16) % 100
    return "val" if h < VAL_PERCENT else "train"


def pack_cards(lang: str = "en", work: Path = PACK_WORK) -> dict[str, Card]:
    """TCGdex cards of a language pack (with an image) as Card objects, keyed by card id. ref_path is the same file
    build_packs.py embed downloads."""
    meta = json.loads((work / lang / "metadata.json").read_text(encoding="utf-8"))
    sets = {s["id"]: s for s in meta["sets"]}
    return {c["id"]: Card(f"{lang}/{c['id']}", c["id"], lang, c["setId"], sets[c["setId"]]["name"], c["localId"],
                          int(sets[c["setId"]]["official"] or 0), c["name"], "", (), c["image"])
            for c in meta["cards"] if c["image"]}


def plan_classes(base: list[Card], extra: Iterable[Card], role=role_of) -> tuple[list[Card], list[int]]:
    """Gallery cards for training and the indices that get an ArcFace class.

    cards = base (the manifest, order kept so synthetic val/test labels stay valid) + extra cards not in base.
    A card gets a class only when its id has role 'train': test and val cards keep their reference in the
    gallery (synthetic val needs it) but are never a class.
    """
    cards = list(base)
    seen = {c.key for c in cards}
    for c in extra:
        if c.key not in seen:
            seen.add(c.key)
            cards.append(c)
    return cards, [i for i, c in enumerate(cards) if role(c.card_id) == "train"]


def real_samples(samples: list[dict], role: str, class_ids: set[str] | None = None,
                 min_sim: float = MIN_SIM_TRAIN) -> list[dict]:
    """Samples of one role. For 'train': only cards that are a class (defensive: never a test card), and without
    the detector failures (baseline similarity to the true reference below min_sim: card backs, several cards,
    background; ~2 % of the crops). Val keeps every crop, like the test split."""
    out = [s for s in samples if s["role"] == role]
    if role == "train":
        assert all(role_of(s["card_id"]) == "train" for s in out), "a non-train card in the real training samples"
        if class_ids is not None:
            out = [s for s in out if s["card_id"] in class_ids]
        out = [s for s in out if s.get("sim_ref_baseline", 1.0) >= min_sim]
    return out


def balanced_weights(card_ids: list[str]) -> np.ndarray:
    """Sampling weights so that every card is drawn equally often, whatever its number of photos."""
    n = Counter(card_ids)
    w = np.array([1.0 / n[c] for c in card_ids], np.float64)
    return w / w.sum()


# ------------------------------------------------------------------------------------------- build (heavy imports)

def _orient(crop, emb, ref_vec):
    """0 or 180: which orientation of the crop matches the true reference better (baseline embedder)."""
    import cv2
    s0 = float(emb(crop) @ ref_vec)
    s1 = float(emb(cv2.rotate(crop, cv2.ROTATE_180)) @ ref_vec)
    return (0, s0) if s0 >= s1 else (180, s1)


def forb_items(cards: dict[str, Card]):
    from eval_forb import FORB, MAP, load_gt
    mapping = json.loads(MAP.read_text())
    for g in load_gt():
        m = mapping.get(g["groundtruth_images"][0])
        if m and m["id"] and m["id"] in cards:
            yield {"source": "forb", "card_id": m["id"], "photo": str(FORB / g["query_image"]),
                   "file": f"forb/{g['query_id']}.jpg", "difficulty": g["difficulty"]}


def tcgar_items(cards: dict[str, Card]):
    from eval_tcgar import annotations, tcgdex_id
    n = Counter()
    for img_path, quad, ptcg in annotations():
        cid = tcgdex_id(ptcg, set(cards))
        n[img_path.stem] += 1
        if cid:
            yield {"source": "tcgar", "card_id": cid, "photo": str(img_path), "quad": quad.tolist(),
                   "file": f"tcgar/{img_path.stem}_{n[img_path.stem]}.jpg"}


def cmd_build(threads: int):
    import cv2

    from detect import order_quad, rectify
    from eval_real_manual import Embedder, LearnedDetector
    from eval_tcgar import guide_crop, upright
    from synth import load_ref_rgba

    cards = pack_cards()
    items = [it for it in [*forb_items(cards), *tcgar_items(cards)] if role_of(it["card_id"]) != "test"]
    print(f"{len(items)} train/val photos: {Counter((i['source'], role_of(i['card_id'])) for i in items)}")
    (REAL_TRAIN / "crops" / "forb").mkdir(parents=True, exist_ok=True)
    (REAL_TRAIN / "crops" / "tcgar").mkdir(parents=True, exist_ok=True)
    old = {s["file"]: s for s in json.loads(SAMPLES.read_text())} if SAMPLES.exists() else {}
    ref_cache: dict[str, np.ndarray] = {}

    import threading
    local = threading.local()

    def tools():
        if not hasattr(local, "emb"):
            local.emb, local.det = Embedder("mnv3_256_real"), LearnedDetector()
        return local.emb, local.det

    def one(it):
        out = REAL_TRAIN / "crops" / it["file"]
        if it["file"] in old and out.exists():
            return old[it["file"]]
        emb, det = tools()
        img = cv2.imread(it["photo"])
        if img is None:
            return None
        if it["source"] == "forb":
            h, w = img.shape[:2]
            s = 2048 / max(h, w)
            if s < 1:
                img = cv2.resize(img, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
            q, conf = det(img)
        else:
            img, quad = upright(img, np.float32(it["quad"]))
            l, t, r, b = guide_crop(img, quad)
            q, conf = det(img[t:b, l:r])
            q = order_quad(q + np.float32([l, t]))
        crop = rectify(img, q)
        cid = it["card_id"]
        if cid not in ref_cache:
            ref_cache[cid] = emb(load_ref_rgba(str(cards[cid].ref_path)))
        rot, sim = _orient(crop, emb, ref_cache[cid])
        cv2.imwrite(str(out), crop, [cv2.IMWRITE_JPEG_QUALITY, 95])
        return {"file": it["file"], "source": it["source"], "card_id": cid, "role": role_of(cid),
                "rot": rot, "sim_ref_baseline": round(sim, 4), "det_conf": round(conf, 4),
                **({"difficulty": it["difficulty"]} if "difficulty" in it else {})}

    done = []
    with ThreadPoolExecutor(threads) as ex:
        for n, r in enumerate(ex.map(one, items)):
            if r:
                done.append(r)
            if n % 500 == 0:
                print(n, flush=True)
    SAMPLES.write_text(json.dumps(done, indent=0))
    by = Counter((s["source"], s["role"]) for s in done)
    print(f"{len(done)} crops -> {SAMPLES}: {dict(by)}; train cards "
          f"{len({s['card_id'] for s in done if s['role'] == 'train'})}, val cards "
          f"{len({s['card_id'] for s in done if s['role'] == 'val'})}")


def cmd_gallery(threads: int):
    import cv2

    from synth import load_ref_rgba
    cards = [c for c in pack_cards().values() if c.ref_path.exists() and c.ref_path.stat().st_size > 0]
    REAL_TRAIN.mkdir(parents=True, exist_ok=True)
    mm = np.lib.format.open_memmap(GALLERY_NPY, mode="w+", dtype=np.uint8,
                                   shape=(len(cards), SIDE, SIDE, 3))

    def one(i):
        bgr = load_ref_rgba(str(cards[i].ref_path))
        a = bgr[..., 3:4].astype(np.float32) / 255
        bgr = (bgr[..., :3] * a + 255 * (1 - a)).astype(np.uint8)
        mm[i] = cv2.cvtColor(cv2.resize(bgr, (SIDE, SIDE), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)

    with ThreadPoolExecutor(threads) as ex:
        list(ex.map(one, range(len(cards))))
    mm.flush()
    GALLERY_IDS.write_text(json.dumps([c.card_id for c in cards]))
    print(f"{len(cards)} gallery images -> {GALLERY_NPY}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["build", "gallery"])
    ap.add_argument("--threads", type=int, default=6)
    args = ap.parse_args()
    (cmd_build if args.cmd == "build" else cmd_gallery)(args.threads)


if __name__ == "__main__":
    main()
