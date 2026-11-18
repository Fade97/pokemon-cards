# SPDX-License-Identifier: GPL-3.0-or-later
"""Open dataset release tooling (#20): manifest export, download script, user scan export. CPU, stdlib, no network:

    cd ml && uv run --no-project --with pytest pytest tests/test_dataset.py
"""
from __future__ import annotations

import csv
import gzip
import hashlib
import http.server
import json
import sqlite3
import subprocess
import sys
import threading
from pathlib import Path

import pytest

ML = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ML))  # the ml/ scripts import each other as top-level modules

from dataset import download, export_manifest, export_scans, release, sources  # noqa: E402

TCGDEX = "https://assets.tcgdex.net"
LIMITLESS = "https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpci"


# ---------------------------------------------------------------------------------------------------------------
# Fixtures

def write_manifest(path: Path) -> Path:
    rows = [
        ("en/sv03.5-001", "sv03.5-001", "en", "sv03.5", "151", "001", "Bulbasaur", "Pokemon",
         f"{TCGDEX}/en/sv/sv03.5/001", "0"),
        ("de/30th-001", "30th-001", "de", "30th", "30 Jahre", "001", "Bisasam", "Pokemon",
         f"{TCGDEX}/de/sv/30th/001", "1"),
        ("en/mee-001", "mee-001", "en", "mee", "Mega Evolution Energy", "001", "Grass Energy", "Energy",
         f"{LIMITLESS}/MEE/MEE_001_R_EN_LG.png", "1"),
    ]
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["key", "card_id", "lang", "set_id", "set_name", "local_id", "official", "name", "category",
                    "dex_ids", "image_url", "holdout"])
        for key, cid, lang, sid, sname, lid, name, cat, url, hold in rows:
            w.writerow([key, cid, lang, sid, sname, lid, 165, name, cat, "", url, hold])
    return path


def write_pack_cards(path: Path) -> Path:
    doc = {"formatVersion": 1, "lang": "en", "version": 3,
           "sets": [{"id": "sv03.5", "name": "151"}, {"id": "mee", "name": "Mega Evolution Energy"}],
           "cards": [
               {"id": "mee-001", "setId": "mee", "localId": "001", "name": "Grass Energy", "image": None,
                "indexed": True},
               {"id": "sv03.5-001", "setId": "sv03.5", "localId": "001", "name": "Bulbasaur",
                "image": f"{TCGDEX}/en/sv/sv03.5/001", "indexed": True},
               {"id": "sv03.5-002", "setId": "sv03.5", "localId": "002", "name": "Ivysaur",
                "image": f"{TCGDEX}/en/sv/sv03.5/002", "indexed": True},
               {"id": "sv03.5-209", "setId": "sv03.5", "localId": "209", "name": "No image", "image": None,
                "indexed": False},
           ]}
    path.write_bytes(gzip.compress(json.dumps(doc).encode(), mtime=0))
    return path


def seg(marker: int, payload: bytes) -> bytes:
    return bytes([0xFF, marker]) + (len(payload) + 2).to_bytes(2, "big") + payload


