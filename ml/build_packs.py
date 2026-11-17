# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the per-language packs the app downloads (issue #5, ADR 0003, format in docs/packs.md).

Three steps, so each runs where its inputs are. A "work" directory carries the results from one to the next and
can be copied between machines:

  fetch   TCGdex card metadata -> <work>/<lang>/metadata.json             anywhere, standard library only
  embed   reference images + model -> <work>/<lang>/embeddings.{bin,json}  where CARDINDEX_DATA is (numpy, OpenCV,
          and <work>/model/                                                 ai-edge-litert, like app_assets.py)
  build   work + the published catalog -> <out>/ (packs, deltas,           anywhere, standard library only
          catalog.json); --upload puts the new files into R2

  python build_packs.py fetch --work packs-work --langs en,de
  CARDINDEX_DATA=... python build_packs.py embed --work packs-work --download-images
  python build_packs.py build --work packs-work --out packs-out --base https://<api>/v1/packs
  python build_packs.py build ... --upload           # wrangler r2 object put, catalog.json last

`all` runs the three in a row. `build --no-embeddings` makes metadata-only packs (for tests; never uploaded).
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path

import packs

ML_DIR = Path(__file__).resolve().parent
BACKEND_DIR = ML_DIR.parent / "backend"
API = "https://api.tcgdex.net/v2"
UA = "cardindex-ml-packs/0.1 (open-source)"
BUCKET = "pokemon-cards-packs"  # backend/wrangler.jsonc, binding PACKS


# ---------------------------------------------------------------------------------------------------------------
# HTTP (standard library, so the metadata steps need no installed packages)

def http_get(url: str, *, timeout: int = 60, tries: int = 4) -> bytes:
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429 or attempt == tries - 1:
                raise
        except urllib.error.URLError:
            if attempt == tries - 1:
                raise
        time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def get_json(url: str):
    return json.loads(http_get(url))


# ---------------------------------------------------------------------------------------------------------------
# Step 1: fetch

def http_exists(url: str, *, timeout: int = 30, tries: int = 3) -> bool | None:
    """HEAD request: True on 2xx, False on 403/404/410, None when the answer stays unclear after `tries`."""
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=timeout):
                return True
        except urllib.error.HTTPError as e:
            if e.code in (403, 404, 410):
                return False
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(2 ** attempt)
    return None


def probe_cdn_gap(lang: str, sets: list[dict], cards: list[dict], cache: Path, *, max_age_days: float,
                  workers: int) -> dict[str, str]:
    """Card id -> image base URL for cards the API serves without `image` although their file is on the CDN
    (packs.cdn_gap_candidates). One HEAD per candidate on `/high.webp`; answers are cached in `cache` and checked
    again after `max_age_days`, so a weekly fetch costs one request per card without image."""
    candidates = packs.cdn_gap_candidates(lang, sets, cards)
    known = json.loads(cache.read_text(encoding="utf-8")) if cache.exists() else {}
    now = time.time()

    def stale(cid: str, url: str) -> bool:
        k = known.get(cid)
        return not k or k["url"] != url or now - k["checked"] > max_age_days * 86400

    todo = [(cid, url) for cid, url in candidates.items() if stale(cid, url)]
    with ThreadPoolExecutor(max_workers=max(1, min(workers, 4))) as ex:
        answers = list(ex.map(lambda t: http_exists(t[1] + "/high.webp"), todo))
    unclear = 0
    for (cid, url), ok in zip(todo, answers):
        if ok is None:
            unclear += 1
            known.pop(cid, None)  # ask again next time instead of trusting an old answer
        else:
            known[cid] = {"url": url, "ok": ok, "checked": now}
    known = {cid: k for cid, k in known.items() if cid in candidates}
    cache.write_text(json.dumps(known, sort_keys=True, indent=0), encoding="utf-8")
    found = {cid: k["url"] for cid, k in known.items() if k["ok"]}
    print(f"{lang}: CDN gap: {len(candidates)} cards without image, {len(todo)} probed, {len(found)} on the CDN"
          + (f", {unclear} unclear (retried next fetch)" if unclear else ""))
    return found


