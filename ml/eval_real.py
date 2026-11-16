# SPDX-License-Identifier: GPL-3.0-or-later
"""Real-photo evaluation with auto-labelling.

Photos with arbitrary file names go into <data>/real-photos/. Labels come from two independent sources:

1. label     A local VLM (labeler.py) reads name, collector number and language from the raw photo and matches
             them against the manifest. It never sees the pipeline output.
2. pipeline  The full prototype pipeline: detect -> embed -> kNN -> OCR rerank.
3. review    Where both agree on card + language, the label is accepted automatically. All other photos go to
             a static review.html (photo, crop, labeler guess, pipeline top-5). Choices are kept in the
             browser's localStorage and exported as review_choices.json, which you save into the work dir.
             labels.json = auto-accepted + reviewed.
4. eval      Accuracy of the pipeline against labels.json.

  uv run --extra ocr --extra labeler python eval_real.py all            # label + pipeline + review + eval
  uv run python eval_real.py review && uv run python eval_real.py eval  # after saving review_choices.json

Work dir: <data>/<photos folder name>-eval/. Also: `make-dryrun` builds a folder of synthetic photos with random
names (truth hidden in _truth.json) and `truthcheck` measures labeler/agreement quality against that truth.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import shutil
import uuid
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

from common import DATA_DIR, REAL_DIR, RESULTS_DIR, SYNTH_DIR, load_manifest

IMG_EXT = {".jpg", ".jpeg", ".png", ".webp"}
DEFAULT_RUN = "mnv3_256"


def photos_in(d: Path) -> list[Path]:
    return sorted(p for p in d.glob("*") if p.suffix.lower() in IMG_EXT and not p.name.startswith("_"))


def file_id(p: Path) -> str:
    st = p.stat()
    return hashlib.sha1(f"{p.name}|{st.st_size}|{int(st.st_mtime)}".encode()).hexdigest()[:12]


def load_json(p: Path, default):
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else default


def save_json(p: Path, obj):
    p.write_text(json.dumps(obj, indent=1, ensure_ascii=False), encoding="utf-8")


# ---------------------------------------------------------------- 1. labeler
def step_label(photos: list[Path], work: Path):
    out_p = work / "labeler.json"
    out = load_json(out_p, {})
    todo = [p for p in photos if out.get(p.name, {}).get("id") != file_id(p)]
    if not todo:
        print("labeler: all cached")
        return out
    from labeler import MODEL_ID, VLMLabeler, match
    cards = load_manifest(include_holdout=True)
    vlm = VLMLabeler()
    for i, p in enumerate(todo):
        reading = vlm.read(cv2.imread(str(p)))
        out[p.name] = {"id": file_id(p), "model": MODEL_ID, "reading": reading, "match": match(reading, cards)}
        print(f"[label {i + 1}/{len(todo)}] {p.name}: {out[p.name]['match']}", flush=True)
        save_json(out_p, out)
    del vlm
    import torch
    torch.cuda.empty_cache()
    return out


# ---------------------------------------------------------------- 2. pipeline
def step_pipeline(photos: list[Path], work: Path, run: str):
    out_p = work / "pipeline.json"
    out = load_json(out_p, {})
    todo = [p for p in photos if out.get(p.name, {}).get("id") != file_id(p) or out[p.name].get("run") != run]
    if not todo:
        print("pipeline: all cached")
        return out
    from pipeline import Pipeline
    try:
        pipe = Pipeline(run, use_ocr=True)
    except ImportError:
        print("easyocr not installed: pipeline runs without OCR rerank")
        pipe = Pipeline(run, use_ocr=False)
    (work / "crops").mkdir(exist_ok=True)
    for i, p in enumerate(todo):
        r = pipe.run(cv2.imread(str(p)))
        cv2.imwrite(str(work / "crops" / f"{p.stem}.jpg"), pipe.last_crop, [cv2.IMWRITE_JPEG_QUALITY, 90])
        out[p.name] = {"id": file_id(p), "run": run, **r.__dict__}
        print(f"[pipeline {i + 1}/{len(todo)}] {p.name}: {r.top5_reranked[0]} (ocr {r.number}/{r.total})", flush=True)
        save_json(out_p, out)
    return out


# ---------------------------------------------------------------- 3. agreement + review page
def thumb(work: Path, card) -> str:
    rel = f"thumbs/{card.key.replace('/', '_')}.jpg"
    dst = work / rel
    if not dst.exists():
        dst.parent.mkdir(exist_ok=True)
        from synth import load_ref_rgba
        r = load_ref_rgba(str(card.ref_path))
        a = r[..., 3:4].astype(np.float32) / 255
        bgr = (r[..., :3] * a + 255 * (1 - a)).astype(np.uint8)
        cv2.imwrite(str(dst), cv2.resize(bgr, (180, 248), interpolation=cv2.INTER_AREA), [cv2.IMWRITE_JPEG_QUALITY, 85])
    return rel


def step_review(photos: list[Path], work: Path):
    cards = load_manifest(include_holdout=True)
    by_key = {c.key: c for c in cards}
    lab = load_json(work / "labeler.json", {})
    pipe = load_json(work / "pipeline.json", {})
    choices = load_json(work / "review_choices.json", {})
    labels, review = {}, []
    for p in photos:
        L, P = lab.get(p.name), pipe.get(p.name)
        if not L or not P:
            print(f"skip {p.name}: run label + pipeline first")
            continue
        lk, pk = L["match"]["key"], P["top5_reranked"][0]
        if lk is not None and lk == pk:
            labels[p.name] = {"key": lk, "status": "auto"}
            continue
        c = choices.get(p.name)
        if c:
            if c["choice"] in ("not_in_scope", "unclear"):
                labels[p.name] = {"key": None, "status": c["choice"]}
            else:
                labels[p.name] = {"key": c["choice"], "status": "reviewed"}
        else:
            labels[p.name] = {"key": None, "status": "pending"}
        review.append((p, L, P))
    save_json(work / "labels.json", labels)

    items = []
    for p, L, P in review:
        cands = []
        keys = list(dict.fromkeys(P["top5_reranked"] + ([L["match"]["key"]] if L["match"]["key"] else [])))
        for k in keys:
            c = by_key[k]
            cands.append({"key": k, "name": c.name, "num": f"{c.local_id}/{c.official}", "set": c.group,
                          "thumb": thumb(work, c), "labeler": k == L["match"]["key"],
                          "rank": P["top5_reranked"].index(k) + 1 if k in P["top5_reranked"] else None})
        r = L["reading"]
        items.append({
            "file": p.name, "photo": Path(os.path.relpath(p, work)).as_posix(),
            "crop": f"crops/{p.stem}.jpg",
            "labeler": f"{r['name'] or '?'} · {r['number'] or '?'}/{r['total'] or '?'} · {r['language']} → "
                       f"{L['match']['key'] or 'no match'} ({L['match']['how']})",
            "ocr": f"{P['number'] or '-'}/{P['total'] or '-'}", "detected": P["found"], "cands": cands,
        })
    all_keys = [{"key": c.key, "label": f"{c.key} · {c.name} · {c.local_id}/{c.official}"} for c in cards]
    page = REVIEW_TEMPLATE.replace("__DATA__", json.dumps(items, ensure_ascii=False)) \
        .replace("__KEYS__", json.dumps(all_keys, ensure_ascii=False)) \
        .replace("__STORE__", html.escape(f"cardindex-review:{work.name}"))
    (work / "review.html").write_text(page, encoding="utf-8")
    n = defaultdict(int)
    for v in labels.values():
        n[v["status"]] += 1
    print(f"labels.json: {dict(n)}; review.html lists {len(items)} photos -> {work / 'review.html'}")
    return labels


# ---------------------------------------------------------------- 4. eval
def step_eval(photos: list[Path], work: Path, out_name: str | None):
    cards = load_manifest(include_holdout=True)
    by_key = {c.key: c for c in cards}
    labels = load_json(work / "labels.json", {})
    pipe = load_json(work / "pipeline.json", {})
    status = defaultdict(int)
    rows = []
    for p in photos:
        l = labels.get(p.name)
        status[l["status"] if l else "unlabelled"] += 1
        if not l or not l["key"] or p.name not in pipe:
            continue
        P = pipe[p.name]
        rows.append((by_key[l["key"]], P, l["status"]))
    res = {"photos": len(photos), "label_status": dict(status), "n_evaluated": len(rows)}

    def acc(rs):
        if not rs:
            return {"n": 0}
        return {"n": len(rs),
                "detected": float(np.mean([P["found"] for _, P, _ in rs])),
                "top1_embedding": float(np.mean([P["top5"][0] == c.key for c, P, _ in rs])),
                "top5_embedding": float(np.mean([c.key in P["top5"] for c, P, _ in rs])),
                "top1_after_rerank": float(np.mean([P["top5_reranked"][0] == c.key for c, P, _ in rs])),
                "ocr_read_rate": float(np.mean([P["number"] is not None for _, P, _ in rs]))}

    res["all"] = acc(rows)
    res["trained_sets"] = acc([r for r in rows if not r[0].holdout])
    res["unseen_sets"] = acc([r for r in rows if r[0].holdout])  # e.g. de/30th: index only, never trained on
    res["by_lang"] = {lg: acc([r for r in rows if r[0].lang == lg]) for lg in sorted({r[0].lang for r in rows})}
    res["by_set"] = {g: acc([r for r in rows if r[0].group == g]) for g in sorted({r[0].group for r in rows})}
    res["reviewed_only"] = acc([r for r in rows if r[2] == "reviewed"])
    pending = status.get("pending", 0)
    if rows and pending:
        res["note"] = (f"{pending} photos still pending review. Auto-accepted photos are pipeline-correct by "
                       "construction, so accuracy is an upper bound until all disagreements are reviewed. "
                       f"Lower bound (pending counted as errors): "
                       f"{sum(P['top5_reranked'][0] == c.key for c, P, _ in rows) / (len(rows) + pending):.3f}")
    print(json.dumps(res, indent=1))
    save_json(work / "eval.json", res)
    if out_name:
        RESULTS_DIR.mkdir(exist_ok=True)
        save_json(RESULTS_DIR / out_name, res)
    return res


# ---------------------------------------------------------------- synthetic helpers
def make_dryrun(dst: Path, n: int, seed: int, sources=("test", "test30th")):
    """Copy n synthetic test photos with random names; truth goes to _truth.json (ignored by the flow)."""
    labels = [dict(l, file=f"{src}/{l['file']}") for src in sources
              for l in json.loads((SYNTH_DIR / src / "labels.json").read_text())]
    rng = np.random.default_rng(seed)
    pick = rng.choice(len(labels), n, replace=False)
    dst.mkdir(parents=True, exist_ok=True)
    truth = {}
    for i in pick:
        name = f"{uuid.UUID(int=int(rng.integers(0, 2**63)) << 64 | int(rng.integers(0, 2**63))).hex[:10]}.jpg"
        shutil.copy(SYNTH_DIR / labels[i]["file"], dst / name)
        truth[name] = labels[i]["key"]
    save_json(dst / "_truth.json", truth)
    print(f"{n} synthetic photos -> {dst}")


def truthcheck(photos_dir: Path, work: Path, out_name: str | None):
    """Synthetic folders only: how good are labeler, agreement and auto-labels against the known truth?"""
    from metrics import error_kind
    cards = load_manifest(include_holdout=True)
    by_key = {c.key: c for c in cards}
    truth = load_json(photos_dir / "_truth.json", {})
    lab = load_json(work / "labeler.json", {})
    pipe = load_json(work / "pipeline.json", {})
    n = len(truth)
    lab_keys = {f: lab[f]["match"]["key"] for f in truth if f in lab}
    pipe_keys = {f: pipe[f]["top5_reranked"][0] for f in truth if f in pipe}
    labeled = [f for f, k in lab_keys.items() if k]
    lab_ok = [f for f in labeled if lab_keys[f] == truth[f]]
    agree = [f for f in truth if lab_keys.get(f) and lab_keys[f] == pipe_keys.get(f)]
    agree_wrong = [f for f in agree if lab_keys[f] != truth[f]]
    lab_wrong_kinds = defaultdict(int)
    for f in labeled:
        if lab_keys[f] != truth[f]:
            lab_wrong_kinds[error_kind(by_key[truth[f]], by_key[lab_keys[f]])] += 1
    per_lang = defaultdict(lambda: [0, 0, 0])
    for f in truth:
        lg = by_key[truth[f]].group if by_key[truth[f]].holdout else by_key[truth[f]].lang
        per_lang[lg][0] += 1
        per_lang[lg][1] += bool(lab_keys.get(f))
        per_lang[lg][2] += lab_keys.get(f) == truth[f]
    res = {
        "n": n,
        "labeler_coverage": len(labeled) / n,
        "labeler_precision": len(lab_ok) / max(len(labeled), 1),
        "labeler_accuracy_overall": len(lab_ok) / n,
        "labeler_wrong_kinds": dict(lab_wrong_kinds),
        "labeler_by_lang": {k: {"n": v[0], "coverage": v[1] / v[0], "correct": v[2] / v[0]} for k, v in per_lang.items()},
        "pipeline_top1": float(np.mean([pipe_keys.get(f) == truth[f] for f in truth])),
        "pipeline_top1_trained_sets": float(np.mean([pipe_keys.get(f) == truth[f] for f in truth
                                                      if not by_key[truth[f]].holdout] or [np.nan])),
        "pipeline_top1_unseen_sets": float(np.mean([pipe_keys.get(f) == truth[f] for f in truth
                                                     if by_key[truth[f]].holdout] or [np.nan])),
        "auto_accepted": len(agree),
        "auto_accepted_rate": len(agree) / n,
        "auto_accepted_wrong (shared error)": len(agree_wrong),
        "shared_error_rate_of_auto": len(agree_wrong) / max(len(agree), 1),
        "to_review": n - len(agree),
    }
    print(json.dumps(res, indent=1))
    if out_name:
        RESULTS_DIR.mkdir(exist_ok=True)
        save_json(RESULTS_DIR / out_name, res)
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("step", choices=["label", "pipeline", "review", "eval", "all", "make-dryrun", "truthcheck"])
    ap.add_argument("--photos", type=Path, default=REAL_DIR)
    ap.add_argument("--run", default=DEFAULT_RUN)
    ap.add_argument("--n", type=int, default=30, help="make-dryrun: number of synthetic photos")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--sources", nargs="*", default=["test", "test30th"], help="make-dryrun: synthetic sets")
    ap.add_argument("--results-name", default=None, help="also write the summary to ml/results/<name>")
    args = ap.parse_args()

    if args.step == "make-dryrun":
        make_dryrun(args.photos, args.n, args.seed, args.sources)
        return
    work = DATA_DIR / f"{args.photos.name}-eval"
    if not args.photos.exists() or not photos_in(args.photos):
        args.photos.mkdir(parents=True, exist_ok=True)
        print(f"No photos in {args.photos}. Drop phone photos there (any file names) and run again.")
        if args.results_name:
            RESULTS_DIR.mkdir(exist_ok=True)
            save_json(RESULTS_DIR / args.results_name, {"photos": 0, "note": f"{args.photos} is empty"})
        return
    work.mkdir(parents=True, exist_ok=True)
    photos = photos_in(args.photos)
    print(f"{len(photos)} photos, work dir {work}")
    if args.step in ("label", "all"):
        step_label(photos, work)
    if args.step in ("pipeline", "all"):
        step_pipeline(photos, work, args.run)
    if args.step in ("review", "all"):
        step_review(photos, work)
    if args.step in ("eval", "all"):
        step_eval(photos, work, args.results_name)
    if args.step == "truthcheck":
        truthcheck(args.photos, work, args.results_name)


REVIEW_TEMPLATE = r"""<!doctype html>
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Card label review</title>
<style>
:root{--bg:#f6f6f4;--fg:#1d1d1f;--muted:#6b6b70;--card:#fff;--line:#ddd;--acc:#2f6fed;--ok:#1f9d55;--warn:#c27c0e}
@media (prefers-color-scheme:dark){:root{--bg:#141416;--fg:#ececef;--muted:#9a9aa2;--card:#1f1f23;--line:#333;--acc:#6b9bff;--ok:#43c47a;--warn:#e0a640}}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.4 system-ui,sans-serif}
header{position:sticky;top:0;background:var(--bg);border-bottom:1px solid var(--line);padding:10px 16px;display:flex;gap:12px;align-items:center;flex-wrap:wrap;z-index:2}
header b{font-size:16px}button{font:inherit;padding:6px 12px;border-radius:6px;border:1px solid var(--line);background:var(--card);color:var(--fg);cursor:pointer}
button.primary{background:var(--acc);color:#fff;border-color:var(--acc)}
main{padding:16px;display:grid;gap:16px;max-width:1300px;margin:auto}
.item{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px;display:grid;grid-template-columns:auto auto 1fr;gap:12px}
.item.done{border-color:var(--ok)}.photos img{max-height:300px;max-width:260px;border-radius:6px;display:block}
.meta{color:var(--muted);font-size:13px;grid-column:1/-1}.cands{display:flex;flex-wrap:wrap;gap:8px;align-content:flex-start}
.cand{width:120px;border:2px solid transparent;border-radius:8px;padding:4px;cursor:pointer;text-align:center;font-size:12px}
.cand img{width:110px;border-radius:5px}.cand.sel{border-color:var(--ok)}.cand .lab{color:var(--warn);font-weight:600}
.extra{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-top:8px}.extra input{width:320px;max-width:100%;padding:6px;border-radius:6px;border:1px solid var(--line);background:var(--card);color:var(--fg)}
.chosen{font-weight:600;color:var(--ok)}
@media (max-width:760px){.item{grid-template-columns:1fr}.photos img{max-width:100%}}
</style></head><body>
<header><b>Card label review</b><span id="progress"></span>
<button class="primary" id="export">Export review_choices.json</button>
<span style="color:var(--muted)">Save the file into the same folder as this page, then run <code>eval_real.py review</code> and <code>eval</code>.
If the right card is not shown, type its key under "other card": "unclear" drops the photo from the score and makes it look better than it is.</span></header>
<main id="list"></main>
<datalist id="keys"></datalist>
<script>
const DATA = __DATA__; const KEYS = __KEYS__; const STORE = "__STORE__";
let choices = {}; try { choices = JSON.parse(localStorage.getItem(STORE) || "{}"); } catch (e) {}
function save(){ try { localStorage.setItem(STORE, JSON.stringify(choices)); } catch (e) {} render(); }
const dl = document.getElementById("keys"); KEYS.forEach(k => { const o = document.createElement("option"); o.value = k.key; o.label = k.label; dl.appendChild(o); });
function el(tag, attrs, ...kids){ const e = document.createElement(tag); Object.assign(e, attrs || {}); kids.forEach(k => e.append(k)); return e; }
function render(){
  const list = document.getElementById("list"); list.innerHTML = "";
  let done = 0;
  DATA.forEach(it => {
    const c = choices[it.file]; if (c) done++;
    const box = el("div", {className: "item" + (c ? " done" : "")});
    box.append(el("div", {className: "photos"}, el("img", {src: it.photo, alt: "photo", loading: "lazy"})));
    box.append(el("div", {className: "photos"}, el("img", {src: it.crop, alt: "rectified crop", loading: "lazy"})));
    const right = el("div");
    const cands = el("div", {className: "cands"});
    it.cands.forEach(k => {
      const d = el("div", {className: "cand" + (c && c.choice === k.key ? " sel" : ""), title: k.key},
        el("img", {src: k.thumb, alt: k.key, loading: "lazy"}),
        el("div", {}, (k.rank ? "#" + k.rank + " " : "") + k.name),
        el("div", {}, k.num + " · " + k.set));
      if (k.labeler) d.append(el("div", {className: "lab", textContent: "labeler"}));
      d.onclick = () => { choices[it.file] = {choice: k.key}; save(); };
      cands.append(d);
    });
    right.append(cands);
    const inp = el("input", {placeholder: "other card: type key, e.g. de/sv03.5-006"}); inp.setAttribute("list", "keys");
    const ok = el("button", {textContent: "use typed key"});
    ok.onclick = () => { if (KEYS.some(k => k.key === inp.value)) { choices[it.file] = {choice: inp.value}; save(); } else alert("unknown key"); };
    const nis = el("button", {textContent: "not in scope"}); nis.onclick = () => { choices[it.file] = {choice: "not_in_scope"}; save(); };
    const unc = el("button", {textContent: "unclear"}); unc.onclick = () => { choices[it.file] = {choice: "unclear"}; save(); };
    const clr = el("button", {textContent: "reset"}); clr.onclick = () => { delete choices[it.file]; save(); };
    right.append(el("div", {className: "extra"}, inp, ok, nis, unc, clr));
    if (c) right.append(el("div", {className: "chosen", textContent: "chosen: " + c.choice}));
    box.append(right);
    box.append(el("div", {className: "meta", textContent: it.file + " · labeler: " + it.labeler + " · pipeline OCR: " + it.ocr + (it.detected ? "" : " · card NOT detected (centre crop)")}));
    list.append(box);
  });
  document.getElementById("progress").textContent = done + " / " + DATA.length + " reviewed";
}
document.getElementById("export").onclick = () => {
  const blob = new Blob([JSON.stringify(choices, null, 1)], {type: "application/json"});
  const a = el("a", {href: URL.createObjectURL(blob), download: "review_choices.json"}); document.body.append(a); a.click(); a.remove();
};
render();
</script></body></html>
"""

if __name__ == "__main__":
    main()
