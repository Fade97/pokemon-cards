# SPDX-License-Identifier: GPL-3.0-or-later
"""Language-pack format: pure functions shared by build_packs.py and its tests (docs/packs.md).

Standard library only (no numpy, torch or OpenCV), so it runs and is tested on any machine. Embeddings are handled
as raw float32 little-endian rows (`bytes` of length dim * 4), exactly as they are stored in index.bin.
"""
from __future__ import annotations

import copy
import gzip
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

FORMAT_VERSION = 1
# TCGdex language codes of the v1 packs (ADR 0003: EU + JP). "ja" is TCGdex's code for Japanese.
LANGS = ("en", "de", "fr", "it", "es", "pt", "nl", "pl", "ja")
# Delta links kept in the catalog per language. An app further behind downloads the full pack.
MAX_DELTAS = 6


# ---------------------------------------------------------------------------------------------------------------
# Hashing and file entries

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_entry(path: str, data: bytes) -> dict:
    """Catalog entry of one published file: R2 key below the packs root, byte size and SHA-256 of the bytes."""
    return {"path": path, "size": len(data), "sha256": sha256_bytes(data)}


def keys_sha256(ids: list[str]) -> str:
    """Digest of the indexed card ids in row order: sha256 of the ids joined with "\\n" (UTF-8, no trailing newline).

    The app recomputes it after applying a delta to check that its rows line up with the full pack's.
    """
    return sha256_bytes("\n".join(ids).encode("utf-8"))


def gzip_bytes(data: bytes) -> bytes:
    """Deterministic gzip (mtime 0, no file name), so the same content always gets the same SHA-256."""
    return gzip.compress(data, compresslevel=9, mtime=0)


def canonical_json(obj) -> bytes:
    """Compact, key-sorted UTF-8 JSON. Rebuilding an unchanged pack gives byte-identical files."""
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


# ---------------------------------------------------------------------------------------------------------------
# Card metadata from TCGdex

def set_record(s: dict) -> dict:
    """The pack's set entry from a TCGdex `/v2/<lang>/sets/<id>` answer."""
    count = s.get("cardCount") or {}
    serie = s.get("serie") or {}
    abbreviation = s.get("abbreviation") or {}
    return {
        "id": s["id"],
        "name": s.get("name") or s["id"],
        "serieId": serie.get("id"),
        "serieName": serie.get("name"),
        "releaseDate": s.get("releaseDate"),
        "official": count.get("official"),
        "total": count.get("total"),
        "abbreviation": abbreviation.get("official"),
        "logo": s.get("logo"),
        "symbol": s.get("symbol"),
    }


def card_records(s: dict) -> list[dict]:
    """The pack's card entries of one TCGdex set answer. `image` is TCGdex's base URL without quality/extension
    (append `/high.webp` or `/low.webp`), `null` when TCGdex has no image."""
    return [
        {"id": c["id"], "setId": s["id"], "localId": c["localId"], "name": c.get("name") or "",
         "image": c.get("image")}
        for c in s.get("cards") or []
    ]


def sort_cards(cards: list[dict]) -> list[dict]:
    """Canonical card order: by id, comparing the strings code point by code point (ids are ASCII)."""
    return sorted(cards, key=lambda c: c["id"])


def sort_sets(sets: list[dict]) -> list[dict]:
    return sorted(sets, key=lambda s: ((s.get("releaseDate") or "9999"), s["id"]))


# ---------------------------------------------------------------------------------------------------------------
# Packs

@dataclass
class Pack:
    """One language pack in memory.

    `cards` are in canonical order; `rows` maps the id of every indexed card to its embedding (dim * 4 bytes).
    Index rows follow the order of the indexed cards in `cards`.
    """
    lang: str
    sets: list[dict]
    cards: list[dict]
    rows: dict[str, bytes]
    dim: int = 0

    def indexed_ids(self) -> list[str]:
        return [c["id"] for c in self.cards if c["id"] in self.rows]

    def index_bytes(self) -> bytes:
        return b"".join(self.rows[i] for i in self.indexed_ids())


