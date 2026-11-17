# SPDX-License-Identifier: GPL-3.0-or-later
"""Pack format, deltas and the build step, on CPU without torch:

    cd ml && uv run --no-project --with pytest pytest tests/test_packs.py
"""
from __future__ import annotations

import gzip
import json
import struct
import subprocess
import sys
from pathlib import Path

import pytest

# The ml/ scripts import each other as top-level modules (python build_packs.py ...). Set here, not in a
# conftest.py, so this file also runs on its own with only pytest installed (.github/workflows/ml-packs.yml).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_packs  # noqa: E402
import packs  # noqa: E402

DIM = 4
BUILT = "2026-11-17T00:00:00Z"


def row(*values: float) -> bytes:
    return struct.pack(f"<{DIM}f", *values)


def tcgdex_set(set_id: str, cards: list[tuple[str, str, bool]], *, release: str = "2026-01-01") -> dict:
    """A TCGdex /sets/<id> answer with (localId, name, has image) cards."""
    return {
        "id": set_id, "name": f"Set {set_id}", "releaseDate": release,
        "cardCount": {"official": len(cards), "total": len(cards)},
        "serie": {"id": "sv", "name": "Scarlet & Violet"}, "abbreviation": {"official": set_id.upper()},
        "logo": f"https://assets.tcgdex.net/en/sv/{set_id}/logo", "symbol": None,
        "cards": [{"id": f"{set_id}-{n}", "localId": n, "name": name,
                   **({"image": f"https://assets.tcgdex.net/en/sv/{set_id}/{n}"} if img else {})}
                  for n, name, img in cards],
    }


def pack_of(*sets: dict, rows: dict[str, bytes] | None = None, lang: str = "en") -> packs.Pack:
    return packs.make_pack(lang, [packs.set_record(s) for s in sets],
                           [c for s in sets for c in packs.card_records(s)], rows, DIM if rows else 0)


A = tcgdex_set("a", [("002", "Ivysaur", True), ("001", "Bulbasaur", True), ("003", "Venusaur", False)],
               release="2026-01-01")
B = tcgdex_set("b", [("001", "Pikachu", True)], release="2026-02-01")
ROWS_A = {"a-001": row(1, 0, 0, 0), "a-002": row(0, 1, 0, 0)}
ROWS_B = {"b-001": row(0, 0, 1, 0)}


# ---------------------------------------------------------------------------------------------------------------
# Records and canonical order

def test_records_from_tcgdex_answer():
    s = packs.set_record(A)
    assert s == {"id": "a", "name": "Set a", "serieId": "sv", "serieName": "Scarlet & Violet",
                 "releaseDate": "2026-01-01", "official": 3, "total": 3, "abbreviation": "A",
                 "logo": "https://assets.tcgdex.net/en/sv/a/logo", "symbol": None}
    cards = packs.card_records(A)
    assert cards[2] == {"id": "a-003", "setId": "a", "localId": "003", "name": "Venusaur", "image": None}


def test_make_pack_orders_cards_and_rows():
    p = pack_of(B, A, rows={**ROWS_B, **ROWS_A})
    assert [c["id"] for c in p.cards] == ["a-001", "a-002", "a-003", "b-001"]
    assert [s["id"] for s in p.sets] == ["a", "b"]  # by release date
    assert p.indexed_ids() == ["a-001", "a-002", "b-001"]  # a-003 has no image, so no row
    assert p.index_bytes() == ROWS_A["a-001"] + ROWS_A["a-002"] + ROWS_B["b-001"]


def test_make_pack_rejects_bad_input():
    with pytest.raises(ValueError, match="duplicate"):
        pack_of(A, A)
    with pytest.raises(ValueError, match="unknown cards"):
        pack_of(A, rows={"zz-1": row(1, 1, 1, 1)})
    with pytest.raises(ValueError, match="float32"):
        packs.make_pack("en", [], packs.card_records(A), {"a-001": b"\0" * 3}, DIM)


def test_keys_sha256_is_newline_joined():
    import hashlib
    assert packs.keys_sha256(["a-001", "b-001"]) == hashlib.sha256(b"a-001\nb-001").hexdigest()
    assert packs.keys_sha256([]) == hashlib.sha256(b"").hexdigest()


