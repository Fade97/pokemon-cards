#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Rebuild the reference images of the open card dataset from its manifest (cards.csv).

The dataset does not ship third-party card images, only their URLs. This script downloads them for your own use:

    python download.py cards.csv --out images                 # everything (tens of GB for all languages)
    python download.py cards.csv --out images --lang de --limit 50
    python download.py cards.csv --out images --dry-run       # print the plan only

- Python 3.9+, standard library only.
- Resumable: run it again and it skips files that are complete (checksum matches, or no checksum is known), and
  continues a partial file with an HTTP Range request when the server's ETag is unchanged.
- Polite: one request at a time, at least `min_interval_s` (sources.json) between requests to one host, honours
  robots.txt (including Crawl-delay) and Retry-After, and backs off on 429/5xx.
- Checks the SHA-256 from the manifest. A mismatch usually means the source changed the image since the release;
  the file is kept and reported, not deleted.
- Writes `<out>/downloaded.csv` (one row per card) and `<out>/download-report.json`.

The images are (c) their owners; read each source's terms in sources.json before use and do not re-host them.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import urllib.robotparser
from pathlib import Path
from urllib.parse import urlsplit

USER_AGENT = "open-card-dataset-downloader/1.0 (python-urllib)"
RECORD_COLUMNS = ["key", "url", "path", "sha256", "size", "status"]
_SAFE = re.compile(r"[^A-Za-z0-9._-]")
_LANG = re.compile(r"^[a-z]{2}(?:-[a-z]{2})?$")
MAX_BYTES = 32 * 1024 * 1024  # a card image is a few MB at most


class Fetcher:
    """HTTP GETs with a per-host minimum interval, robots.txt and retries. `clock`/`sleep` are injectable for tests."""

    def __init__(self, user_agent: str = USER_AGENT, *, timeout: float = 60, retries: int = 5,
                 clock=time.monotonic, sleep=time.sleep, log=print):
        self.ua, self.timeout, self.retries = user_agent, timeout, retries
        self.clock, self.sleep, self.log = clock, sleep, log
        self.last: dict[str, float] = {}
        self.interval: dict[str, float] = {}
        self.robots: dict[str, urllib.robotparser.RobotFileParser] = {}

    def _wait(self, host: str) -> None:
        gap = self.interval.get(host, 1.0)
        if host in self.last:
            delay = self.last[host] + gap - self.clock()
            if delay > 0:
                self.sleep(delay)
        self.last[host] = self.clock()

    def _robots(self, url: str) -> urllib.robotparser.RobotFileParser:
        p = urlsplit(url)
        origin = f"{p.scheme}://{p.netloc}"
        if origin not in self.robots:
            rp = urllib.robotparser.RobotFileParser(origin + "/robots.txt")
            self._wait(p.netloc)
            try:
                with urllib.request.urlopen(self._request(origin + "/robots.txt"), timeout=self.timeout) as r:
                    rp.parse(r.read(512 * 1024).decode("utf-8", "replace").splitlines())
            except urllib.error.HTTPError as e:
                # Same rule as RobotFileParser.read(): 401/403 forbid everything, other 4xx allow everything.
                if e.code in (401, 403):
                    rp.disallow_all = True
                else:
                    rp.allow_all = True
            except (urllib.error.URLError, OSError):
                rp.allow_all = True  # no robots.txt reachable; the per-host interval still applies
            delay = rp.crawl_delay(self.ua)
            if delay:
                self.interval[p.netloc] = max(self.interval.get(p.netloc, 1.0), float(delay))
            self.robots[origin] = rp
        return self.robots[origin]

    def allowed(self, url: str) -> bool:
        return self._robots(url).can_fetch(self.ua, url)

    def _request(self, url: str, headers: dict | None = None) -> urllib.request.Request:
        return urllib.request.Request(url, headers={"User-Agent": self.ua, **(headers or {})})

    def get(self, url: str, headers: dict | None = None, sink: Path | None = None,
            on_headers=None) -> tuple[int, dict, bytes]:
        """(status, headers, body). With `sink`, the body is streamed into that file instead (appended on a 206), so
        an interrupted download leaves a partial file to resume. `on_headers(status, headers)` runs before the body.
        404/403/410/416 come back as a status; other errors are retried, then raised."""
        host = urlsplit(url).netloc
        for attempt in range(self.retries + 1):
            self._wait(host)
            try:
                with urllib.request.urlopen(self._request(url, headers), timeout=self.timeout) as r:
                    h = {k.lower(): v for k, v in r.headers.items()}
                    if on_headers:
                        on_headers(r.status, h)
                    if sink is None:
                        body = r.read(MAX_BYTES + 1)
                        if len(body) > MAX_BYTES:
                            raise ValueError(f"{url}: larger than {MAX_BYTES} bytes")
                        return r.status, h, body
                    sink.parent.mkdir(parents=True, exist_ok=True)
                    with open(sink, "ab" if r.status == 206 else "wb") as fh:
                        for chunk in iter(lambda: r.read(1 << 16), b""):
                            fh.write(chunk)
                            if fh.tell() > MAX_BYTES:
                                raise ValueError(f"{url}: larger than {MAX_BYTES} bytes")
                    return r.status, h, b""
            except urllib.error.HTTPError as e:
                if e.code in (403, 404, 410, 416):
                    return e.code, {k.lower(): v for k, v in e.headers.items()}, b""
                if e.code not in (408, 429, 500, 502, 503, 504) or attempt == self.retries:
                    raise
                wait = _retry_after(e.headers.get("Retry-After"), default=min(2 ** attempt * 2, 120))
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt == self.retries:
                    raise
                wait = min(2 ** attempt * 2, 120)
                self.log(f"  {url}: {e}; retry in {wait:.0f}s")
            self.sleep(wait)
        raise AssertionError("unreachable")


