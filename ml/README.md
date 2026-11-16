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
uv sync --extra ocr --extra labeler   # torch CUDA 13.0 wheels, timm, OpenCV, Kornia, EasyOCR, transformers
```

`ocr` (EasyOCR) and `labeler` (transformers + bitsandbytes for the VLM) are optional. The core steps don't need
them.

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
