<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Card recognition prototype (issue #7)

This prototype measures how well the planned on-device pipeline works (detect, embed, kNN, collector-number
rerank) and which model to use. Results are in [REPORT.md](REPORT.md).

**Data rule:** card images, renders, checkpoints, indexes and exported models stay outside the repo, in
`D:\AI\pokemon-cards-data\` (set `CARDINDEX_DATA` to change it). The card art is © The Pokémon Company. The repo
holds only code, `manifest.csv` (ids, numbers, names, image URLs), and small result files in `results/`.

## Setup

```powershell
cd ml
uv sync --extra ocr --extra labeler --extra litert   # torch CUDA 13.0 wheels, timm, OpenCV, Kornia, ...
```

`ocr` (EasyOCR), `labeler` (transformers + bitsandbytes for the VLM) and `litert` (runs exported `.tflite` files on
Windows) are optional. The core steps don't need them.

## Steps

| # | Command | What it does |
|---|---|---|
| 1 | `uv run python fetch.py` | Downloads the 5 TCGdex set lists, the card details and the 870 `high.png` images into `<data>/api` and `<data>/ref`, then writes `manifest.csv`. Uses at most 4 parallel requests and skips anything already cached. If `high.png` is missing on the CDN, it falls back to `high.webp`. |
| 2 | `uv run python synth.py` | Renders the held-out synthetic **test** set: 5 photos per card, seed 20261116, OpenCV on the CPU, in `<data>/synth/test`. `--name val --per-card 1 --seed 99` renders the val set. |
| 3 | `uv run python detect.py [--name val]` | Runs classic-CV card detection and rectification. Measures the found rate, IoU and corner error against the true placement. Writes the crops to `<data>/synth/<name>-crops` and the summary to `results/detection_test.json`. |
| 4 | `uv run python baselines.py` | Zero-shot baselines on the test crops: pHash and pretrained MobileNetV3-L, EfficientNet-Lite0/B0 and DINOv2-S. Writes `results/baselines.json`. |
| 5 | `uv run python train.py --run mnv3_256` | Trains an ArcFace embedding model. Training images are generated **on the GPU** by `gpu_aug.py` (Kornia), so the CPU does almost nothing. The script saves an augmentation grid to `<data>/aug_grid_<run>.png` and writes `results/train_<run>.json`, which includes throughput and GPU utilisation. `--holdout en/me04` leaves a set out to test unseen cards. |
| 6 | `uv run python evaluate.py --run mnv3_256 --ocr-every 5` | Test-set top-1/top-5 by set and language, error categories, the rerank with simulated OCR (20 % unreadable, 5 % wrong digit), and the rerank with real OCR (EasyOCR). Writes `results/eval_<run>.json`. |
| 7 | see below | Exports to LiteRT (float32 and int8), then checks cosine similarity against PyTorch and top-1. Writes `results/export_<run>.json`. |
| 8 | `uv run python eval_real.py all` | Evaluates real photos with auto-labelling (see below). |
| 9 | `uv run python train.py --run mnv3_256_real --realistic --init mnv3_256 --epochs 10 --lr 5e-4` | Fine-tunes the embedding with the real-photo effects of `real_aug.py` (white balance 2700–7500 K, low light + phone ISP, holo sheen, broad sleeve glare, pocket margins, imprecise and partial crops). |
| 10 | `uv run python train_corners.py --run corners_mnv3` | Trains the learned corner detector on GPU-synthetic scenes (`corner_scenes.py`: binder pages, sleeves, toploaders, loose cards, fingers). Also scores the classic detector on the same fixed test scenes. |
| 11 | `uv run --extra litert python eval_real_manual.py --all` | Real photos with manual labels: old/new embedding × classic/learned detector, exactly as the Android test app runs it (guide crop, `.tflite` w8). |
| 12 | `uv run --extra litert python evaluate.py --run mnv3_256_real [--detector learned]` | Synthetic test sets incl. the language-twin case `de/me04`; `--detector learned` crops with the corner model. |
| 13 | `python eval_forb.py map`, then `eval --detector learned` | FORB real photos (EN, eBay) against the full EN pack gallery from `build_packs.py embed`. Images in `<data>/external/forb` (FORB zip, `animated_cards/` only). Writes `results/forb_<model>_<detector>.json`. |
| 14 | `python eval_tcgar.py` | TCG-AR real set: corner error of both detectors per card plus recognition over the EN gallery. Images in `<data>/external/tcg-ar`. Writes `results/tcgar_<model>.json`. |

`trainset.py` is the older CPU pre-render of training crops. It is kept for reference, but training no longer
uses it.

### Export to LiteRT

`litert-torch` depends on `litert-converter`, which has no Windows wheels, so the export runs in WSL:

```powershell
wsl -d Ubuntu -- bash /mnt/d/AI/pokemon-cards/ml/export_env.sh     # once: ~/cardindex-export venv (CPU torch)
wsl -d Ubuntu -- ~/cardindex-export/.venv/bin/python /mnt/d/AI/pokemon-cards/ml/export_litert.py --run mnv3_256
```

The model takes NHWC float32 RGB in [0, 1] (`1×224×224×3`: the rectified card squashed to a square) and returns
the L2-normalized embedding. ImageNet normalization is built into the model.

```powershell
wsl -d Ubuntu -- ~/cardindex-export/.venv/bin/python /mnt/d/AI/pokemon-cards/ml/export_corners.py --run corners_mnv3
```

### Published weights (ADR 0008)

Published weights are `.tflite` (above) and `.safetensors` only, never the `.pt` training checkpoints: a `.pt` file
is a pickle and can run code when loaded. `export_safetensors.py` writes `<data>/export/<run>.safetensors` (the
model's `state_dict`, plus the ArcFace weights under `arc.` for embedding models; backbone, dim, epoch and classes
in the header metadata):

```powershell
uv run python export_safetensors.py --run mnv3_256_real
uv run python export_safetensors.py --run corners_mnv3
```

All scripts load checkpoints with `torch.load(..., weights_only=True)`; checkpoints hold only tensors and plain
types (str, int, list) so that this works.

### Learned corner detector: exact I/O

Files: `<data>/export/corners_mnv3_w8.tflite` (3.5 MB, int8 weights, float activations; recommended) and
`corners_mnv3_f32.tflite` (12.6 MB). Builtin ops only (conv, depthwise conv, hard-swish, nearest resize, softmax,
batch matmul, reshape), no custom ops.

**Input**: one tensor `float32 [1, 256, 192, 3]`, **NHWC**, RGB, values in **[0, 1]** (no mean/std on the app side:
ImageNet normalization is inside the model).

1. Take the camera frame. The prototype resizes it so the long side is 2048 px, but any size works.
2. Cut out the **guide area** exactly like the app does: guide height `gh = 0.78 × frame height`,
   `gw = gh × 63/88`, centred, then add a margin of 7 % of `gw` on the left and right and 7 % of `gh` on the top
   and bottom, and clip to the frame. The result is roughly 0.716 : 1, portrait.
3. Resize the guide crop to **192 wide × 256 high, stretched, no letterbox, no padding**. Use area or bilinear
   resampling. The aspect ratio changes slightly from 0.716 to 0.75, and the model was trained with exactly this
   stretch.

**Output**: one tensor `float32 [1, 4, 3]`. Each row is one corner in this order: **0 = top-left, 1 = top-right,
2 = bottom-right, 3 = bottom-left**, as seen in the image with the card upright (portrait, long side vertical).
For a card lying upside down, "top-left" is still the corner at the top left of the image, so let the embedding
step try both 180° orientations, as the pipeline does. Each row holds:

- `x`, `y`: soft-argmax position, normalized to **[0, 1] of the 192×256 input**. That is the same as [0, 1] of the
  guide crop, because the resize is a plain stretch. Map back with `X = guideLeft + x × guideWidth` and
  `Y = guideTop + y × guideHeight`.
- `conf`: peak probability of that corner's 64×48 heatmap (0..1), not calibrated. Use the mean over the 4 corners
  as a "card present" signal:
  - synthetic scenes without a card: median 0.008, 95th percentile 0.079, max 0.17
  - synthetic scenes with a card: 5th percentile 0.13, median 0.18
  - the 29 real photos: 0.10 to 0.18, median 0.14

  A threshold of about 0.08 to 0.10 (mean of the 4 corners) works as "no card found". It is a soft signal, not a
  guarantee.

The label the detector learned is the **card's printed edge**, not the sleeve, toploader or pocket edge. Then
rectify the quad to 63:88 (the prototype uses 252×352) and feed the embedding model as before.

### Real photos: auto-labelling and review

Put phone photos with any file names into `D:\AI\pokemon-cards-data\real-photos\`. Then run:

```powershell
uv run python eval_real.py all          # = label, pipeline, review, eval
```

1. **label**: A local VLM (Qwen3-VL-4B-Instruct, 4-bit, ~3.5 GB VRAM) reads the name, the collector number
   and the language from the *raw photo*, and `labeler.py` matches that against the manifest. The VLM never
   sees the pipeline's output.
2. **pipeline**: Detect, embed, kNN, then EasyOCR number rerank. The rectified crops go to `crops/`.
3. **review**: When the labeler and the pipeline's top-1 agree on card and language, the label is accepted
   automatically. Every other photo goes to `<data>/real-photos-eval/review.html`: open it in a browser
   (it works offline) and click the right card, or pick "not in scope" / "unclear". Choices are kept in
   localStorage. **Export review_choices.json** and save the file next to `review.html`. Then run
   `eval_real.py review` and `eval_real.py eval` again.
4. **eval**: Accuracy against `labels.json` (auto-accepted plus reviewed). If any photos are still pending, the
   result is only an upper bound, and the script prints a lower bound next to it.

All results are cached per file, so re-runs only process new photos. For a dry run on synthetic photos:
`eval_real.py make-dryrun --photos <dir> --n 30`, then `eval_real.py all --photos <dir>`. On such a folder,
`eval_real.py truthcheck --photos <dir>` measures the labeler's accuracy and how often both sources are wrong
the same way.

### Android test app

`app_assets.py` builds the model, index and card list for the recognition lab in the app, and `app_parity.py` writes
reference results for its parity test. See [`android/README.md`](../android/README.md#recognition-lab-scan-tab).

### Language packs

`build_packs.py` builds the per-language packs the app downloads (issue #5). Format and app-side checks:
[`docs/packs.md`](../docs/packs.md). The steps run where their inputs are and share a work dir you can copy
between machines:

```sh
# 1. TCGdex metadata, any machine, standard library only (cached; set answers refetched after 7 days or when the
#    card count changes)
python build_packs.py fetch --work packs-work                       # all 9 languages; --langs en,de --sets sv03.5
# 2. Embeddings with the app's .tflite model, on the machine with CARDINDEX_DATA (numpy, OpenCV, ai-edge-litert).
#    Rows are cached per model and image hash in <data>/packs-embcache, so unchanged cards stay byte-identical.
python build_packs.py embed --work packs-work --download-images
# 3. Packs, deltas and catalog.json against what is published, any machine, standard library only
python build_packs.py build --work packs-work --out packs-out --base https://<api>/v1/packs
python build_packs.py build ... --upload            # wrangler r2 object put --remote, catalog.json last; --dry-run
```

`--base` defaults to `--out`, which then acts as a local mirror of the bucket: its `catalog.json` is what was last
published. A build writes the new catalog as `catalog.next.json`; only a successful `--upload` turns it into
`catalog.json`, so a local build or a failed upload never makes the next run believe something was published.
`build --no-embeddings` makes
metadata-only packs for testing; it cannot be combined with `--upload`. `all` runs the three steps in a row.

Tests for the pure parts (format, deltas, catalog, build step) need no torch:

```sh
uv run --no-project --with pytest pytest tests
```
