# SPDX-License-Identifier: GPL-3.0-or-later
"""The labelled real-photo set for finish detection (issue #144): loader and coverage summary.

The set is private like real-photos: it lives in <data>/real-photos-finish/, never in the repo. Each card is one
*burst* folder recorded with the app's Lab ("Details" -> "Finish burst"): a few live frames while the card is
tilted, the rectified crop of each, and burst.json with the labels:

  <root>/<burst id>/burst.json
  <root>/<burst id>/frame-00.jpg ...   the analysed camera frame (about 1280 x 960)
  <root>/<burst id>/crop-00.jpg ...    the card warped upright to 504 x 704

  burst.json  {"format": 1, "id": "...", "cardId": "sv03.5-006", "lang": "en",
               "finish": "normal|holo|reverse", "holder": "bare|penny|double|toploader|binder",
               "light": "room|daylight|torch", "torch": true, "createdAt": "...", "device": "...", "app": "...",
               "frames": [{"frame": "frame-00.jpg", "crop": "crop-00.jpg", "quad": [[x, y] x4: TL TR BR BL],
                           "width": 1280, "height": 960, "similarity": 0.81, "atMs": 0}, ...]}

The card id is what the app recognized while recording. Corrections go into <root>/overrides.json instead of
editing a burst: {"<burst id>": {"cardId": "...", "finish": "..."}, "<burst id>": null} (null drops the burst).

  adb pull /sdcard/Android/data/de.fabianduerkop.foilark.debug/files/finish-bursts/. <data>/real-photos-finish/
  python finish_set.py summary                 # coverage against the targets of #144
  python finish_set.py summary --root <dir> --json

Standard library only.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from common import DATA_DIR

FORMAT = 1
FINISHES = ("normal", "holo", "reverse")
HOLDERS = ("bare", "penny", "double", "toploader", "binder")
LIGHTS = ("room", "daylight", "torch")
# Anything over the card: these are where sleeve glare can look like foil.
COVERED = {"penny", "double", "toploader", "binder"}
DEFAULT_ROOT = DATA_DIR / "real-photos-finish"

# Acceptance criteria of #144.
MIN_PER_FINISH = 30
MIN_COVERED_SHARE = 1 / 3
MIN_NORMAL_REVERSE_PAIRS = 10


@dataclass
class Frame:
    frame: Path
    crop: Path
    quad: list[tuple[float, float]]
    width: int
    height: int
    similarity: float
    at_ms: int


@dataclass
class Burst:
    id: str
    dir: Path
    card_id: str
    lang: str
    finish: str
    holder: str
    light: str
    torch: bool
    frames: list[Frame] = field(default_factory=list)

    @property
    def set_id(self) -> str:
        return self.card_id.rsplit("-", 1)[0]


class SetError(ValueError):
    pass


def parse_burst(path: Path, override: dict | None = None) -> Burst:
    """One burst from its burst.json, with `override` (cardId/finish/holder/light) applied. Raises SetError."""
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError) as e:
        raise SetError(f"{path}: {e}") from e
    if doc.get("format") != FORMAT:
        raise SetError(f"{path}: format {doc.get('format')}, this loader reads {FORMAT}")
    doc = {**doc, **(override or {})}
    for key, allowed in (("finish", FINISHES), ("holder", HOLDERS), ("light", LIGHTS)):
        if doc.get(key) not in allowed:
            raise SetError(f"{path}: {key} {doc.get(key)!r} is not one of {', '.join(allowed)}")
    if not doc.get("cardId") or not doc.get("lang"):
        raise SetError(f"{path}: no cardId or lang")
    frames = []
    for f in doc.get("frames") or []:
        frame, crop = path.parent / f["frame"], path.parent / f["crop"]
        missing = [p.name for p in (frame, crop) if not p.is_file()]
        if missing:
            raise SetError(f"{path}: missing {', '.join(missing)}")
        quad = [(float(x), float(y)) for x, y in f["quad"]]
        if len(quad) != 4:
            raise SetError(f"{path}: {f['frame']} has {len(quad)} corners")
        frames.append(Frame(frame, crop, quad, int(f["width"]), int(f["height"]), float(f["similarity"]),
                            int(f["atMs"])))
    if not frames:
        raise SetError(f"{path}: no frames")
    return Burst(doc.get("id") or path.parent.name, path.parent, doc["cardId"], doc["lang"], doc["finish"],
                 doc["holder"], doc["light"], bool(doc.get("torch")), frames)


def load(root: Path = DEFAULT_ROOT, *, strict: bool = False) -> tuple[list[Burst], list[str]]:
    """Every burst below `root`, sorted by id, and the problems found. A folder without burst.json is a burst that
    was cancelled or is still being pulled, so it is skipped with a note; with `strict` any problem raises."""
    overrides_path = root / "overrides.json"
    overrides = json.loads(overrides_path.read_text(encoding="utf-8")) if overrides_path.exists() else {}
    dirs = sorted(p for p in root.iterdir() if p.is_dir()) if root.is_dir() else []
    bursts, problems = [], []
    for d in dirs:
        if d.name in overrides and overrides[d.name] is None:
            continue
        path = d / "burst.json"
        try:
            if not path.exists():
                raise SetError(f"{d}: no burst.json (cancelled or incomplete)")
            bursts.append(parse_burst(path, overrides.get(d.name)))
        except SetError as e:
            if strict:
                raise
            problems.append(str(e))
    problems += [f"overrides.json: no burst {name}" for name in sorted(set(overrides) - {d.name for d in dirs})]
    return sorted(bursts, key=lambda b: b.id), problems


def summary(bursts: list[Burst]) -> dict:
    """Coverage of the set against the targets of #144."""
    per_finish = Counter(b.finish for b in bursts)
    covered = Counter(b.finish for b in bursts if b.holder in COVERED)
    finishes_of: dict[tuple[str, str], set[str]] = {}
    for b in bursts:
        finishes_of.setdefault((b.lang, b.card_id), set()).add(b.finish)
    pairs = sorted(f"{lang}/{cid}" for (lang, cid), f in finishes_of.items() if {"normal", "reverse"} <= f)
    holo_reverse = sorted(f"{lang}/{cid}" for (lang, cid), f in finishes_of.items() if {"holo", "reverse"} <= f)
    checks = {
        f"at least {MIN_PER_FINISH} cards per finish": all(per_finish[f] >= MIN_PER_FINISH for f in FINISHES),
        "at least a third of each finish covered (sleeve, toploader, binder)": all(
            per_finish[f] and covered[f] / per_finish[f] >= MIN_COVERED_SHARE for f in FINISHES),
        f"at least {MIN_NORMAL_REVERSE_PAIRS} normal/reverse pairs": len(pairs) >= MIN_NORMAL_REVERSE_PAIRS,
    }
    return {
        "bursts": len(bursts),
        "frames": sum(len(b.frames) for b in bursts),
        "perFinish": {f: per_finish[f] for f in FINISHES},
        "coveredPerFinish": {f: covered[f] for f in FINISHES},
        "perHolder": dict(Counter(b.holder for b in bursts)),
        "perLight": dict(Counter(b.light for b in bursts)),
        "perLang": dict(Counter(b.lang for b in bursts)),
        "sets": len({b.set_id for b in bursts}),
        "normalReversePairs": pairs,
        "holoReversePairs": holo_reverse,
        "checks": checks,
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("summary", help="coverage of the set against the targets of #144")
    p.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    p.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    bursts, problems = load(args.root)
    s = summary(bursts)
    if args.json:
        print(json.dumps({**s, "problems": problems}, indent=1))
    else:
        print(f"{args.root}: {s['bursts']} bursts, {s['frames']} frames, {s['sets']} sets")
        for f in FINISHES:
            print(f"  {f:8} {s['perFinish'][f]:4} cards, {s['coveredPerFinish'][f]:4} covered")
        print(f"  holders {s['perHolder']}  light {s['perLight']}  languages {s['perLang']}")
        print(f"  normal/reverse pairs {len(s['normalReversePairs'])}, holo/reverse pairs {len(s['holoReversePairs'])}")
        for name, ok in s["checks"].items():
            print(f"  [{'x' if ok else ' '}] {name}")
        for problem in problems:
            print(f"  ! {problem}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
