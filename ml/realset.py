# SPDX-License-Identifier: GPL-3.0-or-later
"""Real-photo training data for the embedding: FORB and TCG-AR photos of train-split cards, cropped like the app.

Every card id gets one fixed role (`role_of`):
  test   the 20 % holdout of common.split_of. No real photo (not even a crop here) and no ArcFace class; also no
         class for a twin or reprint with the same art (`twins`). Only scored at the end (eval_forb.py /
         eval_tcgar.py). Note: a fine-tune that starts from mnv3_256_real inherits a backbone that saw the 870
         manifest cards (incl. their test-split ids) as classes; FORB/TCG-AR test cards are not among them.
  val    10 % of the remaining cards (separate hash). Treated like test during training (no photo, no class), so
         that model selection measures the same thing as the final test: real photos of cards never trained on.
  train  real photos and ArcFace class allowed.

  build    crop every train/val photo exactly like the evaluators: FORB -> learned corners on the whole photo
           (long side 2048 px); TCG-AR -> learned corners inside the app guide box around the annotated card.
           Rectify, then pick the 180-degree orientation that matches the reference better (baseline .tflite).
           -> <data>/real-train/crops/{forb,tcgar}/*.jpg and samples.json
  gallery  the full EN pack gallery as 224x224 uint8 RGB (composited on white, like the app) for the real
           validation during training -> <data>/real-train/gallery_en_224.npy + gallery_en_224.json
  twins    same-art pairs across ids (ja/SV2a <-> en/sv03.5, reprints): every class candidate whose baseline
           reference embedding has cos >= 0.9 with a test/val card -> <data>/real-train/twins.json. train.py --real
           gives such cards no class.
  stats    crop and card counts of a samples file -> results/realset_<name>.json (no images, no per-photo rows)

Production (--all-cards, issue #58): every card id has role 'train' (no test, no val, no twin blocking), so the
shipped model learns from every photo. Its crops and samples live apart in <data>/real-train/all/, so the measured
run (#57) stays reproducible. There is no held-out real set in this mode: score the recipe with the default roles.

  uv run --extra litert python realset.py gallery
  uv run --extra litert python realset.py twins      # needs packs-embcache rows of mnv3_256_real_w8
  uv run --extra litert python realset.py build
  uv run --extra litert python realset.py build --all-cards      # production: all photos, real-train/all/

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
CROPS = REAL_TRAIN / "crops"
GALLERY_NPY = REAL_TRAIN / "gallery_en_224.npy"
GALLERY_IDS = REAL_TRAIN / "gallery_en_224.json"
TWINS = REAL_TRAIN / "twins.json"
BASELINE_MODEL = "export/mnv3_256_real_w8.tflite"  # relative to DATA_DIR; crops, twins and filters use it
TWIN_COS = 0.9
CROP_VERSION = 2  # bump when the cropping changes (2: TCG-AR upright() really rotates); cached crops are redone
PACK_WORK = DATA_DIR / "packs-work"
VAL_PERCENT = 10
SIDE = 224
# Train crops whose baseline embedding is this far from the true reference are detector failures, not hard photos
# (checked by eye on a grid of the lowest-similarity crops).
MIN_SIM_TRAIN = 0.10
ALL_CARDS = False  # production mode (use_all_cards): every card is 'train'


def use_all_cards() -> None:
    """Production mode: every card id has role 'train'; crops and samples go to <data>/real-train/all/."""
    global ALL_CARDS, SAMPLES, CROPS
    ALL_CARDS = True
    SAMPLES = REAL_TRAIN / "all" / "samples.json"
    CROPS = REAL_TRAIN / "all" / "crops"


def role_of(card_id: str) -> str:
    """'test' (common.split_of holdout), 'val' (10 % of the rest, own hash) or 'train'; always 'train' in
    production mode (use_all_cards)."""
    if ALL_CARDS:
        return "train"
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


def plan_classes(base: list[Card], extra: Iterable[Card], role=role_of,
                 blocked: frozenset[str] | set[str] = frozenset()) -> tuple[list[Card], list[int]]:
    """Gallery cards for training and the indices that get an ArcFace class.

    cards = base (the manifest, order kept so synthetic val/test labels stay valid) + extra cards not in base.
    A card gets a class only when its id has role 'train' and its key is not `blocked` (a same-art twin or reprint
    of a test/val card, see find_twins): test and val cards keep their reference in the gallery (synthetic val
    needs it) but are never a class, not even under another id.
    """
    cards = list(base)
    seen = {c.key for c in cards}
    for c in extra:
        if c.key not in seen:
            seen.add(c.key)
            cards.append(c)
    return cards, [i for i, c in enumerate(cards) if role(c.card_id) == "train" and c.key not in blocked]


def find_twins(cand_keys: list[str], cand_ids: list[str], Ec: np.ndarray, other_ids: list[str], Eo: np.ndarray,
               thr: float = TWIN_COS) -> list[tuple[str, str, float | str]]:
    """(candidate key, other card id, cos) for every pair with cos >= thr and a different card id. Rows of Ec/Eo
    are L2-normalized embeddings; same id (en/de of one print) is the same card, not a twin."""
    out = []
    S = Ec @ Eo.T
    for i, j in zip(*np.nonzero(S >= thr)):
        if cand_ids[i] != other_ids[j]:
            out.append((cand_keys[i], other_ids[j], round(float(S[i, j]), 4)))
    return out


# Same set in two languages with different TCGdex ids. The baseline embedding separates languages on purpose (cos
# ~0 between JP and EN prints of one card), so these twins are matched by collector number, not by cosine.
LANGUAGE_TWIN_SETS = {"SV2a": "sv03.5"}  # ja 151 <-> en/de 151: numbers 1-180 are the same card
LANGUAGE_TWIN_SAME_NUMBER_UP_TO = 180


def language_twins(cards: list[Card]) -> list[tuple[str, str, str]]:
    """(key, other card id, 'number'/'dex') for both directions of every language-twin pair. Secret rares (above
    the printed total) are numbered differently, so there any two with the same dex ids pair up; that may block a
    few extra cards, never too few."""
    out = []
    for a_set, b_set in LANGUAGE_TWIN_SETS.items():
        A = [c for c in cards if c.set_id == a_set]
        B = [c for c in cards if c.set_id == b_set]
        for a in A:
            for b in B:
                na, nb = int(a.local_id), int(b.local_id)
                if na == nb and na <= LANGUAGE_TWIN_SAME_NUMBER_UP_TO:
                    why = "number"
                elif min(na, nb) > max(a.official, b.official) and a.dex_ids == b.dex_ids:
                    why = "dex"
                else:
                    continue
                out += [(a.key, b.card_id, why), (b.key, a.card_id, why)]
    return out


def blocked_keys(pairs: Iterable[tuple[str, str, float | str]], role=role_of) -> set[str]:
    """Candidate keys with a same-art twin whose role is test or val: they must not become a class."""
    return {k for k, other, _ in pairs if role(other) != "train"}


def load_blocked() -> set[str]:
    if not TWINS.exists():
        raise SystemExit(f"{TWINS} missing: run realset.py twins first")
    return blocked_keys(tuple(p) for p in json.loads(TWINS.read_text())["pairs"])


def real_samples(samples: list[dict], role: str, class_ids: set[str] | None = None,
                 min_sim: float = MIN_SIM_TRAIN) -> list[dict]:
    """Samples of one role; the role is recomputed from the card id (a cached samples.json may be older than the
    split). For 'train': only cards that are a class, and without detector failures: baseline similarity to the
    true reference below min_sim (card backs, several cards, background; ~2 % of the crops), or a crop whose
    baseline top-1 is another card with role test/val (the detector caught a neighbouring card). Val keeps every
    crop, like the test split."""
    out = [s for s in samples if role_of(s["card_id"]) == role]
    if role == "train":
        if class_ids is not None:
            out = [s for s in out if s["card_id"] in class_ids]
        out = [s for s in out if s.get("sim_ref_baseline", 1.0) >= min_sim
               and (s.get("top1_baseline") in (None, s["card_id"]) or role_of(s["top1_baseline"]) == "train")]
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


def baseline_gallery(lang: str = "en") -> tuple[list[str], np.ndarray]:
    """EN reference embeddings of the baseline .tflite from the build_packs.py row cache (no recompute)."""
    import packs
    sha = packs.sha256_bytes((DATA_DIR / BASELINE_MODEL).read_bytes())[:12]
    d = DATA_DIR / "packs-embcache" / sha
    if not (d / f"{lang}.json").exists():
        raise SystemExit(f"no cached {lang} rows for {BASELINE_MODEL}: run build_packs.py embed --model {BASELINE_MODEL}")
    ids = [cid for cid, _ in json.loads((d / f"{lang}.json").read_text())]
    E = np.frombuffer((d / f"{lang}.bin").read_bytes(), "<f4").reshape(len(ids), -1)
    return ids, E / np.linalg.norm(E, axis=1, keepdims=True)


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

    from detect import LearnedDetector, order_quad, rectify
    from eval_real_manual import Embedder
    from eval_tcgar import guide_crop, upright
    from synth import load_ref_rgba

    cards = pack_cards()
    items = [it for it in [*forb_items(cards), *tcgar_items(cards)] if role_of(it["card_id"]) != "test"]
    print(f"{len(items)} train/val photos: {Counter((i['source'], role_of(i['card_id'])) for i in items)}")
    (CROPS / "forb").mkdir(parents=True, exist_ok=True)
    (CROPS / "tcgar").mkdir(parents=True, exist_ok=True)
    old = {s["file"]: s for s in json.loads(SAMPLES.read_text())} if SAMPLES.exists() else {}
    ref_cache: dict[str, np.ndarray] = {}
    gal_ids, G = baseline_gallery()

    import threading
    local = threading.local()

    def tools():
        if not hasattr(local, "emb"):
            local.emb, local.det = Embedder("mnv3_256_real"), LearnedDetector()
        return local.emb, local.det

    def one(it):
        out = CROPS / it["file"]
        o = old.get(it["file"])
        # reuse only a crop of the same card made by the same cropping code (a new ground-truth map or an
        # upright() fix changes either)
        if o and out.exists() and o["card_id"] == it["card_id"] and o.get("crop_version") == CROP_VERSION:
            return {**o, "role": role_of(o["card_id"])}
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
        up = cv2.rotate(crop, cv2.ROTATE_180) if rot == 180 else crop
        top1 = gal_ids[int(np.argmax(G @ emb(up)))]
        cv2.imwrite(str(out), crop, [cv2.IMWRITE_JPEG_QUALITY, 95])
        return {"file": it["file"], "source": it["source"], "card_id": cid, "role": role_of(cid),
                "crop_version": CROP_VERSION, "rot": rot, "sim_ref_baseline": round(sim, 4),
                "top1_baseline": top1, "det_conf": round(conf, 4),
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


def cmd_twins():
    """Twins/reprints of test and val cards among all class candidates (manifest, any language, + EN pack)."""
    from common import load_manifest
    from eval_real_manual import Embedder
    from synth import load_ref_rgba
    gal_ids, G = baseline_gallery()
    pos = {c: i for i, c in enumerate(gal_ids)}
    emb = Embedder(BASELINE_MODEL.split("/")[-1].removesuffix("_w8.tflite"))
    man = load_manifest()
    Em = np.stack([G[pos[c.card_id]] if c.lang == "en" and c.card_id in pos else emb(load_ref_rgba(str(c.ref_path)))
                   for c in man])
    cand_keys = [c.key for c in man] + [f"en/{c}" for c in gal_ids]
    cand_ids = [c.card_id for c in man] + gal_ids
    Ec = np.concatenate([Em, G])
    held = [i for i, c in enumerate(cand_ids) if role_of(c) != "train"]
    pairs = find_twins(cand_keys, cand_ids, Ec, [cand_ids[i] for i in held], Ec[held])
    # language twins of the manifest; en/de prints of one card share the id, so add the en/de keys for each id too
    lang = language_twins(man)
    keys_of_id: dict[str, set[str]] = {}
    for c in man:
        keys_of_id.setdefault(c.card_id, set()).add(c.key)
    by_id = {c.key: c.card_id for c in man}
    pairs += [(k2, other, why) for k, other, why in lang if role_of(other) != "train"
              for k2 in keys_of_id[by_id[k]]]
    pairs = sorted(set(pairs), key=str)
    blocked = blocked_keys(pairs)
    TWINS.write_text(json.dumps({"model": BASELINE_MODEL, "cos": TWIN_COS, "language_twins": LANGUAGE_TWIN_SETS,
                                 "pairs": pairs}, indent=0))
    print(f"{sum(isinstance(p[2], str) for p in pairs)} language-twin and "
          f"{sum(not isinstance(p[2], str) for p in pairs)} cosine pairs; {len(pairs)} same-art pairs with a test/val card; {len(blocked)} class candidates blocked "
          f"({sum(k in blocked for k in (c.key for c in man))} of the {len(man)} manifest cards) -> {TWINS}")


def cmd_stats(samples_path: Path, name: str):
    """Counts only (no file names, no per-photo rows) -> results/realset_<name>.json."""
    from common import RESULTS_DIR
    samples = json.loads(samples_path.read_text())
    tr_all = [s for s in samples if role_of(s["card_id"]) == "train"]
    tr = real_samples(samples, "train")
    va = real_samples(samples, "val")
    out = {
        "samples": samples_path.name,
        "crops_by_source_role": {f"{k[0]}/{k[1]}": v for k, v in
                                 sorted(Counter((s["source"], role_of(s["card_id"])) for s in samples).items())},
        "train_crops_before_filter": len(tr_all), "train_crops": len(tr),
        "train_dropped_low_sim": sum(s.get("sim_ref_baseline", 1) < MIN_SIM_TRAIN for s in tr_all),
        "train_cards": len({s["card_id"] for s in tr}), "val_crops": len(va),
        "val_cards": len({s["card_id"] for s in va}),
        "test_split_crops": sum(role_of(s["card_id"]) == "test" for s in samples),
    }
    path = RESULTS_DIR / f"realset_{name}.json"
    path.write_text(json.dumps(out, indent=1))
    print(json.dumps(out, indent=1), "->", path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["build", "gallery", "twins", "stats"])
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--samples", type=Path, default=SAMPLES, help="stats: samples file")
    ap.add_argument("--name", default="current", help="stats: results/realset_<name>.json")
    ap.add_argument("--all-cards", action="store_true",
                    help="production: every card is train (no holdout); data in real-train/all/")
    args = ap.parse_args()
    if args.all_cards:
        use_all_cards()
        if args.samples == REAL_TRAIN / "samples.json":
            args.samples = SAMPLES
    if args.cmd == "twins":
        cmd_twins()
    elif args.cmd == "stats":
        cmd_stats(args.samples, args.name)
    else:
        (cmd_build if args.cmd == "build" else cmd_gallery)(args.threads)


if __name__ == "__main__":
    main()