def test_gzip_is_deterministic():
    data = packs.canonical_json({"b": 1, "a": "é"})
    assert data == '{"a":"é","b":1}'.encode()
    assert packs.gzip_bytes(data) == packs.gzip_bytes(data)
    assert gzip.decompress(packs.gzip_bytes(data)) == data


# ---------------------------------------------------------------------------------------------------------------
# Deltas

def test_delta_new_set_only_carries_the_new_cards():
    old, new = pack_of(A, rows=ROWS_A), pack_of(A, B, rows={**ROWS_A, **ROWS_B})
    d = packs.compute_delta(old, new)
    assert [s["id"] for s in d["upsertSets"]] == ["b"]
    assert d["upsert"] == [{"id": "b-001", "setId": "b", "localId": "001", "name": "Pikachu",
                            "image": "https://assets.tcgdex.net/en/sv/b/001", "indexed": True}]
    assert d["remove"] == [] and d["removeSets"] == []
    assert d["rows"] == ROWS_B["b-001"]


def test_delta_changes_removals_and_new_embeddings_round_trip():
    a2 = tcgdex_set("a", [("001", "Bulbasaur (fixed)", True), ("003", "Venusaur", True)], release="2026-01-01")
    old = pack_of(A, B, rows={**ROWS_A, **ROWS_B})
    new = pack_of(a2, rows={"a-001": ROWS_A["a-001"], "a-003": row(0, 0, 0, 1)})
    d = packs.compute_delta(old, new)
    assert [c["id"] for c in d["upsert"]] == ["a-001", "a-003"]  # renamed; image appeared
    assert d["remove"] == ["a-002", "b-001"]
    assert d["removeSets"] == ["b"]
    doc = packs.delta_document("en", 1, 2, d)
    assert "rows" not in doc
    applied = packs.apply_delta(old, json.loads(packs.canonical_json(doc)), d["rows"])
    assert applied.cards == new.cards and applied.sets == new.sets
    assert applied.index_bytes() == new.index_bytes()


def test_delta_for_changed_embedding_only():
    old = pack_of(A, rows=ROWS_A)
    new = pack_of(A, rows={**ROWS_A, "a-002": row(0, 0.5, 0.5, 0)})
    d = packs.compute_delta(old, new)
    assert [c["id"] for c in d["upsert"]] == ["a-002"]
    assert d["rows"] == row(0, 0.5, 0.5, 0)


def test_card_losing_its_embedding_is_upserted_unindexed():
    old = pack_of(A, rows=ROWS_A)
    new = pack_of(A, rows={"a-001": ROWS_A["a-001"]})
    d = packs.compute_delta(old, new)
    assert d["upsert"] == [{**new.cards[1], "indexed": False}] and d["rows"] == b""
    assert packs.apply_delta(old, d, d["rows"]).index_bytes() == new.index_bytes()


def test_no_delta_across_embedding_sizes():
    old = pack_of(A, rows=ROWS_A)
    new = packs.make_pack("en", old.sets, old.cards, {"a-001": b"\0" * 8}, 2)
    assert packs.compute_delta(old, new) is None


def test_apply_delta_rejects_wrong_row_count():
    old, new = pack_of(A, rows=ROWS_A), pack_of(A, B, rows={**ROWS_A, **ROWS_B})
    d = packs.compute_delta(old, new)
    with pytest.raises(ValueError):
        packs.apply_delta(old, d, d["rows"] + row(1, 1, 1, 1))


# ---------------------------------------------------------------------------------------------------------------
# Catalog

def model(sha_seed: bytes = b"model-1"):
    return packs.model_entry("mnv3_256_real_w8.tflite", sha_seed, dim=DIM, input_size=224, corners=b"corners")


def test_model_entry_names_files_by_hash():
    entry, files = model()
    sha = packs.sha256_bytes(b"model-1")
    # The id covers embedder and corner detector; the embedder file keeps its own checksum.
    assert entry["id"] == f"mnv3_256_real_w8-{packs.sha256_bytes(b'model-1' + b'corners')[:12]}"
    assert entry["embedder"] == {"path": f"models/{entry['id']}.tflite", "size": 7, "sha256": sha}
    assert entry["corners"]["path"].startswith("models/corners-")
    assert [f.key for f in files] == [entry["embedder"]["path"], entry["corners"]["path"]]