def jpeg(w: int = 120, h: int = 168, *, exif: bool = False) -> bytes:
    parts = [b"\xff\xd8", seg(0xE0, b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")]
    if exif:
        parts += [seg(0xE1, b"Exif\x00\x00GPS-52.52N-13.40E"), seg(0xFE, b"Pixel 9 Pro")]
    parts += [seg(0xDB, bytes(65)),
              seg(0xC0, b"\x08" + h.to_bytes(2, "big") + w.to_bytes(2, "big") + b"\x01\x01\x11\x00"),
              seg(0xDA, b"\x01\x01\x00\x00\x3f\x00"), b"\x12\x34\x56\x78\xff\xd9"]
    return b"".join(parts)


def webp(w: int = 120, h: int = 168, *, exif: bool = False) -> bytes:
    def chunk(fourcc: bytes, data: bytes) -> bytes:
        return fourcc + len(data).to_bytes(4, "little") + data + (b"\x00" if len(data) & 1 else b"")
    flags = 0x08 if exif else 0
    vp8x = bytes([flags, 0, 0, 0]) + (w - 1).to_bytes(3, "little") + (h - 1).to_bytes(3, "little")
    bits = ((w - 1) & 0x3FFF) | (((h - 1) & 0x3FFF) << 14)
    body = chunk(b"VP8X", vp8x) + chunk(b"VP8L", b"\x2f" + bits.to_bytes(4, "little") + b"\x00\x00\x00")
    if exif:
        body += chunk(b"EXIF", b"MM\x00*GPS-52.52N")
    return b"RIFF" + (len(body) + 4).to_bytes(4, "little") + b"WEBP" + body


UUIDS = [f"0000000{i}-0000-4000-8000-00000000000{i}" for i in range(8)]


def contribution(i: int, data: bytes, **over) -> dict:
    kind = export_scans.sniff(data)
    row = {"id": UUIDS[i], "token_hash": "ab" * 32, "card_id": "sv03.5-025", "lang": "de", "consent_version": "cc0-1",
           "object_key": f"scans/{UUIDS[i]}", "content_type": kind, "size": len(data), "width": 120, "height": 168,
           "sha256": hashlib.sha256(data).hexdigest(), "created_at": f"2026-11-17T10:00:0{i}Z",
           "withdrawn_at": None, "stored_at": f"2026-11-17T10:00:0{i}Z", "review": "approved",
           "reviewed_at": "2026-11-18T09:00:00Z"}
    row.update(over)
    return row


@pytest.fixture
def backend(tmp_path: Path) -> tuple[list[dict], Path]:
    """Rows as the backend writes them (migrations applied for real) and a local copy of the R2 objects."""
    objects = tmp_path / "r2"
    (objects / "scans").mkdir(parents=True)
    images = [jpeg(exif=True), webp(exif=True), jpeg(), jpeg(), jpeg(), jpeg(), webp()]
    for i, data in enumerate(images):
        (objects / "scans" / UUIDS[i]).write_bytes(data)
    rows = [
        contribution(0, images[0], card_id="sv03.5-006"),           # released, EXIF stripped again
        contribution(1, images[1], lang="en"),                       # released (WebP), EXIF chunk stripped again
        contribution(2, images[2], withdrawn_at="2026-11-17T11:00:00Z"),
        contribution(3, images[3], stored_at=None),                  # only claimed
        contribution(4, images[4], consent_version="cc-by-1"),       # not a CC0 consent
        contribution(5, images[5], sha256="00" * 32),                # object does not match the row
        contribution(6, images[6], object_key=f"scans/{UUIDS[7]}"),  # object missing
        {**contribution(7, b"", content_type="image/jpeg"), "object_key": "", "card_id": "", "lang": "",
         "withdrawn_at": "2026-11-17T12:00:00Z", "stored_at": None, "review": "pending", "reviewed_at": None},  # tombstone
    ]
    return rows, objects


def sqlite_from_migrations(path: Path, rows: list[dict]) -> Path:
    con = sqlite3.connect(path)
    for m in sorted((ML.parent / "backend" / "migrations").glob("*.sql")):
        con.executescript(m.read_text(encoding="utf-8"))
    cols = list(rows[0])
    con.executemany(f"INSERT INTO contributions ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                    [[r[c] for c in cols] for r in rows])
    con.commit()
    con.close()
    return path


# ---------------------------------------------------------------------------------------------------------------
# Sources and manifest export

def test_resolve_tcgdex_base_and_full_urls():
    src, primary, alt = sources.resolve(f"{TCGDEX}/de/sv/sv03.5/001")
    assert (src.id, primary, alt) == ("tcgdex", f"{TCGDEX}/de/sv/sv03.5/001/high.png",
                                      f"{TCGDEX}/de/sv/sv03.5/001/high.webp")
    src, primary, alt = sources.resolve(f"{LIMITLESS}/MEE/MEE_001_R_DE_LG.png")
    assert (src.id, primary, alt) == ("limitless", f"{LIMITLESS}/MEE/MEE_001_R_DE_LG.png", "")


def test_resolve_refuses_unknown_hosts_and_http():
    with pytest.raises(sources.UnknownSource):
        sources.resolve("https://images.example.com/card.png")
    with pytest.raises(ValueError):
        sources.resolve("http://assets.tcgdex.net/en/sv/sv03.5/001")


def test_export_merges_manifest_and_pack(tmp_path):
    man = export_manifest.from_manifest(write_manifest(tmp_path / "manifest.csv"))
    pk = export_manifest.from_pack_cards(write_pack_cards(tmp_path / "cards-3.json.gz"))
    rows, used = export_manifest.build(man, pk)
    by_key = {r["key"]: r for r in rows}
    assert list(by_key) == sorted(by_key)
    assert set(by_key) == {"de/30th-001", "en/mee-001", "en/sv03.5-001", "en/sv03.5-002"}  # no image -> not listed
    assert by_key["en/sv03.5-001"]["split"] == "train"      # manifest wins over the pack
    assert by_key["de/30th-001"]["split"] == "holdout"
    assert by_key["en/sv03.5-002"]["split"] == "index"
    assert by_key["en/mee-001"]["source"] == "limitless"        # manifest URL used, pack has image null
    assert list(used) == ["limitless", "tcgdex"]


def test_export_langs_filter_and_checksums(tmp_path):
    man = export_manifest.from_manifest(write_manifest(tmp_path / "manifest.csv"))
    url = f"{TCGDEX}/de/sv/30th/001/high.png"
    rows, used = export_manifest.build(man, [], langs={"de"}, checksums={url: "ab" * 32})
    assert [r["key"] for r in rows] == ["de/30th-001"]
    assert rows[0]["sha256"] == "ab" * 32 and rows[0]["alt_sha256"] == ""
    assert list(used) == ["tcgdex"]


def test_export_writes_no_private_paths(tmp_path):
    man = export_manifest.from_manifest(write_manifest(tmp_path / "manifest.csv"))
    rows, used = export_manifest.build(man, [])
    export_manifest.write(rows, used, tmp_path / "out")
    text = (tmp_path / "out" / "cards.csv").read_text(encoding="utf-8")
    assert str(tmp_path) not in text and "pokemon-cards-data" not in text
    assert set(json.loads((tmp_path / "out" / "sources.json").read_text(encoding="utf-8"))) == {"tcgdex", "limitless"}
    bad = {**rows[0], "name": r"D:\AI\pokemon-cards-data\ref\en\x.png"}
    with pytest.raises(ValueError, match="private path"):
        export_manifest.check_public([bad])
    for path in ("c:/data/x.png", r"\\nas\share\x.png", "/opt/cards/x.png", "./ref/x.png", "../ref/x.png",
                 "~bob/x.png", "FILE:///x.png"):
        with pytest.raises(ValueError, match="private path"):
            export_manifest.check_public([{**rows[0], "name": path}])
    with pytest.raises(ValueError, match="credentials"):
        export_manifest.check_public([{**rows[0], "image_url": "https://user:token@assets.tcgdex.net/a/high.png"}])


def test_export_cli(tmp_path):
    out = tmp_path / "out"
    export_manifest.main(["--manifest", str(write_manifest(tmp_path / "m.csv")), "--out", str(out)])
    with open(out / "cards.csv", encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 3 and list(rows[0]) == export_manifest.COLUMNS


def test_real_manifest_exports():
    """The repo's own manifest goes through (every host registered, no private values)."""
    rows, used = export_manifest.build(export_manifest.from_manifest(ML / "manifest.csv"), [])
    assert rows and "tcgdex" in used


# ---------------------------------------------------------------------------------------------------------------
# Download script

class Server:
    """A local image host: robots.txt, ETags, Range/If-Range, a 429 once, 404s for fallbacks."""

    def __init__(self):
        self.files = {"/a/high.png": b"A" * 1000, "/b/high.webp": b"B" * 500, "/c/high.png": b"C" * 300,
                      "/blocked/high.png": b"X"}
        self.hits: list[tuple[str, dict]] = []
        self.flaky = {"/c/high.png"}
        self.break_range: set[str] = set()  # the next Range response for these paths breaks off mid-body
        srv = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                srv.hits.append((self.path, dict(self.headers)))
                if self.path == "/robots.txt":
                    return self._send(200, b"User-agent: *\nDisallow: /blocked/\n")
                if self.path == "/bad/high.png":
                    return self._send(400, b"")
                if self.path == "/redir/high.png":
                    self.send_response(302)
                    self.send_header("Location", "ftp://127.0.0.1/x.png")
                    self.end_headers()
                    return
                if self.path in srv.flaky:
                    srv.flaky.discard(self.path)
                    self.send_response(429)
                    self.send_header("Retry-After", "0")
                    self.end_headers()
                    return
                body = srv.files.get(self.path)
                if body is None:
                    return self._send(404, b"")
                etag = '"' + hashlib.sha256(body).hexdigest()[:8] + '"'
                rng = self.headers.get("Range")
                if rng and self.headers.get("If-Range") == etag:
                    start = int(rng.split("=")[1].rstrip("-"))
                    rest = body[start:]
                    cr = f"bytes {start}-{len(body) - 1}/{len(body)}"
                    if self.path in srv.break_range:
                        srv.break_range.discard(self.path)
                        return self._send(206, rest, etag, content_range=cr, cut=len(rest) // 2)
                    return self._send(206, rest, etag, content_range=cr)
                return self._send(200, body, etag)

            def _send(self, code, body, etag=None, content_range=None, cut=None):
                self.send_response(code)
                if etag:
                    self.send_header("ETag", etag)
                if content_range:
                    self.send_header("Content-Range", content_range)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body if cut is None else body[:cut])

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()


@pytest.fixture
def server():
    s = Server()
    yield s
    s.close()


def dl_manifest(tmp_path: Path, base: str, *, sha_a: str = "") -> tuple[Path, dict]:
    host = base.split("//")[1]
    rows = [
        {"key": "en/a-1", "card_id": "a-1", "lang": "en", "set_id": "a", "set_name": "A", "local_id": "1", "name": "A",
         "category": "", "source": "local", "image_url": f"{base}/a/high.png", "alt_url": f"{base}/a/high.webp",
         "sha256": sha_a, "alt_sha256": "", "split": "train"},
        {"key": "de/b-1", "card_id": "b-1", "lang": "de", "set_id": "b", "set_name": "B", "local_id": "1", "name": "B",
         "category": "", "source": "local", "image_url": f"{base}/b/high.png", "alt_url": f"{base}/b/high.webp",
         "sha256": "", "alt_sha256": "", "split": "index"},
        {"key": "en/c-1", "card_id": "c-1", "lang": "en", "set_id": "c", "set_name": "C", "local_id": "1", "name": "C",
         "category": "", "source": "local", "image_url": f"{base}/c/high.png", "alt_url": "", "sha256": "",
         "alt_sha256": "", "split": "holdout"},
        {"key": "en/x-1", "card_id": "x-1", "lang": "en", "set_id": "x", "set_name": "X", "local_id": "1", "name": "X",
         "category": "", "source": "local", "image_url": f"{base}/blocked/high.png", "alt_url": "", "sha256": "",
         "alt_sha256": "", "split": "index"},
        {"key": "en/z-1", "card_id": "z-1", "lang": "en", "set_id": "z", "set_name": "Z", "local_id": "1", "name": "Z",
         "category": "", "source": "nowhere", "image_url": "https://example.invalid/z.png", "alt_url": "",
         "sha256": "", "alt_sha256": "", "split": "index"},
    ]
    path = tmp_path / "cards.csv"
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, export_manifest.COLUMNS)
        w.writeheader()
        w.writerows(rows)
    terms = {"local": {"name": "Local", "hosts": [host], "images": "test", "credit": "test", "min_interval_s": 0.0}}
    return path, terms


def quiet_fetcher(**kw) -> download.Fetcher:
    """The test server is plain http on 127.0.0.1; the real default is https only."""
    return download.Fetcher(retries=2, sleep=lambda s: None, log=lambda *a: None, schemes=("http", "https"), **kw)


def test_download_end_to_end_and_resume(tmp_path, server):
    man, terms = dl_manifest(tmp_path, server.base, sha_a=hashlib.sha256(b"A" * 1000).hexdigest())
    out = tmp_path / "img"
    rep = download.run(man, out, source_terms=terms, fetcher=quiet_fetcher(), log=lambda *a: None)
    assert rep["counts"] == {"ok": 3, "robots": 1, "unknown-source": 1}
    assert (out / "en" / "a-1.png").read_bytes() == b"A" * 1000
    assert (out / "de" / "b-1.webp").read_bytes() == b"B" * 500         # fallback after a 404
    assert (out / "en" / "c-1.png").read_bytes() == b"C" * 300          # after one 429
    assert not (out / "en" / "x-1.png").exists()                        # robots.txt
    assert all("blocked" not in p for p, _ in server.hits)
    assert all(h.get("User-Agent") == download.USER_AGENT for _, h in server.hits)
    with open(out / "downloaded.csv", encoding="utf-8", newline="") as f:
        rec = {r["key"]: r for r in csv.DictReader(f)}
    assert rec["de/b-1"]["path"] == "de/b-1.webp" and rec["de/b-1"]["status"] == "ok"

    # Second run: everything complete is skipped without a request.
    server.hits.clear()
    rep = download.run(man, out, source_terms=terms, fetcher=quiet_fetcher(), log=lambda *a: None)
    assert rep["counts"]["cached"] == 3
    assert [p for p, _ in server.hits if p != "/robots.txt"] == []


def test_download_resumes_a_partial_file_with_range(tmp_path, server):
    man, terms = dl_manifest(tmp_path, server.base)
    out = tmp_path / "img"
    (out / "en").mkdir(parents=True)
    body = server.files["/a/high.png"]
    etag = '"' + hashlib.sha256(body).hexdigest()[:8] + '"'
    (out / "en" / "a-1.png.part").write_bytes(body[:400])
    (out / "en" / "a-1.png.part.json").write_text(json.dumps({"url": f"{server.base}/a/high.png", "etag": etag}))
    download.run(man, out, source_terms=terms, fetcher=quiet_fetcher(), log=lambda *a: None, limit=1)
    assert (out / "en" / "a-1.png").read_bytes() == body
    assert not (out / "en" / "a-1.png.part").exists()
    sent = [h for p, h in server.hits if p == "/a/high.png"]
    assert sent[-1].get("Range") == "bytes=400-"


def write_rows(path: Path, rows: list[dict]) -> Path:
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, export_manifest.COLUMNS, restval="")
        w.writeheader()
        w.writerows(rows)
    return path


def card(cid: str, url: str, alt: str = "", source: str = "local") -> dict:
    return {"key": f"en/{cid}", "card_id": cid, "lang": "en", "set_id": "s", "set_name": "S", "local_id": "1",
            "name": cid, "category": "", "source": source, "image_url": url, "alt_url": alt, "split": "index"}


def test_download_resume_survives_a_body_that_breaks_off(tmp_path, server):
    """A resumed (206) body that breaks off is retried with the same Range: the bytes the failed attempt appended
    must be cut, not kept in front of the second copy."""
    man, terms = dl_manifest(tmp_path, server.base)
    out = tmp_path / "img"
    (out / "en").mkdir(parents=True)
    body = server.files["/a/high.png"]
    etag = '"' + hashlib.sha256(body).hexdigest()[:8] + '"'
    (out / "en" / "a-1.png.part").write_bytes(body[:400])
    (out / "en" / "a-1.png.part.json").write_text(json.dumps({"url": f"{server.base}/a/high.png", "etag": etag}))
    server.break_range.add("/a/high.png")
    rep = download.run(man, out, source_terms=terms, fetcher=quiet_fetcher(), log=lambda *a: None, limit=1)
    assert rep["counts"] == {"ok": 1}
    assert (out / "en" / "a-1.png").read_bytes() == body
    assert [h.get("Range") for p, h in server.hits if p == "/a/high.png"] == ["bytes=400-", "bytes=400-"]


def test_download_one_failing_card_does_not_stop_the_run(tmp_path, server):
    man = write_rows(tmp_path / "cards.csv", [
        card("bad-1", f"{server.base}/bad/high.png"),        # 400: not retried, not fatal
        card("redir-1", f"{server.base}/redir/high.png"),    # redirect to ftp:// is refused
        card("a-1", f"{server.base}/a/high.png"),
    ])
    terms = {"local": {"name": "L", "hosts": [server.base.split("//")[1]], "images": "", "credit": "",
                       "min_interval_s": 0.0}}
    rep = download.run(man, tmp_path / "img", source_terms=terms, fetcher=quiet_fetcher(), log=lambda *a: None)
    assert rep["counts"] == {"error": 2, "ok": 1}
    assert (tmp_path / "img" / "en" / "a-1.png").read_bytes() == server.files["/a/high.png"]


def test_download_fetches_only_https_on_the_source_hosts(tmp_path, server):
    secret = tmp_path / "secret.png"
    secret.write_bytes(b"private")
    host = server.base.split("//")[1]
    man = write_rows(tmp_path / "cards.csv", [
        card("file-1", secret.as_uri()),
        card("http-1", f"{server.base}/a/high.png"),                    # plain http with the default fetcher
        card("other-1", "https://elsewhere.example/a.png"),             # not a host of the source
        card("alt-1", f"https://{host}/a/high.png", alt=secret.as_uri()),
    ])
    terms = {"local": {"name": "L", "hosts": [host], "images": "", "credit": "", "min_interval_s": 0.0}}
    f = download.Fetcher(retries=0, sleep=lambda s: None, log=lambda *a: None)
    out = tmp_path / "img"
    rep = download.run(man, out, source_terms=terms, fetcher=f, log=lambda *a: None)
    assert rep["counts"] == {"bad-url": 4}
    assert server.hits == [] and not list(out.rglob("*.png"))
    with pytest.raises(download.UnsafeRedirect):
        f.get(secret.as_uri())


def test_download_reports_checksum_mismatch(tmp_path, server):
    man, terms = dl_manifest(tmp_path, server.base, sha_a="00" * 32)
    rep = download.run(man, tmp_path / "img", source_terms=terms, fetcher=quiet_fetcher(), log=lambda *a: None,
                       limit=1)
    assert rep["counts"] == {"mismatch": 1}
    assert (tmp_path / "img" / "en" / "a-1.png").exists()  # kept, only reported


def test_download_keeps_the_per_host_interval(tmp_path, server):
    man, terms = dl_manifest(tmp_path, server.base)
    terms["local"]["min_interval_s"] = 2.0
    now = [0.0]
    slept: list[float] = []

    def sleep(s):
        slept.append(s)
        now[0] += s

    f = download.Fetcher(retries=2, clock=lambda: now[0], sleep=sleep, log=lambda *a: None, schemes=("http",))
    download.run(man, tmp_path / "img", source_terms=terms, fetcher=f, log=lambda *a: None, langs=["en"])
    # robots.txt, a, c (429), c again, blocked is not requested: no time passes otherwise, so every request after
    # the first waits the full 2 s (the 429's Retry-After: 0 adds a zero sleep).
    assert [s for s in slept if s] == [2.0, 2.0, 2.0]


def test_download_dry_run_and_filters(tmp_path, server):
    man, terms = dl_manifest(tmp_path, server.base)
    rep = download.run(man, tmp_path / "img", source_terms=terms, fetcher=quiet_fetcher(), log=lambda *a: None,
                       dry_run=True, splits=["index"], langs=["en"])
    assert rep == {"planned": 2}
    assert not (tmp_path / "img").exists() and server.hits == []


def test_download_target_path_is_sanitised(tmp_path):
    p = download.target_path(tmp_path, {"lang": "en", "card_id": "../../etc/passwd"}, "https://h/x/high.png")
    assert p.parent == tmp_path / "en" and p.suffix == ".png"
    for url in ("https://h/x/a.png:stream", "https://h/x/a.png%2F..%2Fx", "https://h/x/a"):
        assert download.target_path(tmp_path, {"lang": "en", "card_id": "a"}, url).name == "a.img"
    with pytest.raises(ValueError):
        download.target_path(tmp_path, {"lang": "../x", "card_id": "a"}, "https://h/a.png")


def test_download_script_is_standalone():
    """download.py ships inside the release: standard library only, no imports from the repo."""
    src = (ML / "dataset" / "download.py").read_text(encoding="utf-8")
    imports = {line.split()[1].split(".")[0] for line in src.splitlines() if line.startswith(("import ", "from "))}
    assert imports <= {"__future__", "argparse", "csv", "hashlib", "http", "json", "os", "re", "sys", "time",
                       "urllib", "pathlib"}
    out = subprocess.run([sys.executable, "-I", str(ML / "dataset" / "download.py"), "--help"], capture_output=True,
                         text=True, check=True)
    assert "Rebuild the reference images" in out.stdout


# ---------------------------------------------------------------------------------------------------------------
# User scans

def test_strip_metadata_matches_backend_rules():
    clean, w, h, dropped = export_scans.strip_metadata(jpeg(exif=True), "image/jpeg")
    assert (w, h) == (120, 168) and dropped == ["APP0", "APP1", "COM"]
    assert b"Exif" not in clean and b"GPS" not in clean and b"Pixel" not in clean and b"JFIF" not in clean
    assert export_scans.strip_metadata(clean, "image/jpeg")[3] == []    # idempotent
    clean, w, h, dropped = export_scans.strip_metadata(webp(exif=True), "image/webp")
    assert (w, h) == (120, 168) and dropped == ["EXIF"] and b"GPS" not in clean
    assert clean[20] & 0x08 == 0                                        # VP8X EXIF flag cleared
    assert int.from_bytes(clean[4:8], "little") == len(clean) - 8
    with pytest.raises(export_scans.ImageError):
        export_scans.strip_metadata(jpeg()[:-2], "image/jpeg")


def test_scan_export_releases_only_accepted_scans(tmp_path, backend):
    rows, objects = backend
    res = export_scans.export(rows, objects, tmp_path / "rel")
    assert sorted((r["card_id"], r["lang"]) for r in res.rows) == [("sv03.5-006", "de"), ("sv03.5-025", "en")]
    assert res.skipped == {"checksum mismatch": 1, "consent": 1, "missing object": 1, "not stored": 1, "withdrawn": 2}
    assert res.restripped == 2
    for r in res.rows:
        data = (tmp_path / "rel" / r["file"]).read_bytes()
        assert hashlib.sha256(data).hexdigest() == r["sha256"] and len(data) == r["bytes"]
        assert b"GPS" not in data and b"Exif" not in data and b"EXIF" not in data
        assert r["month"] == "2026-11" and r["consent"] == "cc0-1" and r["split"] in ("train", "test")
    published = (tmp_path / "rel" / "scans.csv").read_text(encoding="utf-8")
    for u in UUIDS:
        assert u not in published                       # no backend ids, token hashes or exact times
    assert "ab" * 32 not in published and "T10:00" not in published
    assert sorted(p.name for p in (tmp_path / "rel" / "scans").iterdir()) == sorted(
        Path(r["file"]).name for r in res.rows)


def test_scan_export_takes_only_approved_scans(tmp_path, backend):
    rows, objects = backend
    good = rows[0]
    assert export_scans.skip_reason({**good, "review": "pending", "reviewed_at": None}) == "not approved"
    assert export_scans.skip_reason({**good, "review": "rejected"}) == "not approved"
    # A dump from before migration 0003 (no review column): nothing was looked at, nothing goes out.
    old = [{k: v for k, v in r.items() if k not in ("review", "reviewed_at")} for r in rows]
    res = export_scans.export(old, objects, None)
    assert res.rows == [] and res.skipped["not approved"] == 5
    assert export_scans.fetch_commands(old, objects) == []
    # The quarantine as the backend writes it: a new row is pending until approved.
    db = sqlite_from_migrations(tmp_path / "d1.sqlite", [{k: v for k, v in r.items() if k not in ("review", "reviewed_at")}
                                                        for r in rows])
    assert {r["review"] for r in export_scans.load_rows(db)} == {"pending"}


def test_scan_ids_are_stable_and_opaque():
    assert export_scans.scan_id(UUIDS[0]) == export_scans.scan_id(UUIDS[0])
    assert export_scans.scan_id(UUIDS[0]) != export_scans.scan_id(UUIDS[1])
    assert UUIDS[0].replace("-", "")[:8] not in export_scans.scan_id(UUIDS[0])


def test_scan_export_lists_withdrawals_since_previous_release(tmp_path, backend):
    rows, objects = backend
    first = export_scans.export(rows, objects, tmp_path / "v1")
    # The de scan gets withdrawn before the next release.
    rows = [{**r, "withdrawn_at": "2026-12-01T00:00:00Z"} if r["id"] == UUIDS[0] else r for r in rows]
    second = export_scans.export(rows, objects, tmp_path / "v2", previous=export_scans.previous_ids(tmp_path / "v1"))
    gone = export_scans.scan_id(UUIDS[0])
    assert gone in {r["scan_id"] for r in first.rows} and gone not in {r["scan_id"] for r in second.rows}
    assert (tmp_path / "v2" / "withdrawn.csv").read_text(encoding="utf-8") == f"scan_id\n{gone}\n"
    # It stays listed in the release after that.
    third = export_scans.export(rows, objects, None, previous=export_scans.previous_ids(tmp_path / "v2"))
    assert third.withdrawn_since_previous == [gone]


@pytest.mark.parametrize("fmt", ["sqlite", "sql", "json"])
def test_scan_rows_load_from_every_d1_copy(tmp_path, backend, fmt):
    rows, objects = backend
    db = sqlite_from_migrations(tmp_path / "d1.sqlite", rows)
    if fmt == "sqlite":
        path = db
    elif fmt == "sql":  # the shape of `wrangler d1 export`
        con = sqlite3.connect(db)
        path = tmp_path / "contributions.sql"
        path.write_text("PRAGMA defer_foreign_keys=TRUE;\n" + "\n".join(con.iterdump()), encoding="utf-8")
        con.close()
    else:  # `wrangler d1 execute --json`
        path = tmp_path / "rows.json"
        path.write_text(json.dumps([{"results": rows, "success": True, "meta": {}}]), encoding="utf-8")
    loaded = export_scans.load_rows(path)
    assert sorted(r["id"] for r in loaded) == sorted(UUIDS)
    assert len(export_scans.export(loaded, objects, None).rows) == 2


def test_rows_from_before_stored_at_count_as_stored(tmp_path, backend):
    rows, objects = backend
    old = [{k: v for k, v in r.items() if k != "stored_at"} for r in rows]
    res = export_scans.export(old, objects, None)
    assert len(res.rows) == 3 and "not stored" not in res.skipped  # row 3 has an object key, as 0002 assumes
    assert export_scans.skip_reason(old[7]) == "withdrawn"         # a tombstone stays out


def test_scan_export_refuses_odd_rows(backend):
    rows, _ = backend
    good = rows[0]
    assert export_scans.skip_reason(good) is None
    assert export_scans.skip_reason({**good, "object_key": "scans/../../secret"}) == "bad object key"
    assert export_scans.skip_reason({**good, "card_id": "a b"}) == "bad label"
    assert export_scans.skip_reason({**good, "lang": "DE"}) == "bad label"
    assert export_scans.skip_reason({**good, "consent_version": None}) == "consent"


def test_fetch_commands_only_for_eligible_missing_objects(tmp_path, backend):
    rows, objects = backend
    cmds = export_scans.fetch_commands(rows, objects)
    assert len(cmds) == 1 and UUIDS[7] in cmds[0]       # row 6 points at a missing object
    assert all(UUIDS[2] not in c for c in cmds)         # never a withdrawn scan
    assert cmds[0].startswith("wrangler r2 object get pokemon-cards-contributions/scans/") and "--remote" in cmds[0]


# ---------------------------------------------------------------------------------------------------------------
# Release

def test_release_build(tmp_path, backend):
    rows, objects = backend
    db = sqlite_from_migrations(tmp_path / "d1.sqlite", rows)
    out = tmp_path / "release" / "v2026.11.0"
    meta = release.build(out, version="2026.11.0", manifest=write_manifest(tmp_path / "manifest.csv"),
                         pack_cards=[write_pack_cards(tmp_path / "cards-3.json.gz")], rows=db, objects=objects,
                         today="2026-11-18")
    assert meta["cards"] == 4 and meta["scans"] == 2
    names = {p.relative_to(out).as_posix() for p in out.rglob("*") if p.is_file()}
    assert {"README.md", "cards.csv", "sources.json", "download.py", "scans.csv", "withdrawn.csv", "stats.json",
            "release.json", "SHA256SUMS", "LICENSES/CC0-scans.txt", "LICENSES/TCGdex-MIT.txt"} <= names
    sums = (out / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    assert len(sums) == len(names) - 1
    for line in sums:
        digest, rel = line.split("  ", 1)
        assert hashlib.sha256((out / rel).read_bytes()).hexdigest() == digest
    readme = (out / "README.md").read_text(encoding="utf-8")
    assert "$" not in readme.replace("$ ", "")         # every placeholder filled
    assert "CC0 1.0" in readme and "Limitless TCG" in readme and "Version **2026.11.0**" in readme
    for p in out.rglob("*"):
        if p.is_file() and p.suffix in (".csv", ".json", ".md"):
            text = p.read_text(encoding="utf-8")
            assert str(tmp_path) not in text and str(tmp_path).replace("\\", "/") not in text
    assert (tmp_path / "release" / "v2026.11.0.review.html").exists()
    with pytest.raises(SystemExit):  # a release directory is written once
        release.build(out, version="2026.11.0", manifest=tmp_path / "manifest.csv")
    with pytest.raises(SystemExit, match="withdrawn"):  # forgetting --rows must not withdraw every earlier scan
        release.build(tmp_path / "release" / "v2026.12.0", version="2026.12.0", manifest=tmp_path / "manifest.csv",
                      previous=out)
    nxt = release.build(tmp_path / "release" / "v2026.12.0", version="2026.12.0", manifest=tmp_path / "manifest.csv",
                        rows=db, objects=objects, previous=out, today="2026-12-01")
    assert nxt["scans"] == 2 and nxt["withdrawnSincePrevious"] == 0
