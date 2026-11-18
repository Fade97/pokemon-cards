# SPDX-License-Identifier: GPL-3.0-or-later
"""Assemble one open dataset release directory (#20). Builds files only; it uploads and publishes nothing.

    cd ml && python -m dataset.release --version 2026.11.0 --out ../dataset-release/v2026.11.0 \\
        --manifest manifest.csv [--pack-cards packs-out/en/cards-3.json.gz ...] [--checksums images/downloaded.csv] \\
        [--rows contributions.sql --objects scans-r2] [--previous ../dataset-release/v2026.10.0]

Layout of the release (docs/dataset.md):

    README.md        dataset card, generated from dataset/README.template.md
    cards.csv        reference cards: ids, labels, image URLs + source, split (no images)
    sources.json     per image source: owner, terms, credit, rate limit
    download.py      rebuilds the reference images from cards.csv
    LICENSES/        CC0 notice for the scans, TCGdex's MIT licence for the card data
    scans.csv        user scans (CC0): labels and checksums
    scans/           the scan crops themselves (CC0)
    withdrawn.csv    scan ids of earlier releases that this one drops
    stats.json       the numbers in the README
    release.json     name, version, date, schema version, file counts
    SHA256SUMS       of every file above (sha256sum -c SHA256SUMS)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import string
import sys
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from dataset import export_manifest, export_scans

HERE = Path(__file__).resolve().parent
SCHEMA_VERSION = 1
DEFAULT_NAME = "cardindex-open-dataset"  # working title; the final name is Fabian's decision (#15, #20)


def stats(cards: list[dict], scans: list[dict], skipped: dict[str, int]) -> dict:
    by_lang = Counter(c["lang"] for c in cards)
    return {
        "cards": len(cards),
        "cardsByLang": dict(sorted(by_lang.items())),
        "cardsBySource": dict(sorted(Counter(c["source"] for c in cards).items())),
        "cardsBySplit": dict(sorted(Counter(c["split"] for c in cards).items())),
        "setsByLang": {lang: len({c["set_id"] for c in cards if c["lang"] == lang}) for lang in sorted(by_lang)},
        "cardsWithChecksum": sum(1 for c in cards if c["sha256"]),
        "scans": len(scans),
        "scansByLang": dict(sorted(Counter(s["lang"] for s in scans).items())),
        "scansBySplit": dict(sorted(Counter(s["split"] for s in scans).items())),
        "scanCards": len({(s["lang"], s["card_id"]) for s in scans}),
        "scansNotReleased": skipped,
    }


def _table(head: tuple[str, ...], rows: list[tuple]) -> str:
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    lines += ["| " + " | ".join(str(x) for x in r) + " |" for r in rows]
    return "\n".join(lines)


def render_card(name: str, version: str, date: str, st: dict, sources: dict, *, homepage: str, doi: str) -> str:
    langs = sorted(set(st["cardsByLang"]) | set(st["scansByLang"]))
    lang_table = _table(("Language", "Reference cards", "Sets", "User scans"),
                        [(lang, st["cardsByLang"].get(lang, 0), st["setsByLang"].get(lang, 0),
                          st["scansByLang"].get(lang, 0)) for lang in langs])
    source_table = _table(("Source", "Cards", "Images", "Credit"),
                          [(f"[{s.name}]({s.homepage}) (`{sid}`)", st["cardsBySource"].get(sid, 0), s.images, s.credit)
                           for sid, s in sources.items()])
    split_table = _table(("Split", "Reference cards", "User scans"),
                         [(k, st["cardsBySplit"].get(k, 0), st["scansBySplit"].get(k, 0))
                          for k in ("train", "holdout", "index", "test") if st["cardsBySplit"].get(k) or
                          st["scansBySplit"].get(k)])
    tpl = string.Template((HERE / "README.template.md").read_text(encoding="utf-8"))
    return tpl.substitute(
        name=name, version=version, date=date, homepage=homepage or "(homepage to be decided)",
        doi=doi or "(no DOI yet)", year=date[:4], cards=st["cards"], scans=st["scans"], scan_cards=st["scanCards"],
        lang_table=lang_table, source_table=source_table, split_table=split_table,
        with_checksum=st["cardsWithChecksum"], schema=SCHEMA_VERSION)


def sha256sums(root: Path) -> str:
    lines = []
    for p in sorted(x for x in root.rglob("*") if x.is_file() and x.name != "SHA256SUMS"):
        lines.append(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(root).as_posix()}")
    return "\n".join(lines) + "\n"


def build(out: Path, *, version: str, name: str = DEFAULT_NAME, manifest: Path | None = None,
          pack_cards: list[Path] = (), checksums: Path | None = None, langs: set[str] | None = None,
          rows: Path | None = None, objects: Path | None = None, previous: Path | None = None,
          homepage: str = "", doi: str = "", today: str | None = None) -> dict:
    if out.exists() and any(out.iterdir()):
        raise SystemExit(f"{out} is not empty; a release directory is written once")
    prev = export_scans.previous_ids(previous) if previous else None
    if rows is None and prev:
        raise SystemExit("the previous release has scans; without --rows/--objects this one would list them all as "
                         "withdrawn")
    out.mkdir(parents=True, exist_ok=True)
    date = today or datetime.now(UTC).strftime("%Y-%m-%d")

    man = export_manifest.from_manifest(manifest) if manifest else []
    pk = [r for p in pack_cards for r in export_manifest.from_pack_cards(p)]
    cards, used = export_manifest.build(
        man, pk, langs=langs, checksums=export_manifest.load_checksums(checksums) if checksums else None)
    export_manifest.write(cards, used, out)

    if rows is not None:
        res = export_scans.export(export_scans.load_rows(rows), objects, out, previous=prev)
    else:  # no contributions yet: empty scans.csv and withdrawn.csv
        res = export_scans.export([], Path("."), out)
    st = stats(cards, res.rows, res.skipped)
    (out / "stats.json").write_text(json.dumps(st, indent=2) + "\n", encoding="utf-8")
    shutil.copyfile(HERE / "download.py", out / "download.py")
    (out / "LICENSES").mkdir()
    for lic in sorted((HERE / "licenses").glob("*.txt")):
        shutil.copyfile(lic, out / "LICENSES" / lic.name)
    (out / "README.md").write_text(render_card(name, version, date, st, used, homepage=homepage, doi=doi),
                                   encoding="utf-8", newline="\n")
    meta = {"name": name, "version": version, "date": date, "schemaVersion": SCHEMA_VERSION,
            "cards": st["cards"], "scans": st["scans"], "withdrawnSincePrevious": len(res.withdrawn_since_previous),
            "licenses": {"scans": "CC0-1.0", "cards.csv": "MIT (TCGdex data); added columns CC0-1.0",
                         "sources.json": "CC0-1.0", "download.py": "GPL-3.0-or-later",
                         "referenceImages": "not included; see sources.json"}}
    (out / "release.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    (out / "SHA256SUMS").write_text(sha256sums(out), encoding="utf-8", newline="\n")
    if res.rows:
        review = out.with_name(out.name + ".review.html")  # outside the release: for the maintainer only
        review.write_text(export_scans.review_html(res.rows, out), encoding="utf-8")
        print(f"look through {review} before publishing", file=sys.stderr)
    print(f"{out}: {st['cards']} cards, {st['scans']} scans; scans not released: {res.skipped}; metadata stripped "
          f"again in {res.restripped}; dropped since previous: {len(res.withdrawn_since_previous)}", file=sys.stderr)
    return meta


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, required=True, help="new, empty release directory")
    ap.add_argument("--version", required=True, help="release version, e.g. 2026.11.0")
    ap.add_argument("--name", default=DEFAULT_NAME)
    ap.add_argument("--manifest", type=Path)
    ap.add_argument("--pack-cards", type=Path, action="append", default=[])
    ap.add_argument("--checksums", type=Path, help="downloaded.csv of a download run")
    ap.add_argument("--langs")
    ap.add_argument("--rows", type=Path, help="D1 copy of contributions (see dataset/export_scans.py)")
    ap.add_argument("--objects", type=Path, help="local copy of the contribution objects")
    ap.add_argument("--previous", type=Path, help="previous release directory")
    ap.add_argument("--homepage", default="")
    ap.add_argument("--doi", default="")
    args = ap.parse_args(argv)
    if not args.manifest and not args.pack_cards:
        ap.error("give --manifest and/or --pack-cards")
    if (args.rows is None) != (args.objects is None):
        ap.error("--rows and --objects go together")
    build(args.out, version=args.version, name=args.name, manifest=args.manifest, pack_cards=args.pack_cards,
          checksums=args.checksums, langs=set(args.langs.split(",")) if args.langs else None, rows=args.rows,
          objects=args.objects, previous=args.previous, homepage=args.homepage, doi=args.doi)


if __name__ == "__main__":
    main()