def test_publish_first_version_matches_meta_json_keys():
    m, _ = model()
    cat, files = packs.publish_pack(packs.empty_catalog(), pack_of(A, rows=ROWS_A), None, model=m, built_at=BUILT)
    e = cat["packs"]["en"]
    assert e["version"] == 1 and e["deltas"] == []
    assert e["model"] == m["id"] and e["dim"] == DIM and e["cards"] == 2 and e["metadataCards"] == 3
    assert e["modelSha256"] == m["embedder"]["sha256"]
    index = ROWS_A["a-001"] + ROWS_A["a-002"]
    assert e["indexSha256"] == packs.sha256_bytes(index)
    assert e["keysSha256"] == packs.keys_sha256(["a-001", "a-002"])
    assert {f.key for f in files} == {"en/cards-1.json.gz", "en/index-1.bin"}
    by_key = {f.key: f for f in files}
    for entry in e["files"].values():
        assert packs.verify_file(entry, by_key[entry["path"]].data)
    assert e["size"] == sum(len(f.data) for f in files)
    assert cat["models"][m["id"]]["dim"] == DIM
    doc = json.loads(gzip.decompress(by_key["en/cards-1.json.gz"].data))
    assert doc["version"] == 1 and [c["indexed"] for c in doc["cards"]] == [True, True, False]


def test_publish_unchanged_writes_nothing():
    m, _ = model()
    p = pack_of(A, rows=ROWS_A)
    cat, _ = packs.publish_pack(packs.empty_catalog(), p, None, model=m, built_at=BUILT)
    again, files = packs.publish_pack(cat, p, p, model=m, built_at="2026-11-18T00:00:00Z")
    assert files == [] and again == cat


def test_publish_delta_chain_and_reset_on_model_change():
    m, _ = model()
    p1 = pack_of(A, rows=ROWS_A)
    cat, _ = packs.publish_pack(packs.empty_catalog(), p1, None, model=m, built_at=BUILT)
    p2 = pack_of(A, B, rows={**ROWS_A, **ROWS_B})
    cat, files = packs.publish_pack(cat, p2, p1, model=m, built_at=BUILT)
    e = cat["packs"]["en"]
    assert e["version"] == 2
    assert {f.key for f in files} == {"en/cards-2.json.gz", "en/index-2.bin", "en/delta-1-2.json.gz",
                                      "en/delta-1-2.bin"}
    (d,) = e["deltas"]
    assert (d["from"], d["to"]) == (1, 2)
    assert d["result"] == {"indexSha256": e["indexSha256"], "keysSha256": e["keysSha256"], "cards": 3,
                           "metadataCards": 4}
    by_key = {f.key: f.data for f in files}
    doc = json.loads(gzip.decompress(by_key["en/delta-1-2.json.gz"]))
    assert packs.apply_delta(p1, doc, by_key["en/delta-1-2.bin"]).index_bytes() == by_key["en/index-2.bin"]

    # Chain: v2 -> v3 keeps the 1->2 link; capped at max_deltas.
    c = tcgdex_set("c", [("001", "Mew", True)], release="2026-03-01")
    p3 = pack_of(A, B, c, rows={**ROWS_A, **ROWS_B, "c-001": row(1, 1, 0, 0)})
    cat3, _ = packs.publish_pack(cat, p3, p2, model=m, built_at=BUILT, max_deltas=1)
    assert [(d["from"], d["to"]) for d in cat3["packs"]["en"]["deltas"]] == [(2, 3)]
    cat3, _ = packs.publish_pack(cat, p3, p2, model=m, built_at=BUILT)
    assert [(d["from"], d["to"]) for d in cat3["packs"]["en"]["deltas"]] == [(1, 2), (2, 3)]

    # A new model invalidates every old row: full pack only, chain reset, old model pruned.
    m2, _ = model(b"model-2")
    cat4, files = packs.publish_pack(cat3, p3, p3, model=m2, built_at=BUILT)
    e4 = cat4["packs"]["en"]
    assert e4["version"] == 4 and e4["deltas"] == [] and e4["model"] == m2["id"]
    assert not any("delta" in f.key for f in files)
    assert set(packs.prune_models(cat4)["models"]) == {m2["id"]}


