# SPDX-License-Identifier: GPL-3.0-or-later
"""Download TCGdex set lists and card images into the data dir and write ml/manifest.csv.

Everything is cached: re-running only fetches what is missing. At most 4 parallel requests.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
from concurrent.futures import ThreadPoolExecutor

import requests
from PIL import Image
from tqdm import tqdm
from urllib3.util.retry import Retry

from common import API_CACHE, MANIFEST, SETS, Card

API = "https://api.tcgdex.net/v2"
UA = {"User-Agent": "cardindex-ml-prototype/0.1 (open-source research)"}
session = requests.Session()
session.headers.update(UA)
# The CDN answers 503 now and then during long runs (~22k EN images): back off and retry instead of aborting.
session.mount("https://", requests.adapters.HTTPAdapter(max_retries=Retry(
    total=6, backoff_factor=2, status_forcelist=(429, 500, 502, 503, 504), allowed_methods=("GET",),
    raise_on_status=False)))


def get_set(lang: str, set_id: str) -> dict:
    path = API_CACHE / lang / f"{set_id}.json"
    if not path.exists():
        r = session.get(f"{API}/{lang}/sets/{set_id}", timeout=30)
        r.raise_for_status()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(r.text, encoding="utf-8")
    return json.loads(path.read_text(encoding="utf-8"))


def get_card(card: Card) -> dict:
    path = API_CACHE / card.lang / "cards" / f"{card.card_id}.json"
    if not path.exists():
        r = session.get(f"{API}/{card.lang}/cards/{card.card_id}", timeout=30)
        r.raise_for_status()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(r.text, encoding="utf-8")
    return json.loads(path.read_text(encoding="utf-8"))


def download(card: Card) -> str:
    dst = card.ref_path
    if dst.exists() and dst.stat().st_size > 0:
        return "cached"
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(".part")
    # high.png is missing for some cards on the CDN while high.webp exists; convert webp to png then.
    r = session.get(card.image_url + "/high.png", timeout=60)
    if r.status_code == 200:
        tmp.write_bytes(r.content)
        tmp.replace(dst)
        return "png"
    r = session.get(card.image_url + "/high.webp", timeout=60)
    if r.status_code == 404:  # API lists an image, CDN has none (e.g. de/30th B/G/R Mew)
        return "missing"
    r.raise_for_status()
    Image.open(io.BytesIO(r.content)).convert("RGBA").save(tmp, format="PNG")
    tmp.replace(dst)
    return "webp"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()

    cards: list[Card] = []
    for lang, set_id, holdout in SETS:
        s = get_set(lang, set_id)
        official = int(s["cardCount"]["official"])
        for c in s["cards"]:
            if not c.get("image"):
                continue
            cards.append(Card(f"{lang}/{c['id']}", c["id"], lang, set_id, s["name"], c["localId"],
                              official, c["name"], "", (), c["image"], holdout))
    print(f"{len(cards)} cards with images")

    with ThreadPoolExecutor(max_workers=min(args.workers, 4)) as ex:
        res = list(tqdm(ex.map(download, cards), total=len(cards), desc="images"))
    print({k: res.count(k) for k in set(res)})
    missing = [c.key for c, r in zip(cards, res) if r == "missing"]
    if missing:
        print(f"skipped, image 404 on the CDN: {missing}")
    cards = [c for c, r in zip(cards, res) if r != "missing"]
    with ThreadPoolExecutor(max_workers=min(args.workers, 4)) as ex:
        details = list(tqdm(ex.map(get_card, cards), total=len(cards), desc="card details"))

    with open(MANIFEST, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["key", "card_id", "lang", "set_id", "set_name", "local_id", "official", "name", "category",
                    "dex_ids", "image_url", "holdout"])
        for c, d in zip(cards, details):
            dex = ";".join(str(x) for x in d.get("dexId") or [])
            w.writerow([c.key, c.card_id, c.lang, c.set_id, c.set_name, c.local_id, c.official, c.name,
                        d.get("category", ""), dex, c.image_url, int(c.holdout)])
    print(f"wrote {MANIFEST}")


if __name__ == "__main__":
    main()