def fetch_lang(lang: str, work: Path, *, only_sets: set[str] | None, max_age_days: float, workers: int,
               cdn_gap: bool = True) -> dict:
    """Card metadata of one language. Set answers are cached in <work>/tcgdex/<lang>/ and fetched again when the
    set list reports a different card count or the cache is older than `max_age_days`."""
    listing = get_json(f"{API}/{lang}/sets")
    if only_sets:
        listing = [s for s in listing if s["id"] in only_sets]
    cache = work / "tcgdex" / lang
    cache.mkdir(parents=True, exist_ok=True)

    def one(brief: dict) -> dict:
        path = cache / f"{urllib.parse.quote(brief['id'], safe='')}.json"
        if path.exists():
            cached = json.loads(path.read_text(encoding="utf-8"))
            fresh = time.time() - path.stat().st_mtime < max_age_days * 86400
            same = (cached.get("cardCount") or {}).get("total") == (brief.get("cardCount") or {}).get("total")
            if fresh and same:
                return cached
        data = http_get(f"{API}/{lang}/sets/{urllib.parse.quote(brief['id'], safe='')}")
        path.write_bytes(data)
        return json.loads(data)

    with ThreadPoolExecutor(max_workers=max(1, min(workers, 4))) as ex:  # TCGdex is free: at most 4 requests
        answers = list(ex.map(one, listing))
    sets = [packs.set_record(s) for s in answers]
    cards = [c for s in answers for c in packs.card_records(s)]
    if cdn_gap:
        found = probe_cdn_gap(lang, sets, cards, cache.parent / f"{lang}.cdn-gap.json",
                              max_age_days=max_age_days, workers=workers)
        cards = packs.fill_cdn_gap(cards, found)
    meta = {"lang": lang, "fetchedAt": now_iso(), "sets": packs.sort_sets(sets), "cards": packs.sort_cards(cards)}
    out = work / lang / "metadata.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    with_image = sum(1 for c in cards if c["image"])
    print(f"{lang}: {len(sets)} sets, {len(cards)} cards, {with_image} with image -> {out}")
    return meta


# ---------------------------------------------------------------------------------------------------------------
# Step 2: embed (needs the data dir; heavy imports stay inside)

def embed(work: Path, langs: list[str], *, model_rel: str, corners_rel: str, download_images: bool,
          threads: int) -> None:
    """Embeds every card that has a reference image with the app's own .tflite model (same code path as
    app_assets.py, so pack and app always match). Rows are cached per model and image hash, so an unchanged card
    keeps byte-identical bytes and does not show up in the next delta."""
    import hashlib

    import numpy as np

    from app_assets import INPUT, Embedder, to_input
    from common import DATA_DIR, Card
    from synth import load_ref_rgba

    model = DATA_DIR / model_rel
    model_bytes = model.read_bytes()
    model_sha = packs.sha256_bytes(model_bytes)
    emb = Embedder(model, threads=threads)
    dim = int(emb(np.zeros((1, INPUT, INPUT, 3), np.float32)).shape[0])

    mdir = work / "model"
    mdir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(model, mdir / "embedder.tflite")
    corners = DATA_DIR / corners_rel if corners_rel else None
    if corners and corners.exists():
        shutil.copyfile(corners, mdir / "corners.tflite")
    elif (mdir / "corners.tflite").exists():
        (mdir / "corners.tflite").unlink()
    (mdir / "model.json").write_text(json.dumps({"name": model.name, "sha256": model_sha, "dim": dim,
                                                 "inputSize": INPUT}, indent=1))

    for lang in langs:
        meta_path = work / lang / "metadata.json"
        if not meta_path.exists():
            print(f"{lang}: no metadata, run fetch first")
            continue
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        sets = {s["id"]: s for s in meta["sets"]}
        cards = [Card(f"{lang}/{c['id']}", c["id"], lang, c["setId"], sets[c["setId"]]["name"], c["localId"],
                      int(sets[c["setId"]]["official"] or 0), c["name"], "", (), c["image"])
                 for c in meta["cards"] if c["image"]]
        if download_images:
            from fetch import download
            with ThreadPoolExecutor(max_workers=4) as ex:
                results = list(ex.map(download, cards))
            print(f"{lang}: images {({k: results.count(k) for k in set(results)})}")

        cache_dir = DATA_DIR / "packs-embcache" / model_sha[:12]
        cache_dir.mkdir(parents=True, exist_ok=True)
        cache_json, cache_bin = cache_dir / f"{lang}.json", cache_dir / f"{lang}.bin"
        cached: dict[str, tuple[str, bytes]] = {}
        if cache_json.exists() and cache_bin.exists():
            entries = json.loads(cache_json.read_text())
            blob = cache_bin.read_bytes()
            for n, (cid, img_sha) in enumerate(entries):
                cached[cid] = (img_sha, blob[n * dim * 4:(n + 1) * dim * 4])

        ids, rows, entries, fresh = [], [], [], 0
        for c in cards:
            if not c.ref_path.exists() or c.ref_path.stat().st_size == 0:
                continue
            img_sha = hashlib.sha256(c.ref_path.read_bytes()).hexdigest()
            hit = cached.get(c.card_id)
            if hit and hit[0] == img_sha:
                row = hit[1]
            else:
                row = emb(to_input(load_ref_rgba(str(c.ref_path)))).astype("<f4").tobytes()
                fresh += 1
            ids.append(c.card_id)
            rows.append(row)
            entries.append([c.card_id, img_sha])
        cache_json.write_text(json.dumps(entries))
        cache_bin.write_bytes(b"".join(rows))
        (work / lang / "embeddings.bin").write_bytes(b"".join(rows))
        (work / lang / "embeddings.json").write_text(json.dumps(
            {"model": model.name, "modelSha256": model_sha, "dim": dim, "ids": ids}))
        print(f"{lang}: {len(ids)} embeddings ({fresh} computed, {len(ids) - fresh} cached), "
              f"{len(meta['cards']) - len(ids)} cards without reference image")


