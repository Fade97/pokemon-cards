# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the public reference manifest (`cards.csv` + `sources.json`) from ml/manifest.csv and/or pack card files.

    cd ml && python -m dataset.export_manifest --manifest manifest.csv --out <dir>
    cd ml && python -m dataset.export_manifest --pack-cards packs-out/de/cards-3.json.gz ... --out <dir>

Only card ids, labels and image URLs go out, never images or local paths (ADR 0008/0009). `split` says how the
card was used: `train` (a trained set in ml/manifest.csv), `holdout` (an unseen set in ml/manifest.csv, index and
evaluation only) or `index` (only in a language pack's reference index, never trained). `--checksums` takes the
`downloaded.csv` of a download run and adds the SHA-256 of each image as it was when that run fetched it.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import json
import re
from pathlib import Path

from dataset.sources import SOURCES, Source, resolve

COLUMNS = ["key", "card_id", "lang", "set_id", "set_name", "local_id", "name", "category", "source", "image_url",
           "alt_url", "sha256", "alt_sha256", "split"]
SPLITS = ("train", "holdout", "index")
LANG = re.compile(r"^[a-z]{2}(?:-[a-z]{2})?$")

# Anything that looks like a path on the builder's machine. The public files hold ids, labels and https URLs only.
_PRIVATE = re.compile(r"^[A-Za-z]:[\\/]|^\\\\|^/(?:home|Users|mnt|tmp|var|root|data)/|^file:|^~[\\/]|pokemon-cards-data")


def from_manifest(path: Path) -> list[dict]:
    """Rows of ml/manifest.csv (`fetch.py` format) as public records."""
    with open(path, encoding="utf-8", newline="") as f:
        return [{
            "key": r["key"], "card_id": r["card_id"], "lang": r["lang"], "set_id": r["set_id"],
            "set_name": r["set_name"], "local_id": r["local_id"], "name": r["name"], "category": r.get("category", ""),
            "url": r["image_url"], "split": "holdout" if r.get("holdout", "0") == "1" else "train",
        } for r in csv.DictReader(f)]


def from_pack_cards(path: Path) -> list[dict]:
    """Cards with an image from a pack card file (`cards-<v>.json[.gz]`, docs/packs.md). Cards without one are skipped."""
    raw = path.read_bytes()
    doc = json.loads(gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw)
    lang = doc["lang"]
    set_names = {s["id"]: s.get("name") or "" for s in doc.get("sets", [])}
    return [{
        "key": f"{lang}/{c['id']}", "card_id": c["id"], "lang": lang, "set_id": c["setId"],
        "set_name": set_names.get(c["setId"], ""), "local_id": c["localId"], "name": c.get("name") or "",
        "category": "", "url": c["image"], "split": "index",
    } for c in doc["cards"] if c.get("image")]


def load_checksums(path: Path) -> dict[str, str]:
    """url -> sha256 from a download run's `downloaded.csv` (rows with status ok)."""
    with open(path, encoding="utf-8", newline="") as f:
        return {r["url"]: r["sha256"] for r in csv.DictReader(f) if r.get("status") in ("ok", "cached") and r["sha256"]}


def build(manifest_rows: list[dict], pack_rows: list[dict], *, langs: set[str] | None = None,
          checksums: dict[str, str] | None = None, sources: dict[str, Source] = SOURCES) -> tuple[list[dict], dict]:
    """Merges the records (a manifest row wins over a pack row for the same key, as it is what was trained on) and
    resolves every image URL to its source. Returns (rows sorted by key, {source id: source} of the used sources)."""
    merged: dict[str, dict] = {}
    for r in pack_rows:
        merged[r["key"]] = r
    for r in manifest_rows:
        if r["key"] in merged and merged[r["key"]]["url"] and not r["url"]:
            r = {**r, "url": merged[r["key"]]["url"]}
        merged[r["key"]] = r
    checksums = checksums or {}
    rows, used = [], {}
    for key in sorted(merged):
        r = merged[key]
        if langs and r["lang"] not in langs:
            continue
        if not LANG.match(r["lang"]) or key != f"{r['lang']}/{r['card_id']}":
            raise ValueError(f"bad key/lang: {key}")
        src, primary, alt = resolve(r["url"], sources)
        used[src.id] = src
        rows.append({
            "key": key, "card_id": r["card_id"], "lang": r["lang"], "set_id": r["set_id"], "set_name": r["set_name"],
            "local_id": r["local_id"], "name": r["name"], "category": r["category"], "source": src.id,
            "image_url": primary, "alt_url": alt, "sha256": checksums.get(primary, ""),
            "alt_sha256": checksums.get(alt, "") if alt else "", "split": r["split"],
        })
    check_public(rows)
    return rows, dict(sorted(used.items()))


def check_public(rows: list[dict]) -> None:
    """Raises if a value looks like a local path or a URL is not https."""
    for r in rows:
        for k, v in r.items():
            if isinstance(v, str) and _PRIVATE.search(v):
                raise ValueError(f"{r.get('key')}: {k} looks like a private path: {v!r}")
        for k in ("image_url", "alt_url"):
            if r.get(k) and not r[k].startswith("https://"):
                raise ValueError(f"{r.get('key')}: {k} is not https: {r[k]!r}")
        if r.get("split") not in SPLITS:
            raise ValueError(f"{r.get('key')}: unknown split {r.get('split')!r}")


def write(rows: list[dict], used: dict[str, Source], out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "cards.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, COLUMNS, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    (out / "sources.json").write_text(
        json.dumps({k: s.public() for k, s in used.items()}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", type=Path, help="ml/manifest.csv (trained and holdout sets)")
    ap.add_argument("--pack-cards", type=Path, action="append", default=[], help="a cards-<v>.json.gz; repeatable")
    ap.add_argument("--langs", help="comma-separated language filter, e.g. en,de")
    ap.add_argument("--checksums", type=Path, help="downloaded.csv of a download run, adds sha256 columns")
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args(argv)
    if not args.manifest and not args.pack_cards:
        ap.error("give --manifest and/or --pack-cards")
    man = from_manifest(args.manifest) if args.manifest else []
    pk = [r for p in args.pack_cards for r in from_pack_cards(p)]
    langs = set(args.langs.split(",")) if args.langs else None
    rows, used = build(man, pk, langs=langs, checksums=load_checksums(args.checksums) if args.checksums else None)
    write(rows, used, args.out)
    print(f"wrote {len(rows)} cards from {', '.join(used)} to {args.out}")


if __name__ == "__main__":
    main()