def make_pack(lang: str, sets: list[dict], cards: list[dict], rows: dict[str, bytes] | None, dim: int = 0) -> Pack:
    """Validates and normalizes a pack: canonical order, unique ids, rows only for known cards, equal row size."""
    cards = sort_cards(cards)
    ids = [c["id"] for c in cards]
    if len(set(ids)) != len(ids):
        dupes = sorted({i for i in ids if ids.count(i) > 1})
        raise ValueError(f"{lang}: duplicate card ids {dupes[:5]}")
    rows = dict(rows or {})
    unknown = set(rows) - set(ids)
    if unknown:
        raise ValueError(f"{lang}: embeddings for unknown cards {sorted(unknown)[:5]}")
    if rows:
        if dim <= 0:
            raise ValueError(f"{lang}: embeddings need dim > 0")
        bad = [i for i, r in rows.items() if len(r) != dim * 4]
        if bad:
            raise ValueError(f"{lang}: embedding rows of {bad[:5]} are not {dim} float32 values")
    return Pack(lang, sort_sets(sets), cards, rows, dim if rows else 0)


def cards_document(pack: Pack, version: int) -> dict:
    """Content of `cards-<version>.json.gz`. `indexed` marks the cards that have a row in index.bin."""
    return {
        "formatVersion": FORMAT_VERSION,
        "lang": pack.lang,
        "version": version,
        "sets": pack.sets,
        "cards": [{**c, "indexed": c["id"] in pack.rows} for c in pack.cards],
    }


def pack_from_files(lang: str, cards_gz: bytes, index: bytes | None, dim: int) -> Pack:
    """Reads a published full pack back (used as the base of the next delta)."""
    doc = json.loads(gzip.decompress(cards_gz))
    if doc.get("formatVersion") != FORMAT_VERSION:
        raise ValueError(f"{lang}: unsupported pack format {doc.get('formatVersion')}")
    cards = [{k: v for k, v in c.items() if k != "indexed"} for c in doc["cards"]]
    indexed = [c["id"] for c in doc["cards"] if c.get("indexed")]
    rows: dict[str, bytes] = {}
    if indexed:
        if not index or dim <= 0 or len(index) != len(indexed) * dim * 4:
            raise ValueError(f"{lang}: index.bin does not match {len(indexed)} indexed cards of dim {dim}")
        step = dim * 4
        rows = {i: index[n * step:(n + 1) * step] for n, i in enumerate(indexed)}
    return make_pack(lang, doc["sets"], cards, rows, dim)


# ---------------------------------------------------------------------------------------------------------------
# Deltas

def compute_delta(old: Pack, new: Pack) -> dict | None:
    """What turns `old` into `new`: changed or added sets and cards, removed ids, and the new embedding rows.

    Returns None when no delta is possible (different embedding size). An empty delta means nothing changed.
    Rows of `upsert` cards travel in the delta's .bin in upsert order, only for cards marked `indexed`.
    """
    if old.rows and new.rows and old.dim != new.dim:
        return None
    old_sets = {s["id"]: s for s in old.sets}
    new_sets = {s["id"]: s for s in new.sets}
    old_cards = {c["id"]: c for c in old.cards}
    upsert = []
    for c in new.cards:
        prev = old_cards.get(c["id"])
        if prev != c or old.rows.get(c["id"]) != new.rows.get(c["id"]):
            upsert.append({**c, "indexed": c["id"] in new.rows})
    new_ids = {c["id"] for c in new.cards}
    return {
        "upsertSets": [s for s in new.sets if old_sets.get(s["id"]) != s],
        "removeSets": sorted(set(old_sets) - set(new_sets)),
        "upsert": upsert,
        "remove": sorted(set(old_cards) - new_ids),
        "rows": b"".join(new.rows[c["id"]] for c in upsert if c["indexed"]),
    }


def delta_is_empty(delta: dict) -> bool:
    return not (delta["upsertSets"] or delta["removeSets"] or delta["upsert"] or delta["remove"])


def delta_document(lang: str, base: int, target: int, delta: dict) -> dict:
    """Content of `delta-<base>-<target>.json.gz` (the rows go into `delta-<base>-<target>.bin`)."""
    return {"formatVersion": FORMAT_VERSION, "lang": lang, "from": base, "to": target,
            **{k: v for k, v in delta.items() if k != "rows"}}