# ---------------------------------------------------------------------------------------------------------------
# Step 3: build

class Source:
    """The published packs: a local mirror directory or the API's /v1/packs URL."""

    def __init__(self, where: str | Path | None):
        self.where = str(where) if where else None

    def read(self, key: str) -> bytes | None:
        if not self.where:
            return None
        if self.where.startswith(("http://", "https://")):
            try:
                return http_get(f"{self.where.rstrip('/')}/{key}")
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return None
                raise
        path = Path(self.where) / key
        return path.read_bytes() if path.exists() else None

    def catalog(self) -> dict:
        data = self.read("catalog.json")
        if data is None:
            return packs.empty_catalog()
        cat = json.loads(data)
        if cat.get("formatVersion") != packs.FORMAT_VERSION:
            raise SystemExit(f"published catalog has format {cat.get('formatVersion')}, this builder writes "
                             f"{packs.FORMAT_VERSION}")
        return cat

    def pack(self, lang: str, entry: dict) -> packs.Pack | None:
        """The published full pack, checked against the catalog like the app does; None if unavailable."""
        cards_entry, index_entry = entry["files"]["cards"], entry["files"].get("index")
        cards = self.read(cards_entry["path"])
        index = self.read(index_entry["path"]) if index_entry else None
        if cards is None or (index_entry and index is None):
            print(f"{lang}: published v{entry['version']} files missing in {self.where}; no delta this time")
            return None
        if not packs.verify_file(cards_entry, cards) or (index_entry and not packs.verify_file(index_entry, index)):
            raise SystemExit(f"{lang}: published v{entry['version']} files do not match the catalog checksums")
        return packs.pack_from_files(lang, cards, index, entry.get("dim") or 0)


def load_work_pack(work: Path, lang: str, *, embeddings: bool, model_sha: str | None = None) -> packs.Pack | None:
    meta_path = work / lang / "metadata.json"
    if not meta_path.exists():
        return None
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    rows: dict[str, bytes] = {}
    dim = 0
    if embeddings:
        info_path = work / lang / "embeddings.json"
        if not info_path.exists():
            raise SystemExit(f"{lang}: no embeddings in {work / lang}; run embed first or pass --no-embeddings")
        info = json.loads(info_path.read_text())
        # Embeddings from another model would pass every check in the app and then match nothing.
        if model_sha is not None and info.get("modelSha256") != model_sha:
            raise SystemExit(f"{lang}: embeddings were made with another model than work/model; run embed again")
        dim = int(info["dim"])
        blob = (work / lang / "embeddings.bin").read_bytes()
        if len(blob) != len(info["ids"]) * dim * 4:
            raise SystemExit(f"{lang}: embeddings.bin does not match embeddings.json")
        rows = {cid: blob[n * dim * 4:(n + 1) * dim * 4] for n, cid in enumerate(info["ids"])}
    return packs.make_pack(lang, meta["sets"], meta["cards"], rows, dim)