def test_metadata_only_pack_has_no_index():
    cat, files = packs.publish_pack(packs.empty_catalog(), pack_of(A), None, model=None, built_at=BUILT)
    e = cat["packs"]["en"]
    assert e["model"] is None and e["modelSha256"] is None and e["indexSha256"] is None and e["cards"] == 0
    assert [f.key for f in files] == ["en/cards-1.json.gz"]


def test_pack_from_files_round_trip():
    m, _ = model()
    p = pack_of(A, B, rows={**ROWS_A, **ROWS_B})
    cat, files = packs.publish_pack(packs.empty_catalog(), p, None, model=m, built_at=BUILT)
    by_key = {f.key: f.data for f in files}
    back = packs.pack_from_files("en", by_key["en/cards-1.json.gz"], by_key["en/index-1.bin"], DIM)
    assert back == p
    with pytest.raises(ValueError):
        packs.pack_from_files("en", by_key["en/cards-1.json.gz"], by_key["en/index-1.bin"][:-4], DIM)


def test_upload_commands_put_catalog_last(tmp_path):
    files = [packs.PublishedFile("catalog.json", b"{}", "application/json"),
             packs.PublishedFile("en/index-2.bin", b"x", "application/octet-stream")]
    cmds = packs.upload_commands(files, "bucket", tmp_path)
    assert [c[6] for c in cmds] == ["bucket/en/index-2.bin", "bucket/catalog.json"]
    assert cmds[0][:5] == ["pnpm", "exec", "wrangler", "r2", "object"]
    assert cmds[1][-3:] == ["--content-type", "application/json", "--remote"]
    assert packs.upload_commands(files, "bucket", tmp_path, remote=False)[0][-1] == "--local"


# ---------------------------------------------------------------------------------------------------------------
# build step (work dir -> out dir), without network or model

def write_work(work, *sets, rows=None, model_bytes=b"model-1", embedded_with=None):
    d = work / "en"
    d.mkdir(parents=True, exist_ok=True)
    meta = {"lang": "en", "fetchedAt": BUILT, "sets": [packs.set_record(s) for s in sets],
            "cards": [c for s in sets for c in packs.card_records(s)]}
    (d / "metadata.json").write_text(json.dumps(meta))
    if rows is not None:
        ids = sorted(rows)
        (d / "embeddings.bin").write_bytes(b"".join(rows[i] for i in ids))
        sha = packs.sha256_bytes(embedded_with if embedded_with is not None else model_bytes)
        (d / "embeddings.json").write_text(json.dumps({"model": "m.tflite", "modelSha256": sha, "dim": DIM,
                                                       "ids": ids}))
        m = work / "model"
        m.mkdir(exist_ok=True)
        (m / "embedder.tflite").write_bytes(model_bytes)
        (m / "model.json").write_text(json.dumps({"name": "m.tflite", "sha256": packs.sha256_bytes(model_bytes),
                                                  "dim": DIM, "inputSize": 224}))


def test_build_against_local_mirror(tmp_path):
    work, out = tmp_path / "work", tmp_path / "out"
    write_work(work, A, rows=ROWS_A)
    cat, files = build_packs.build(work, out, ["en", "de"], base=None, embeddings=True, built_at=BUILT)
    keys = {f.key for f in files}
    model_key = cat["models"][cat["packs"]["en"]["model"]]["embedder"]["path"]
    assert keys == {"en/cards-1.json.gz", "en/index-1.bin", model_key, "catalog.json"}
    # Staged until published: the baseline (catalog.json) does not move yet.
    assert not (out / "catalog.json").exists()
    assert json.loads((out / build_packs.PENDING_CATALOG).read_text()) == cat
    build_packs.promote_catalog(out)
    assert json.loads((out / "catalog.json").read_text()) == cat

    # Unchanged rebuild: nothing to publish.
    assert build_packs.build(work, out, ["en"], base=None, embeddings=True, built_at=BUILT)[1] == []

    # New set: delta against the mirror; the model is already published, so it is not uploaded again.
    write_work(work, A, B, rows={**ROWS_A, **ROWS_B})
    cat, files = build_packs.build(work, out, ["en"], base=None, embeddings=True, built_at=BUILT)
    assert {f.key for f in files} == {"en/cards-2.json.gz", "en/index-2.bin", "en/delta-1-2.json.gz",
                                      "en/delta-1-2.bin", "catalog.json"}
    build_packs.promote_catalog(out)

    # A tampered published file is caught before it becomes a delta base.
    (out / "en/index-2.bin").write_bytes(b"\0" * 12)
    write_work(work, A, rows=ROWS_A)
    with pytest.raises(SystemExit, match="checksums"):
        build_packs.build(work, out, ["en"], base=None, embeddings=True, built_at=BUILT)


