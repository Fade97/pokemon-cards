# SPDX-License-Identifier: GPL-3.0-or-later
"""Korean card names from the official Korean card database, for the `ko` pack (#18, decision 2).

TCGdex has Korean names for only 239 cards, so the `ko` pack borrows the Japanese card list (packs.CARD_LIST_LANGS)
and shows Japanese names. pokemoncard.co.kr (Pokémon Korea) lists every Korean card. This script reads only facts
from its card pages: name, set code and collector number. No images, no card text.

Checked 2026-11-20 (docs/research/ko-zh-card-sources.md): robots.txt `Allow: /`. The terms (in force since
2026-10-03) forbid copying the service's content for profit without consent and say nothing about automated access.
The site's list endpoint (`/v2/ajax2_dev2`) builds SQL from its form fields; it is not used. Only plain GETs of card
pages, one at a time, at most one every `--interval` seconds.

A card page is `/cards/detail/<CardNum>`, CardNum = `BS<year><product:3><number:3>`, e.g. BS2026006001 = M6a 001.
Product ids are not listed anywhere, so they are walked: number 001 upwards until `--max-misses` pages in a row
are empty.

  python ko_names.py crawl --out <data>/ko-names --products BS2026006
  python ko_names.py crawl --out <data>/ko-names --years 2008-2026 --ja-metadata <work>/ja/metadata.json
  python ko_names.py export --out <data>/ko-names --ja-metadata <work>/ja/metadata.json   # -> names.json

Pages are cached in <out>/pages.jsonl (also the misses), so a rerun only fetches what is new. Standard library only.
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

SITE = "https://pokemoncard.co.kr"
# A generic client name, as for the other crawls (ml/dataset/download.py); nothing that identifies the project.
UA = "card-names-crawler/1.0 (python-urllib)"

NAME = re.compile(r'<span class="card-hp title">(.*?)</span>', re.S)
NUMBER = re.compile(r'<span class="p_num">\s*([^<]*?)\s*<')
# The card picture names set and number: wmimages/<series>/<set>/<set>_<nnn>.webp
IMAGE = re.compile(r'wmimages/([^/"]+)/([^/"]+)/\2_([^./"?]+)\.(?:webp|png|jpe?g)[^"]*" class="feature_image"')
CARD_NUM = re.compile(r"^BS\d{4}\d{3}$")


def parse_card(page: str) -> dict | None:
    """Name, set, number and series of one card page; None for a page without a card."""
    name = NAME.search(page)
    image = IMAGE.search(page)
    if not name or not image or not html.unescape(name.group(1)).strip():
        return None
    number = NUMBER.search(page)
    return {
        "name": html.unescape(re.sub(r"<[^>]+>", "", name.group(1))).strip(),
        "series": image.group(1),
        "set": image.group(2),
        "localId": image.group(3),
        "printed": number.group(1) if number else None,
    }


def tcgdex_id(card: dict) -> str:
    """The TCGdex id of a Korean card: Korean sets reuse the Japanese set code and number (M6a 001 -> M6a-001)."""
    return f"{card['set']}-{card['localId']}"


class Fetcher:
    """GETs at most one page every `interval` seconds; backs off on 429/5xx and gives up after `tries`."""

    def __init__(self, interval: float, tries: int = 4, server_error_tries: int = 1):
        self.interval, self.tries, self.server_error_tries, self.last = interval, tries, server_error_tries, 0.0

    def get(self, url: str) -> tuple[int, str]:
        """Status and body. A 5xx comes back as that status after `server_error_tries` tries, without waiting: the
        site answers 500, not 404, for a number it has no card for (see crawl)."""
        code = 0
        for attempt in range(self.tries):
            wait = self.interval - (time.monotonic() - self.last)
            if wait > 0:
                time.sleep(wait)
            self.last = time.monotonic()
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ko"})
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    return r.status, r.read().decode("utf-8", errors="replace")
            except urllib.error.HTTPError as e:
                if e.code in (404, 410):
                    return e.code, ""
                if e.code != 429 and e.code < 500:
                    raise
                code = e.code
                retry = e.headers.get("Retry-After")
                delay = float(retry) if retry and retry.isdigit() else 30 * (attempt + 1)
                if e.code >= 500 and attempt + 1 >= self.server_error_tries:
                    return e.code, ""
            except (urllib.error.URLError, TimeoutError) as e:
                code = 0
                delay = 30 * (attempt + 1)
                print(f"  {url}: {e}, retry in {delay:.0f} s", flush=True)
            time.sleep(delay)
        raise SystemExit(f"{url}: still failing after {self.tries} tries ({code or 'no answer'}); stopped (rerun to resume)")


def load_pages(path: Path) -> dict[str, dict]:
    pages: dict[str, dict] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                entry = json.loads(line)
                pages[entry["cardNum"]] = entry
    return pages


def ja_last_numbers(ja_metadata: Path | None) -> dict[str, int]:
    """Japanese TCGdex set id -> its highest plain collector number: where the Korean twin of that set ends."""
    if not ja_metadata:
        return {}
    last: dict[str, int] = {}
    for c in json.loads(ja_metadata.read_text(encoding="utf-8"))["cards"]:
        if c["localId"].isdigit():
            last[c["setId"]] = max(last.get(c["setId"], 0), int(c["localId"]))
    return last


def crawl(out: Path, products: list[str], *, interval: float, max_misses: int, limit: int | None,
          retry_errors: bool = False, ja_metadata: Path | None = None, years: list[int] = (),
          max_empty_products: int = 4) -> None:
    """Walks each product from 001. The site answers 500 instead of 404 for a number it has no card for, and also
    for a few numbers inside a product (M6a on 2026-11-20: 119, 128, 129, 131 of 135, the same on every retry).
    So a 5xx is cached as an error, fetched again only with --retry-errors, and a product ends after `max_misses`
    empty pages in a row past the last number of its Japanese twin (when it has one)."""
    out.mkdir(parents=True, exist_ok=True)
    pages_path = out / "pages.jsonl"
    pages = load_pages(pages_path)
    if retry_errors:
        pages = {k: e for k, e in pages.items() if e["status"] < 500}
    ja_last = ja_last_numbers(ja_metadata)
    fetcher = Fetcher(interval)
    state = {"fetched": 0}
    with pages_path.open("a", encoding="utf-8") as log:

        def walk(product: str) -> int | None:
            """Cards found in `product`; None when --limit stopped the crawl."""
            if not re.fullmatch(r"BS\d{7}", product):
                raise SystemExit(f"{product}: expected BS<year><product>, e.g. BS2026006")
            misses, found, errors, last = 0, 0, 0, 0
            for n in range(1, 1000):
                card_num = f"{product}{n:03d}"
                entry = pages.get(card_num)
                if entry is None:
                    if limit is not None and state["fetched"] >= limit:
                        print(f"stopped after --limit {limit} requests")
                        return None
                    status, page = fetcher.get(f"{SITE}/cards/detail/{card_num}")
                    state["fetched"] += 1
                    card = parse_card(page) if status == 200 else None
                    entry = {"cardNum": card_num, "status": status, **(card or {})}
                    log.write(json.dumps(entry, ensure_ascii=False) + "\n")
                    log.flush()
                    pages[card_num] = entry
                if entry.get("name"):
                    misses, found = 0, found + 1
                    last = max(last, ja_last.get(entry["set"], 0))
                else:
                    errors += entry["status"] >= 500
                    misses += 1
                    if misses >= max_misses and n > last:
                        break
            if found:
                print(f"{product}: {found} cards, {errors} pages answered 5xx ({state['fetched']} requests so far)",
                      flush=True)
            return found

        for product in products:
            if walk(product) is None:
                return
        for year in years:
            empty = 0
            for n in range(1, 1000):
                found = walk(f"BS{year}{n:03d}")
                if found is None:
                    return
                empty = 0 if found else empty + 1
                if empty >= max_empty_products:
                    print(f"{year}: ends after product {n - empty:03d} ({state['fetched']} requests so far)", flush=True)
                    break


def export(out: Path, ja_metadata: Path | None) -> None:
    """<out>/names.json: TCGdex id -> Korean name, for build_packs.py fetch --ko-names."""
    pages = load_pages(out / "pages.jsonl")
    names: dict[str, str] = {}
    for entry in sorted(pages.values(), key=lambda e: e["cardNum"]):
        if entry.get("name"):
            names.setdefault(tcgdex_id(entry), entry["name"])
    if ja_metadata:
        ja = {c["id"] for c in json.loads(ja_metadata.read_text(encoding="utf-8"))["cards"]}
        unmatched = sorted(set(names) - ja)
        print(f"{len(names) - len(unmatched)} of {len(names)} names match a Japanese TCGdex card"
              + (f"; not matched: {', '.join(unmatched[:10])}{' …' if len(unmatched) > 10 else ''}" if unmatched else ""))
    (out / "names.json").write_text(json.dumps(dict(sorted(names.items())), ensure_ascii=False, indent=0),
                                    encoding="utf-8")
    print(f"{len(names)} names -> {out / 'names.json'}")


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("crawl", help="fetch card pages into <out>/pages.jsonl")
    c.add_argument("--out", type=Path, required=True)
    c.add_argument("--products", default="", help="comma-separated product ids, e.g. BS2026006")
    c.add_argument("--years", default="", help="walk every product of these years, e.g. 2008-2026 or 2025,2026")
    c.add_argument("--max-empty-products", type=int, default=4, help="empty products in a row that end a year")
    c.add_argument("--interval", type=float, default=1.5, help="seconds between requests (minimum 1)")
    c.add_argument("--max-misses", type=int, default=3, help="empty pages in a row that end a product")
    c.add_argument("--limit", type=int, default=None, help="stop after this many requests")
    c.add_argument("--retry-errors", action="store_true", help="fetch pages that answered 5xx last time again")
    c.add_argument("--ja-metadata", type=Path, default=None,
                   help="<work>/ja/metadata.json: walk each product at least up to its Japanese twin's last number")
    e = sub.add_parser("export", help="<out>/names.json from the cached pages")
    e.add_argument("--out", type=Path, required=True)
    e.add_argument("--ja-metadata", type=Path, default=None, help="report names without a Japanese TCGdex card")
    args = ap.parse_args(argv)
    if args.cmd == "crawl":
        if args.interval < 1:
            sys.exit("--interval below 1 s is not polite")
        years: list[int] = []
        for part in filter(None, args.years.split(",")):
            lo, _, hi = part.partition("-")
            years += range(int(lo), int(hi or lo) + 1)
        if not args.products and not years:
            sys.exit("pass --products or --years")
        crawl(args.out, [p for p in args.products.split(",") if p], years=years,
              max_empty_products=args.max_empty_products, interval=args.interval,
              max_misses=args.max_misses, limit=args.limit, retry_errors=args.retry_errors, ja_metadata=args.ja_metadata)
    else:
        export(args.out, args.ja_metadata)


if __name__ == "__main__":
    main()