def load_work_model(work: Path) -> tuple[dict, list[packs.PublishedFile]]:
    mdir = work / "model"
    info = json.loads((mdir / "model.json").read_text())
    embedder = (mdir / "embedder.tflite").read_bytes()
    if packs.sha256_bytes(embedder) != info["sha256"]:
        raise SystemExit("work/model/embedder.tflite does not match model.json")
    corners = (mdir / "corners.tflite").read_bytes() if (mdir / "corners.tflite").exists() else None
    return packs.model_entry(info["name"], embedder, dim=info["dim"], input_size=info["inputSize"],
                             corners=corners)


def build(work: Path, out: Path, langs: list[str], *, base: str | None, embeddings: bool,
          built_at: str | None = None) -> tuple[dict, list[packs.PublishedFile]]:
    """Builds new pack versions into `out` and returns the new catalog and the files that changed."""
    source = Source(base if base is not None else (out if (out / "catalog.json").exists() else None))
    catalog = source.catalog()
    # Model files are immutable and named by hash: one the published catalog lists is already in the bucket.
    published = {e["path"] for m in catalog["models"].values() for e in (m["embedder"], m.get("corners")) if e}
    built_at = built_at or now_iso()
    model, model_files = (None, [])
    if embeddings:
        model, model_files = load_work_model(work)
    changed: list[packs.PublishedFile] = []
    for lang in langs:
        pack = load_work_pack(work, lang, embeddings=embeddings,
                              model_sha=model["embedder"]["sha256"] if model else None)
        if pack is None:
            print(f"{lang}: no metadata in {work}, skipped")
            continue
        if pack.rows and pack.dim != model["dim"]:
            raise SystemExit(f"{lang}: embeddings have dim {pack.dim}, the model {model['dim']}")
        prev = catalog["packs"].get(lang)
        base_pack = source.pack(lang, prev) if prev else None
        catalog, files = packs.publish_pack(catalog, pack, base_pack, model=model, built_at=built_at)
        if not files:
            print(f"{lang}: unchanged at v{prev['version']}")
            continue
        entry = catalog["packs"][lang]
        delta = entry["deltas"][-1] if entry["deltas"] and entry["deltas"][-1]["to"] == entry["version"] else None
        print(f"{lang}: v{entry['version']}, {entry['metadataCards']} cards, {entry['cards']} indexed, "
              f"{entry['size'] / 1e6:.1f} MB" + (f", delta from v{delta['from']} {delta['size'] / 1e6:.2f} MB"
                                                  if delta else ", no delta"))
        changed += files
    # A pack can point to a new model without new pack files; then only the catalog (and model) are published.
    if not changed and catalog["packs"] == source.catalog()["packs"]:
        print("nothing changed")
        return catalog, []
    catalog = packs.prune_models(catalog)
    used = {e["path"] for m in catalog["models"].values() for e in (m["embedder"], m["corners"]) if e}
    changed += [f for f in model_files if f.key in used and f.key not in published]
    catalog_bytes = (json.dumps(catalog, ensure_ascii=False, indent=1) + "\n").encode("utf-8")
    changed.append(packs.PublishedFile("catalog.json", catalog_bytes, "application/json"))
    # out/catalog.json is the baseline of the next build, so the new one stays staged until it is published.
    packs.write_files(out, [f for f in changed if f.key != "catalog.json"])
    (out / PENDING_CATALOG).write_bytes(catalog_bytes)
    print(f"wrote {len(changed)} files to {out} (catalog staged as {PENDING_CATALOG})")
    return catalog, changed


PENDING_CATALOG = "catalog.next.json"


def promote_catalog(out: Path) -> None:
    """Makes the staged catalog the baseline, once its files are where the app will look for them."""
    (out / PENDING_CATALOG).replace(out / "catalog.json")
    print(f"{out / 'catalog.json'} is the new baseline")


def upload(files: list[packs.PublishedFile], out: Path, *, bucket: str, remote: bool, dry_run: bool) -> None:
    paths = {"catalog.json": out / PENDING_CATALOG}
    for cmd in packs.upload_commands(files, bucket, out, remote=remote, paths=paths):
        print("$", " ".join(cmd))
        if not dry_run:
            subprocess.run(cmd, cwd=BACKEND_DIR, check=True)