def test_a_new_corner_detector_alone_is_published_as_a_new_model(tmp_path):
    work, out = tmp_path / "work", tmp_path / "out"
    write_work(work, A, rows=ROWS_A)
    (work / "model" / "corners.tflite").write_bytes(b"corners-1")
    cat1, _ = build_packs.build(work, out, ["en"], base=None, embeddings=True, built_at=BUILT)
    build_packs.promote_catalog(out)

    (work / "model" / "corners.tflite").write_bytes(b"corners-2")
    cat2, files = build_packs.build(work, out, ["en"], base=None, embeddings=True, built_at=BUILT)
    old, new = cat1["packs"]["en"], cat2["packs"]["en"]
    assert new["model"] != old["model"]
    assert new["version"] == old["version"] and new["files"] == old["files"]  # no new pack files
    assert new["modelSha256"] == old["modelSha256"]  # same embedder, so the installed index stays valid
    assert set(cat2["models"]) == {new["model"]}
    corners_key = cat2["models"][new["model"]]["corners"]["path"]
    assert {f.key for f in files} >= {corners_key, "catalog.json"}
    assert not any(k.startswith("en/") for k in {f.key for f in files})


def test_build_refuses_embeddings_from_another_model(tmp_path):
    work, out = tmp_path / "work", tmp_path / "out"
    write_work(work, A, rows=ROWS_A, model_bytes=b"model-2", embedded_with=b"model-1")
    with pytest.raises(SystemExit, match="another model"):
        build_packs.build(work, out, ["en"], base=None, embeddings=True, built_at=BUILT)


def test_failed_upload_keeps_the_baseline_so_a_rerun_publishes_again(tmp_path, monkeypatch):
    work, out = tmp_path / "work", tmp_path / "out"
    write_work(work, A, rows=ROWS_A)
    args = ["build", "--work", str(work), "--out", str(out), "--langs", "en", "--upload"]

    def broken_upload(cmd, **kwargs):
        raise subprocess.CalledProcessError(1, cmd)

    monkeypatch.setattr(build_packs.subprocess, "run", broken_upload)
    with pytest.raises(subprocess.CalledProcessError):
        build_packs.main(args)
    assert not (out / "catalog.json").exists()

    uploaded = []
    monkeypatch.setattr(build_packs.subprocess, "run", lambda cmd, **kwargs: uploaded.append(cmd[6]))
    build_packs.main(args)
    assert uploaded[-1] == "pokemon-cards-packs/catalog.json"
    assert "pokemon-cards-packs/en/index-1.bin" in uploaded
    assert (out / "catalog.json").exists() and not (out / build_packs.PENDING_CATALOG).exists()


def test_build_no_embeddings(tmp_path):
    work, out = tmp_path / "work", tmp_path / "out"
    write_work(work, A)
    cat, files = build_packs.build(work, out, ["en"], base=None, embeddings=False, built_at=BUILT)
    assert {f.key for f in files} == {"en/cards-1.json.gz", "catalog.json"}
    assert cat["models"] == {} and cat["packs"]["en"]["indexSha256"] is None


def test_cli_refuses_uploading_metadata_only(tmp_path):
    with pytest.raises(SystemExit, match="metadata-only"):
        build_packs.main(["build", "--work", str(tmp_path), "--out", str(tmp_path / "o"), "--no-embeddings",
                          "--upload", "--langs", "en"])