def _retry_after(value: str | None, default: float) -> float:
    try:
        return min(max(float(value), 0.0), 300.0) if value is not None else default
    except ValueError:
        return default


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def target_path(out: Path, row: dict, url: str) -> Path:
    if not _LANG.match(row["lang"]):
        raise ValueError(f"bad lang {row['lang']!r}")
    ext = os.path.splitext(urlsplit(url).path)[1].lower() or ".img"
    name = _SAFE.sub("_", row["card_id"]).strip(".") or "card"
    return out / row["lang"] / f"{name}{ext}"


def _complete(dst: Path, expected: str) -> bool:
    return dst.is_file() and dst.stat().st_size > 0 and (not expected or sha256_file(dst) == expected)


def fetch_one(f: Fetcher, url: str, dst: Path, expected: str) -> tuple[str, str, int]:
    """Downloads url to dst. Returns (status, sha256, size): ok, cached, mismatch, missing or robots."""
    if _complete(dst, expected):
        return "cached", sha256_file(dst), dst.stat().st_size
    if not f.allowed(url):
        return "robots", "", 0
    part = dst.with_name(dst.name + ".part")
    meta = dst.with_name(dst.name + ".part.json")
    headers = {}
    if part.exists() and part.stat().st_size > 0 and meta.exists():
        saved = json.loads(meta.read_text(encoding="utf-8"))
        if saved.get("url") == url and saved.get("etag"):
            headers = {"Range": f"bytes={part.stat().st_size}-", "If-Range": saved["etag"]}

    def remember(status: int, h: dict) -> None:
        if status == 200:  # a fresh full body: note its ETag so an interrupted download can resume
            if h.get("etag"):
                meta.write_text(json.dumps({"url": url, "etag": h["etag"]}), encoding="utf-8")
            else:
                meta.unlink(missing_ok=True)

    dst.parent.mkdir(parents=True, exist_ok=True)
    status, _, _ = f.get(url, headers, sink=part, on_headers=remember)
    if status == 416:  # the partial file does not fit the current file: start over
        part.unlink(missing_ok=True)
        meta.unlink(missing_ok=True)
        status, _, _ = f.get(url, sink=part, on_headers=remember)
    if status in (403, 404, 410, 416):
        return "missing", "", 0
    digest = sha256_file(part)
    os.replace(part, dst)
    meta.unlink(missing_ok=True)
    return ("mismatch" if expected and digest != expected else "ok"), digest, dst.stat().st_size


def load_rows(manifest: Path, *, langs=None, sources=None, splits=None, limit=None) -> list[dict]:
    with open(manifest, encoding="utf-8", newline="") as fh:
        rows = [r for r in csv.DictReader(fh)
                if (not langs or r["lang"] in langs) and (not sources or r["source"] in sources)
                and (not splits or r["split"] in splits)]
    return rows[:limit] if limit else rows