def apply_delta(old: Pack, doc: dict, rows: bytes) -> Pack:
    """Applies a delta the way the app does (docs/packs.md, "Applying a delta"). Used to self-check every delta."""
    dim = old.dim
    upsert_indexed = [c for c in doc["upsert"] if c.get("indexed")]
    if upsert_indexed:
        if dim <= 0:
            dim = len(rows) // len(upsert_indexed) // 4
        if len(rows) != len(upsert_indexed) * dim * 4:
            raise ValueError("delta rows do not match its indexed cards")
    sets = {s["id"]: s for s in old.sets}
    for sid in doc["removeSets"]:
        sets.pop(sid, None)
    for s in doc["upsertSets"]:
        sets[s["id"]] = s
    cards = {c["id"]: c for c in old.cards}
    out_rows = dict(old.rows)
    for cid in doc["remove"]:
        cards.pop(cid, None)
        out_rows.pop(cid, None)
    step = dim * 4
    n = 0
    for c in doc["upsert"]:
        card = {k: v for k, v in c.items() if k != "indexed"}
        cards[card["id"]] = card
        if c.get("indexed"):
            out_rows[card["id"]] = rows[n * step:(n + 1) * step]
            n += 1
        else:
            out_rows.pop(card["id"], None)
    return make_pack(old.lang, list(sets.values()), list(cards.values()), out_rows, dim)


# ---------------------------------------------------------------------------------------------------------------
# Catalog

def empty_catalog() -> dict:
    return {"formatVersion": FORMAT_VERSION, "generatedAt": None, "models": {}, "packs": {}}


def model_id(name: str, data: bytes) -> str:
    """Immutable model id: file stem plus the first 12 hex digits of its SHA-256, e.g. `mnv3_256_real_w8-1a2b3c4d5e6f`."""
    stem = name.rsplit("/", 1)[-1].removesuffix(".tflite")
    return f"{stem}-{sha256_bytes(data)[:12]}"


@dataclass
class PublishedFile:
    """A file to write under the packs root (and upload to R2 under the same key)."""
    key: str
    data: bytes
    content_type: str


def content_type(key: str) -> str:
    if key.endswith(".json.gz"):
        return "application/gzip"
    if key.endswith(".json"):
        return "application/json"
    return "application/octet-stream"


def publish_pack(catalog: dict, pack: Pack, base: Pack | None, *, model: dict | None, built_at: str,
                 max_deltas: int = MAX_DELTAS) -> tuple[dict, list[PublishedFile]]:
    """Adds a new version of `pack` to a copy of `catalog`. Returns the new catalog and the files to publish.

    `base` is the currently published pack of the language (None for the first build). If nothing changed, the
    catalog entry is kept as it is and no files are returned. A delta from the base is added when the model is the
    same; the delta chain is reset whenever the model changes.
    `model` is `{"id", "embedder": PublishedFile-like dict, ...}` from `model_entry`, or None for a metadata-only
    pack (built with --no-embeddings).
    """
    catalog = copy.deepcopy(catalog)
    lang = pack.lang
    prev = catalog["packs"].get(lang)
    model_sha = model["embedder"]["sha256"] if model and pack.rows else None
    delta = None
    if prev and base is not None and prev.get("modelSha256") == model_sha:
        delta = compute_delta(base, pack)
        if delta is not None and delta_is_empty(delta):
            new_model = model["id"] if model and pack.rows else None
            if prev.get("model") != new_model:
                # Same embedder (so the same index), new model bundle (e.g. corner detector): the pack only points
                # to it, with no new pack files. (The app still re-downloads its packs on any model change.)
                catalog["packs"][lang] = {**prev, "model": new_model}
                catalog["models"][new_model] = {k: v for k, v in model.items() if k != "id"}
            return catalog, []
    version = (prev["version"] + 1) if prev else 1

    files: list[PublishedFile] = []

    def add(key: str, data: bytes) -> dict:
        files.append(PublishedFile(key, data, content_type(key)))
        return file_entry(key, data)

    cards_gz = gzip_bytes(canonical_json(cards_document(pack, version)))
    entry_files = {"cards": add(f"{lang}/cards-{version}.json.gz", cards_gz)}
    index = pack.index_bytes()
    ids = pack.indexed_ids()
    if pack.rows:
        entry_files["index"] = add(f"{lang}/index-{version}.bin", index)

    deltas = []
    if delta is not None and prev is not None:
        base_version = prev["version"]
        doc = delta_document(lang, base_version, version, delta)
        # Self-check: applying the delta to the base must give exactly the new pack.
        applied = apply_delta(base, doc, delta["rows"])
        if applied.cards != pack.cards or applied.sets != pack.sets or applied.index_bytes() != index:
            raise AssertionError(f"{lang}: delta {base_version}->{version} does not reproduce the full pack")
        dfiles = {"cards": add(f"{lang}/delta-{base_version}-{version}.json.gz", gzip_bytes(canonical_json(doc)))}
        if delta["rows"]:
            dfiles["index"] = add(f"{lang}/delta-{base_version}-{version}.bin", delta["rows"])
        deltas = [*prev.get("deltas", []), {
            "from": base_version, "to": version, "files": dfiles,
            "size": sum(f["size"] for f in dfiles.values()),
            "result": {"indexSha256": sha256_bytes(index) if pack.rows else None, "keysSha256": keys_sha256(ids),
                       "cards": len(ids), "metadataCards": len(pack.cards)},
        }][-max_deltas:]

    catalog["packs"][lang] = {
        "version": version,
        "builtAt": built_at,
        # The next five keys match today's meta.json (ml/app_assets.py).
        "model": model["id"] if model and pack.rows else None,
        "dim": pack.dim,
        "cards": len(ids),
        "modelSha256": model_sha,
        "indexSha256": sha256_bytes(index) if pack.rows else None,
        "keysSha256": keys_sha256(ids),
        "metadataCards": len(pack.cards),
        "sets": len(pack.sets),
        "files": entry_files,
        "size": sum(f["size"] for f in entry_files.values()),
        "deltas": deltas,
    }
    if model and pack.rows:
        catalog["models"][model["id"]] = {k: v for k, v in model.items() if k != "id"}
    catalog["generatedAt"] = built_at
    return catalog, files


