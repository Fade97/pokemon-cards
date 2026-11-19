# SPDX-License-Identifier: GPL-3.0-or-later
"""Export accepted user scan contributions (#14, ADR 0008) for a dataset release, under CC0.

Reads a copy of the backend's D1 `contributions` table and a local copy of the R2 objects; never the live storage.

    # 1. On a machine with wrangler (read-only on the live side):
    wrangler d1 export pokemon-cards --remote --table contributions --output contributions.sql
    python -m dataset.export_scans fetch-commands --rows contributions.sql --objects scans-r2 > fetch.sh
    sh fetch.sh                      # wrangler r2 object get ... for eligible scans only, never withdrawn ones
    # 2. Export:
    python -m dataset.export_scans export --rows contributions.sql --objects scans-r2 --out <release dir>

`--rows` takes a `wrangler d1 export` SQL dump, a SQLite file, or the JSON of
`wrangler d1 execute DB --remote --json --command "SELECT * FROM contributions"`.

A row is released only if it is stored (`stored_at`), not withdrawn (`withdrawn_at IS NULL`), approved by an admin
(`review = 'approved'`, backend #135; a dump without the column has nothing approved), has a CC0 consent
version, a well-formed card id and language, and an image whose SHA-256 matches the row. The image is walked again
with the backend's metadata rules (defence in depth; a stored image should already be clean) and must keep its
size limits. What goes out per scan: an opaque scan id, card id, language, card-level split, consent version, the
month of upload, size and SHA-256. Not the contribution id, token hash, exact time or anything else.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import shlex
import sqlite3
import sys
from dataclasses import dataclass
from pathlib import Path

from common import split_of

# Consent versions whose text releases the scan under CC0 1.0 (backend CONSENT_VERSIONS). A new consent text must be
# added here on purpose; an unknown version is never released.
CC0_CONSENTS = frozenset({"cc0-1"})
BUCKET = "pokemon-cards-contributions"
COLUMNS = ["scan_id", "file", "card_id", "lang", "split", "consent", "month", "content_type", "width", "height",
           "bytes", "sha256"]
MIN_SIDE, MAX_SIDE, MAX_BYTES = 64, 2048, 1024 * 1024
_KEY = re.compile(r"^scans/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:\.(?:jpg|webp))?$")
_CARD = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_LANG = re.compile(r"^[a-z]{2}(?:-[a-z]{2})?$")
_ISO = re.compile(r"^\d{4}-\d{2}-\d{2}T")
EXT = {"image/jpeg": ".jpg", "image/webp": ".webp"}


# ---------------------------------------------------------------------------------------------------------------
# Reading the D1 copy

def load_rows(path: Path) -> list[dict]:
    if path.suffix.lower() == ".json":
        doc = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(doc, dict):
            doc = [doc]
        rows: list[dict] = []
        for item in doc:  # wrangler: [{"results": [...], "success": true, "meta": {...}}]; or plain row dicts
            rows.extend(item["results"] if isinstance(item, dict) and "results" in item else [item])
        return rows
    if path.suffix.lower() == ".sql":
        con = sqlite3.connect(":memory:")
        con.executescript(path.read_text(encoding="utf-8"))
    else:
        con = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in con.execute("SELECT * FROM contributions")]
    finally:
        con.close()


def skip_reason(row: dict) -> str | None:
    """Why a row is not released, or None. `stored_at` is missing in dumps from before migration 0002; such rows were
    stored when they have an object key (what the migration itself assumes)."""
    if row.get("withdrawn_at"):
        return "withdrawn"
    if not row.get("object_key"):
        return "not stored"
    stored = row["stored_at"] if "stored_at" in row else row.get("created_at")
    if not stored:
        return "not stored"
    # Quarantine (#135): only what an admin approved. Dumps from before migration 0003 have no review column; none of
    # their rows were looked at, so none are released.
    if row.get("review") != "approved":
        return "not approved"
    if row.get("consent_version") not in CC0_CONSENTS:
        return "consent"
    if not _CARD.match(row.get("card_id") or "") or not _LANG.match(row.get("lang") or ""):
        return "bad label"
    if not _KEY.match(row["object_key"]):
        return "bad object key"
    if row.get("content_type") not in EXT or not _ISO.match(row.get("created_at") or ""):
        return "bad row"
    return None


def scan_id(contribution_id: str) -> str:
    """Opaque, stable public id. Stable so a later release can list what it dropped; derived, so the dataset does not
    carry the backend's row id (half of the withdraw capability)."""
    return hashlib.sha256(f"cardindex-scan:{contribution_id}".encode()).hexdigest()[:20]


# ---------------------------------------------------------------------------------------------------------------
# Metadata check: a port of backend/src/image-meta.ts (same rules, same refusals)

class ImageError(ValueError):
    pass