# ---------------------------------------------------------------------------------------------------------------

def now_iso() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_langs(value: str) -> list[str]:
    langs = [x.strip() for x in value.split(",") if x.strip()]
    unknown = [x for x in langs if x not in packs.LANGS]
    if unknown:
        raise argparse.ArgumentTypeError(f"unknown languages {unknown}; known: {','.join(packs.LANGS)}")
    return langs


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common_args(p):
        p.add_argument("--work", type=Path, default=ML_DIR / "packs-work", help="work directory (default ml/packs-work)")
        p.add_argument("--langs", type=parse_langs, default=list(packs.LANGS), help="comma-separated TCGdex codes")

    def fetch_args(p):
        p.add_argument("--sets", default="", help="only these set ids (comma-separated), e.g. for a quick test")
        p.add_argument("--max-age-days", type=float, default=7, help="refetch cached set answers older than this")
        p.add_argument("--workers", type=int, default=4)
        p.add_argument("--no-cdn-gap", action="store_true",
                       help="do not probe the CDN for images the API leaves out (tcgdex/cards-database#2477)")

    def embed_args(p):
        p.add_argument("--model", default="export/mnv3_256_real_w8.tflite", help="relative to CARDINDEX_DATA")
        p.add_argument("--corners", default="export/corners_mnv3_w8.tflite", help="relative to CARDINDEX_DATA; '' = none")
        p.add_argument("--download-images", action="store_true", help="fetch missing reference images (fetch.py)")
        p.add_argument("--threads", type=int, default=8)

    def build_args(p):
        p.add_argument("--out", type=Path, default=ML_DIR / "packs-out", help="local mirror of the R2 bucket")
        p.add_argument("--base", default=None,
                       help="published packs to build on: a mirror dir or https://<api>/v1/packs (default --out)")
        p.add_argument("--no-embeddings", action="store_true", help="metadata-only packs (tests; cannot be uploaded)")
        p.add_argument("--upload", action="store_true", help="put the new files into R2 with wrangler (catalog last)")
        p.add_argument("--bucket", default=BUCKET)
        p.add_argument("--local", action="store_true", help="upload to wrangler's local R2 instead of --remote")
        p.add_argument("--dry-run", action="store_true", help="with --upload: print the wrangler commands only")

    steps = {
        "fetch": ("TCGdex metadata into the work dir", [fetch_args]),
        "embed": ("reference embeddings into the work dir (needs CARDINDEX_DATA)", [embed_args]),
        "build": ("packs, deltas and catalog.json from the work dir", [build_args]),
        "all": ("fetch + embed + build", [fetch_args, embed_args, build_args]),
    }
    for name, (help_text, adders) in steps.items():
        p = sub.add_parser(name, help=help_text)
        common_args(p)
        for add in adders:
            add(p)
    args = ap.parse_args(argv)
    if getattr(args, "upload", False) and args.no_embeddings:
        sys.exit("--upload with --no-embeddings would replace the published packs with metadata-only ones")

    if args.cmd in ("fetch", "all"):
        only = {s for s in args.sets.split(",") if s} or None
        for lang in args.langs:
            fetch_lang(lang, args.work, only_sets=only, max_age_days=args.max_age_days, workers=args.workers,
                       cdn_gap=not args.no_cdn_gap)
    if args.cmd == "embed" or (args.cmd == "all" and not args.no_embeddings):
        embed(args.work, args.langs, model_rel=args.model, corners_rel=args.corners,
              download_images=args.download_images, threads=args.threads)
    if args.cmd in ("build", "all"):
        _, files = build(args.work, args.out, args.langs, base=args.base, embeddings=not args.no_embeddings)
        # Only a published catalog becomes the baseline: a local build (or a dry run) stays staged, so a later
        # --upload rebuilds the same versions and publishes them, and model files are never assumed uploaded.
        # A failed upload raises before the promotion.
        if files and args.upload:
            upload(files, args.out, bucket=args.bucket, remote=not args.local, dry_run=args.dry_run)
            if not args.dry_run:
                promote_catalog(args.out)
        elif files:
            print(f"not uploaded: {PENDING_CATALOG} stays staged; run again with --upload to publish")


if __name__ == "__main__":
    main()