def model_entry(embedder_name: str, embedder: bytes, *, dim: int, input_size: int,
                corners: bytes | None = None) -> tuple[dict, list[PublishedFile]]:
    """Catalog entry and files of the shared recognition model (same for every language)."""
    # The id covers the corner detector too, so a new detector alone is a new model the app downloads.
    mid = model_id(embedder_name, embedder + (corners or b""))
    files = [PublishedFile(f"models/{mid}.tflite", embedder, "application/octet-stream")]
    entry = {"id": mid, "embedder": file_entry(files[0].key, embedder), "dim": dim, "inputSize": input_size,
             "corners": None}
    if corners:
        cid = model_id("corners", corners)
        files.append(PublishedFile(f"models/{cid}.tflite", corners, "application/octet-stream"))
        entry["corners"] = file_entry(files[-1].key, corners)
    return entry, files


def prune_models(catalog: dict) -> dict:
    """Drops model entries no pack refers to any more (their files stay in R2 for old app installs)."""
    used = {p.get("model") for p in catalog["packs"].values()}
    catalog["models"] = {k: v for k, v in catalog["models"].items() if k in used}
    return catalog


def write_files(root: Path, files: list[PublishedFile]) -> None:
    for f in files:
        dst = root / f.key
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(f.data)


def verify_file(entry: dict, data: bytes) -> bool:
    """What the app does after every download: size and SHA-256 must match the catalog."""
    return len(data) == entry["size"] and sha256_bytes(data) == entry["sha256"]


# ---------------------------------------------------------------------------------------------------------------
# Upload

def upload_commands(files: list[PublishedFile], bucket: str, root: Path, *, remote: bool = True,
                    paths: dict[str, Path] | None = None) -> list[list[str]]:
    """`wrangler r2 object put` invocations, catalog.json last so it never points at files not uploaded yet.

    Versioned files are immutable: the Worker serves them with a one-year cache, so a key must never be reused
    for different bytes.
    """
    ordered = sorted(files, key=lambda f: f.key == "catalog.json")
    cmds = []
    for f in ordered:
        cmd = ["pnpm", "exec", "wrangler", "r2", "object", "put", f"{bucket}/{f.key}",
               "--file", str((paths or {}).get(f.key, root / f.key).resolve()), "--content-type", f.content_type]
        cmd.append("--remote" if remote else "--local")
        cmds.append(cmd)
    return cmds