def sniff(b: bytes) -> str | None:
    if b[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if len(b) >= 12 and b[:4] == b"RIFF" and b[8:12] == b"WEBP":
        return "image/webp"
    return None


def strip_metadata(b: bytes, kind: str) -> tuple[bytes, int, int, list[str]]:
    """(clean bytes, width, height, names of dropped segments)."""
    return _strip_jpeg(b) if kind == "image/jpeg" else _strip_webp(b)


def _strip_jpeg(src: bytes) -> tuple[bytes, int, int, list[str]]:
    out, stripped = [src[:2]], []
    width = height = 0
    pos = 2
    while True:
        if pos + 4 > len(src) or src[pos] != 0xFF:
            raise ImageError("broken JPEG segment structure")
        marker = src[pos + 1]
        while marker == 0xFF and pos + 2 < len(src):
            pos += 1
            marker = src[pos + 1]
        if marker in (0xD8, 0xD9, 0x01) or 0xD0 <= marker <= 0xD7:
            raise ImageError("unexpected JPEG marker before the image data")
        length = (src[pos + 2] << 8) | src[pos + 3]
        end = pos + 2 + length
        if length < 2 or end > len(src):
            raise ImageError("JPEG segment runs past the end")
        is_app = 0xE0 <= marker <= 0xEF
        keep_adobe = marker == 0xEE and length == 14 and src[pos + 4:pos + 9] == b"Adobe"
        if (is_app and not keep_adobe) or marker == 0xFE:
            stripped.append("COM" if marker == 0xFE else f"APP{marker - 0xE0}")
        else:
            out.append(src[pos:end])
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            if length < 7:
                raise ImageError("short JPEG frame header")
            height = (src[pos + 5] << 8) | src[pos + 6]
            width = (src[pos + 7] << 8) | src[pos + 8]
        pos = end
        if marker == 0xDA:
            break
    if not width or not height:
        raise ImageError("JPEG without frame header")
    eoi = -1
    i = pos
    while i + 1 < len(src):
        if src[i] == 0xFF:
            m = src[i + 1]
            if m == 0xD9:
                eoi = i
                break
            if 0xE0 <= m <= 0xEF or m == 0xFE:
                raise ImageError("metadata segment between JPEG scans")
        i += 1
    if eoi < 0:
        raise ImageError("JPEG without end marker")
    out.append(src[pos:eoi + 2])
    if eoi + 2 < len(src):
        stripped.append("trailing data")
    return b"".join(out), width, height, stripped


def _u32(b: bytes, at: int) -> int:
    return int.from_bytes(b[at:at + 4], "little")


def _strip_webp(src: bytes) -> tuple[bytes, int, int, list[str]]:
    riff = _u32(src, 4)
    if riff + 8 > len(src):
        raise ImageError("WebP RIFF size runs past the end")
    end = min(len(src), riff + 8)
    chunks: list[tuple[str, bytes]] = []
    pos = 12
    while pos < end:
        if pos + 8 > end:
            raise ImageError("broken WebP chunk header")
        fourcc = src[pos:pos + 4].decode("latin-1")
        size = _u32(src, pos + 4)
        nxt = pos + 8 + size + (size & 1)
        if pos + 8 + size > end:
            raise ImageError("WebP chunk runs past the end")
        chunks.append((fourcc, src[pos:min(nxt, end)]))
        pos = nxt
    kinds = [c[0] for c in chunks]
    if "ANIM" in kinds or "ANMF" in kinds:
        raise ImageError("animated WebP")
    stripped, kept = [], []
    for fourcc, data in chunks:
        if fourcc in ("VP8X", "VP8 ", "VP8L", "ALPH"):
            kept.append([fourcc, data])
        else:
            stripped.append(fourcc.strip())
    image = next((c for c in kept if c[0] in ("VP8 ", "VP8L")), None)
    if image is None:
        raise ImageError("WebP without image data")
    width, height = _webp_size(image[0], image[1][8:])
    for c in kept:
        if c[0] == "VP8X":
            if len(c[1]) < 18:
                raise ImageError("short VP8X chunk")
            d = bytearray(c[1])
            d[8] &= 0x10  # keep only the alpha flag
            c[1] = bytes(d)
            width = 1 + int.from_bytes(d[12:15], "little")
            height = 1 + int.from_bytes(d[15:18], "little")
    body = b"".join(c[1] for c in kept)
    return src[:4] + (len(body) + 4).to_bytes(4, "little") + src[8:12] + body, width, height, stripped


def _webp_size(fourcc: str, d: bytes) -> tuple[int, int]:
    if fourcc == "VP8 ":
        if len(d) < 10 or d[3:6] != b"\x9d\x01\x2a":
            raise ImageError("bad VP8 header")
        return (d[6] | (d[7] << 8)) & 0x3FFF, (d[8] | (d[9] << 8)) & 0x3FFF
    if len(d) < 5 or d[0] != 0x2F:
        raise ImageError("bad VP8L header")
    bits = int.from_bytes(d[1:5], "little")
    return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1


# ---------------------------------------------------------------------------------------------------------------
# Export

@dataclass
class Result:
    rows: list[dict]
    skipped: dict[str, int]
    restripped: int
    withdrawn_since_previous: list[str]


def export(rows: list[dict], objects: Path, out: Path | None, *, previous: set[str] | None = None) -> Result:
    """Selects, checks and (with `out`) writes `scans/`, `scans.csv` and `withdrawn.csv` into `out`."""
    skipped: dict[str, int] = {}
    released: list[tuple[dict, bytes]] = []
    restripped = 0

    def skip(reason: str) -> None:
        skipped[reason] = skipped.get(reason, 0) + 1

    for row in rows:
        reason = skip_reason(row)
        if reason:
            skip(reason)
            continue
        path = objects / row["object_key"]
        if not path.is_file():
            skip("missing object")
            continue
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != row.get("sha256"):
            skip("checksum mismatch")
            continue
        kind = sniff(raw)
        if kind != row["content_type"]:
            skip("type mismatch")
            continue
        try:
            clean, w, h, dropped = strip_metadata(raw, kind)
        except ImageError:
            skip("bad image")
            continue
        if dropped:
            restripped += 1
        if not (MIN_SIDE <= w <= MAX_SIDE and MIN_SIDE <= h <= MAX_SIDE) or len(clean) > MAX_BYTES:
            skip("bad size")
            continue
        sid = scan_id(row["id"])
        released.append(({
            "scan_id": sid, "file": f"scans/{sid}{EXT[kind]}", "card_id": row["card_id"], "lang": row["lang"],
            "split": split_of(row["card_id"]), "consent": row["consent_version"], "month": row["created_at"][:7],
            "content_type": kind, "width": w, "height": h, "bytes": len(clean),
            "sha256": hashlib.sha256(clean).hexdigest(),
        }, clean))
    released.sort(key=lambda x: x[0]["scan_id"])  # not upload order
    ids = {r["scan_id"] for r, _ in released}
    gone = sorted((previous or set()) - ids)
    if out is not None:
        (out / "scans").mkdir(parents=True, exist_ok=True)
        for r, data in released:
            (out / r["file"]).write_bytes(data)
        with open(out / "scans.csv", "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, COLUMNS, lineterminator="\n")
            w.writeheader()
            w.writerows(r for r, _ in released)
        with open(out / "withdrawn.csv", "w", encoding="utf-8", newline="") as f:
            f.write("scan_id\n" + "".join(f"{s}\n" for s in gone))
    return Result([r for r, _ in released], dict(sorted(skipped.items())), restripped, gone)


def previous_ids(release_dir: Path) -> set[str]:
    """Scan ids of an earlier release, plus the ones it had already listed as withdrawn (they stay listed)."""
    ids: set[str] = set()
    for name in ("scans.csv", "withdrawn.csv"):
        p = release_dir / name
        if p.exists():
            with open(p, encoding="utf-8", newline="") as f:
                ids |= {r["scan_id"] for r in csv.DictReader(f)}
    return ids


def fetch_commands(rows: list[dict], objects: Path, *, bucket: str = BUCKET) -> list[str]:
    """`wrangler r2 object get` lines for the eligible rows whose object is not in `objects` yet (read-only)."""
    cmds = []
    for row in rows:
        if skip_reason(row) is None and not (objects / row["object_key"]).is_file():
            dst = (objects / row["object_key"]).as_posix()
            cmds.append(f"wrangler r2 object get {shlex.quote(bucket + '/' + row['object_key'])} "
                        f"--file {shlex.quote(dst)} --remote")
    return cmds


def review_html(rows: list[dict], release_dir: Path) -> str:
    """A contact sheet for the manual look before a release (hands, faces, backgrounds, wrong labels)."""
    cells = "".join(
        f'<figure><img src="{(release_dir / r["file"]).resolve().as_uri()}" loading="lazy">'
        f'<figcaption>{r["lang"]}/{r["card_id"]}<br><small>{r["scan_id"]}</small></figcaption></figure>'
        for r in rows)
    return ("<!doctype html><meta charset=utf-8><title>Scan review</title><style>body{font:14px sans-serif;"
            "display:flex;flex-wrap:wrap;gap:8px}figure{margin:0;width:180px}img{width:180px}</style>" + cells)


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("export", "fetch-commands"):
        p = sub.add_parser(name)
        p.add_argument("--rows", type=Path, required=True, help="D1 copy: .sql dump, SQLite file or wrangler .json")
        p.add_argument("--objects", type=Path, required=True, help="local copy of the R2 bucket (keys scans/<id>)")
        if name == "export":
            p.add_argument("--out", type=Path, required=True)
            p.add_argument("--previous", type=Path, help="directory of the previous release, for withdrawn.csv")
    args = ap.parse_args(argv)
    rows = load_rows(args.rows)
    if args.cmd == "fetch-commands":
        print("\n".join(fetch_commands(rows, args.objects)))
        return
    res = export(rows, args.objects, args.out, previous=previous_ids(args.previous) if args.previous else None)
    print(f"released {len(res.rows)} scans; skipped {res.skipped}; metadata stripped again in {res.restripped}; "
          f"{len(res.withdrawn_since_previous)} dropped since the previous release", file=sys.stderr)


if __name__ == "__main__":
    main()