def run(manifest: Path, out: Path, *, source_terms: dict, fetcher: Fetcher, prefer: str = "primary", langs=None,
        sources=None, splits=None, limit=None, dry_run: bool = False, log=print) -> dict:
    rows = load_rows(manifest, langs=langs, sources=sources, splits=splits, limit=limit)
    for sid in sorted({r["source"] for r in rows}):
        s = source_terms.get(sid)
        log(f"[{sid}] " + (f"{s['name']}: {s['images']} Credit: {s['credit']}" if s else
                           "not in sources.json: terms unknown, skipped"))
        if s:
            for host in s["hosts"]:
                fetcher.interval[host] = max(fetcher.interval.get(host, 0.0), float(s["min_interval_s"]))
    if dry_run:
        log(f"dry run: {len(rows)} cards would be downloaded to {out}")
        return {"planned": len(rows)}
    out.mkdir(parents=True, exist_ok=True)
    record_path = out / "downloaded.csv"
    done: dict[str, dict] = {}
    if record_path.exists():
        with open(record_path, encoding="utf-8", newline="") as fh:
            done = {r["key"]: r for r in csv.DictReader(fh)}
    counts: dict[str, int] = {}
    problems: list[dict] = []
    for i, r in enumerate(rows, 1):
        if r["source"] not in source_terms:
            status, rec = "unknown-source", {"key": r["key"], "url": "", "path": "", "sha256": "", "size": 0}
        else:
            cands = [(r["image_url"], r.get("sha256", "")), (r.get("alt_url", ""), r.get("alt_sha256", ""))]
            if prefer == "alt" and cands[1][0]:
                cands.reverse()
            cands = [(u, e) for u, e in cands if u]
            # A file from an earlier run (possibly from the fallback URL) counts before any request is made.
            cached = [(u, e) for u, e in cands if _complete(target_path(out, r, u), e)]
            status, rec = "missing", None
            for url, expected in cached[:1] or cands:
                dst = target_path(out, r, url)
                status, digest, size = fetch_one(fetcher, url, dst, expected)
                rec = {"key": r["key"], "url": url, "path": dst.relative_to(out).as_posix(), "sha256": digest,
                       "size": size}
                if status != "missing":
                    break
        rec["status"] = status
        done[r["key"]] = rec
        counts[status] = counts.get(status, 0) + 1
        if status not in ("ok", "cached"):
            problems.append(rec)
        if i % 50 == 0 or i == len(rows):
            _write_record(record_path, done)
            log(f"{i}/{len(rows)} {counts}")
    report = {"cards": len(rows), "counts": counts, "problems": problems}
    (out / "download-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def _write_record(path: Path, done: dict[str, dict]) -> None:
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, RECORD_COLUMNS, lineterminator="\n")
        w.writeheader()
        for k in sorted(done):
            w.writerow({c: done[k].get(c, "") for c in RECORD_COLUMNS})
    os.replace(tmp, path)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifest", type=Path, help="cards.csv of the release")
    ap.add_argument("--out", type=Path, required=True, help="directory for the images")
    ap.add_argument("--sources", type=Path, help="sources.json (default: next to the manifest)")
    ap.add_argument("--lang", action="append", help="only this language; repeatable")
    ap.add_argument("--source", action="append", help="only this source id; repeatable")
    ap.add_argument("--split", action="append", choices=["train", "holdout", "index"], help="repeatable")
    ap.add_argument("--limit", type=int, help="only the first N cards (after filters), for a sample")
    ap.add_argument("--prefer", choices=["primary", "alt"], default="primary",
                    help="alt = the fallback URL first (for TCGdex the much smaller high.webp)")
    ap.add_argument("--min-interval", type=float, default=0.0,
                    help="seconds between requests to one host; can only make sources.json's value slower")
    ap.add_argument("--user-agent", default=USER_AGENT)
    ap.add_argument("--retries", type=int, default=5)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    terms = json.loads((args.sources or args.manifest.with_name("sources.json")).read_text(encoding="utf-8"))
    fetcher = Fetcher(args.user_agent, retries=args.retries)
    for s in terms.values():
        for host in s["hosts"]:
            fetcher.interval[host] = max(float(s["min_interval_s"]), args.min_interval)
    report = run(args.manifest, args.out, source_terms=terms, fetcher=fetcher, prefer=args.prefer,
                 langs=args.lang, sources=args.source, splits=args.split, limit=args.limit, dry_run=args.dry_run)
    print(json.dumps({k: v for k, v in report.items() if k != "problems"}))
    return 0 if not report.get("problems") else 1


if __name__ == "__main__":
    sys.exit(main())
