<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Recognition prototype: results (issue #7)

Date: 2026-11-16. Hardware: RTX 3080 10 GB (shared with other local apps), Windows 11. All numbers come from
the files in [`results/`](results/). Unless a section says otherwise, the test data is **synthetic**: phone-photo
renders of the TCGdex reference images. The real-photo folder is still empty, see [Real photos](#real-photos).

## Scope and data

| Set | Cards | Role |
|---|---|---|
| `en/sv03.5` 151 | 207 | trained |
| `de/sv03.5` 151 (German) | 207 | trained |
| `ja/SV2a` 151 (Japanese) | 210 | trained |
| `en/me03` Perfect Order | 124 | trained |
| `en/me04` Chaos Rising | 122 | trained |
| `de/30th` 30 Jahre | 158 of 161 | **unseen**: never trained on, only added to the index |

Three `de/30th` cards (the B/G/R "RGB Rare" Mew) have an `image` field in the API, but the CDN returns 404 for every
format, so they were skipped. Test sets: 5 renders per card with OpenCV on the CPU (fixed seed 20261116). That
gives 4,350 images for the trained sets and 790 for `de/30th`, with the same generator and settings. The renders
add random perspective and rotation, backgrounds, shadow, sleeve, glare and specular streaks, a thumb, colour
temperature, motion or defocus blur, noise and JPEG artifacts. Model selection used a separate val set
(1 render per card, seed 99).

## Results

### Detection (classic CV: edges and contours, best quad, side snapping, warp to 63:88)

| Test set | Card found | IoU ≥ 0.9 | IoU ≥ 0.8 | Median IoU | Median corner error |
|---|---|---|---|---|---|
| Trained sets (4,350) | 98.9 % | 66.3 % | 87.9 % | 0.930 | 2.1 % of the diagonal |
| `de/30th` (790) | 98.4 % | 60.6 % | 87.5 % | 0.924 | 2.3 % |

Speed is ~78 ms per frame on one x86 core at 640 px. Typical failures: the quad locks onto the inner art frame of
silver-bordered cards on light backgrounds, a shadow or distractor merges with the card edge, or a thumb cuts
the outline.

### Recognition: top-1 / top-5 on the synthetic test set

"Rerank" is the simulated collector-number rerank of the top-5: the true number is read, except 20 % unreadable
and 5 % with one wrong digit. **Main** means the trained sets against the 870-card gallery. **Unseen** means
`de/30th` against the full 1,028-card gallery (870 + 158).

| Model | Main top-1 | Main top-5 | Main + rerank | DE | EN | JA | Unseen top-1 | Unseen top-5 | Unseen + rerank |
|---|---|---|---|---|---|---|---|---|---|
| pHash 16×16 | 69.8 | 83.1 | 71.0 | 59.1 | 70.9 | 77.8 | 79.4 | 81.6 | 81.0 |
| MobileNetV3-L (ImageNet, zero-shot) | 61.0 | 84.8 | 66.4 | 37.4 | 63.4 | 79.0 | 76.7 | 86.3 | 84.1 |
| EfficientNet-Lite0 (zero-shot) | 50.1 | 75.1 | 57.1 | 33.8 | 52.9 | 60.1 | 65.3 | 79.2 | 75.2 |
| EfficientNet-B0 (zero-shot) | 56.7 | 80.8 | 62.8 | 31.6 | 60.2 | 74.1 | 63.3 | 76.3 | 72.9 |
| DINOv2 ViT-S/14 (zero-shot) | 59.3 | 86.6 | 63.7 | 39.8 | 59.2 | 78.9 | 76.7 | 87.2 | 85.4 |
| **MobileNetV3-L + ArcFace, 256-d** | **94.6** | **95.3** | **95.0** | 94.2 | 94.0 | 96.1 | **94.3** | **95.6** | **95.1** |
| MobileNetV3-L + ArcFace, 128-d | 94.4 | 95.1 | 94.7 | 94.5 | 93.7 | 95.9 | 95.3 | 96.5 | 95.8 |

The fine-tuned rows after rerank, by language (256-d): DE 94.6 %, EN 94.5 %, JA 96.4 %. With a *perfect*
number read, main top-1 is 95.2 %. Using the ArcFace class weights as the index instead of reference
embeddings gives 94.8 / 95.7 %, but that only works for trained cards.

**Errors split (top-1, main, 4,350 images)**

| Model | Same art, other language | Same Pokémon, other card | Other |
|---|---|---|---|
| MobileNetV3-L zero-shot | 626 (14.4 % of images) | 71 | 1,000 |
| pHash | 471 (10.8 %) | 35 | 808 |
| **MobileNetV3-L ArcFace 256-d** | **21 (0.5 %)** | 0 | 216 |

So for the fine-tuned model, language is the only error in 0.5 % of images (9 % of its errors). The collector
number cannot fix this case, because EN and DE prints share the number.

**Where the remaining errors come from.** The fine-tuned 256-d model gets **99.97 %** top-1 (unseen set:
99.7 %) whenever the detected quad has IoU ≥ 0.8. With IoU 0.5–0.8 it gets 85 %, and below 0.5 it gets 21 %.
236 of the 237 main errors have IoU < 0.8 (`results/eval_mnv3_256_by_detection.json`). **The embedding is not the
bottleneck. Detection is.**

### Unseen set: can a new set be supported by only adding its embeddings?

**Yes, on this data.** `de/30th` was never trained on, but it reaches 94.3 % top-1 / 95.6 % top-5 (95.1 % after
rerank) against the full 1,028-card gallery. That is within 0.3 pp of the trained sets (94.6 %). It is also limited
by detection in the same way (99.7 % with a good quad). Adding the 158 unseen cards to the gallery barely changes
the trained sets: 94.55 → 94.51 % top-1, 94.97 → 94.87 % after rerank.

Caveat: `de/30th` has no language twin in the gallery, because its EN print is not included. Language twins are
the hard case (14 % of zero-shot images, 0.5 % after fine-tuning). It is still untested whether the fine-tuned
model separates an EN/DE pair *of a set it has never seen*. Before relying on index-only updates for sets that
come in several languages, test this by holding out one EN/DE pair (e.g. `en/me04` + a future `de/me04`).

### Fine-tuning

- MobileNetV3-Large (`mobilenetv3_large_100.ra_in1k`) → linear 256 → BN → L2. ArcFace (s=30, m=0.3, label
  smoothing 0.05), one class per card (870), AdamW with cosine schedule, 20 epochs of 32 samples per class,
  batch 128, AMP.
- **Training time: 18.2 min** for 555k augmented samples. **Throughput: 521 samples/s** (augment, forward and
  backward). The augmentation takes ~77 % of that time. **GPU utilisation: 74 % mean / 75 % median**
  (nvidia-smi every 30 s), at most 7.5 GB of VRAM in use on the card. The 128-d run took 25.5 min, but it shared
  the GPU with the VLM labeler, so its time is not comparable.
- Val top-1 plateaus at ~92.5–93 % after 3–4 epochs (val uses its own renders). 8–10 epochs would very likely be
  enough.

**Change of plan, decided during the run: on-the-fly GPU augmentation instead of CPU pre-rendering.** The first
plan pre-rendered 48 training crops per card on the CPU (OpenCV renderer and the real detector, `trainset.py`).
That ran at only ~14 images/s on 13 cores and would have taken about an hour, and a fixed set of renders gives
much less variety than fresh samples every step. Training now renders every sample on the GPU (`gpu_aug.py`,
Kornia and torch: perspective, background-texture pool, shadow, sleeve, glare, streaks, thumb, colour,
motion/defocus blur, noise, JPEG on the crop, re-rectification with residual corner error). The reference images
live on the GPU as a uint8 tensor (~310 MB). The CPU renderer is still used for the val and test sets, so train
and test come from **different renderers**, which makes the test a fairer check. The 15.7k crops that were
already pre-rendered stay in the data directory, unused.

Two performance pitfalls came up and were fixed:

1. `channels_last` together with BatchNorm in train mode was ~10× slower on torch 2.14 / cu130 (0.73 vs.
   0.07 s per step).
2. cuDNN autotuning on the random-size sub-batches of the blur and JPEG steps. Autotuning is now switched off
   inside the augmentation.

### Export (LiteRT via `litert-torch`, run in WSL because `litert-converter` has no Windows wheels)

The input is `1×224×224×3` float32 RGB in [0, 1] with normalization built in; the output is the L2-normalized
embedding. Checked on 300 test crops; the index for each row is built with that same model.

| Variant | Size | Cosine vs. PyTorch (mean / min) | Top-1 (subset) | x86 CPU, 8 threads |
|---|---|---|---|---|
| PyTorch | — | — | 95.0 % | — |
| **float32 `.tflite`** | **18.2 MB** | **1.0000 / 1.0000 ✓** | 95.0 % | 3.6 ms |
| int8 weights, float activations (`dynamic_wi8_afp32`) | **5.0 MB** | 0.965 / — | 95.0 % | 12.1 ms |
| full int8 (PT2E static, per-channel) | 5.2 MB | 0.913 / 0.588 | 93.7 % | 7.2 ms |

Full int8 hurts MobileNetV3 (hard-swish and squeeze-excite layers), while int8 weights alone keep the accuracy.
The x86 latency is not representative of a phone.

**Index size** (one vector per card):

| Gallery | float32 | float16 | int8 |
|---|---|---|---|
| 870 cards × 256 | 870 KB | 435 KB | 218 KB |
| 1,028 cards × 256 | 1,028 KB | 514 KB | 257 KB |

### OCR of the collector number (EasyOCR as a stand-in for ML Kit)

On the synthetic frames (720×960, where the number is ~6 px tall) EasyOCR reads a number on only 29 % of
images. It is correct on 23 %, and 23 % of the numbers it reads are wrong. Rerank with it does not help
(93.8 → 93.7 % on the subset). On `de/30th` it reads 23 %. Even simulated, the rerank adds only ~0.4 pp, because
the remaining errors are bad crops, and language twins share the number.

## Real photos

`D:\AI\pokemon-cards-data\real-photos\` is **empty**, so there are no real-photo numbers yet. The flow is ready
and was tested end to end:

- **Auto-labelling** (`eval_real.py`): a local VLM (Qwen3-VL-4B-Instruct, 4-bit NF4, ~3.2 GB VRAM, ~13–15 s per
  photo here) reads the name, collector number and language from the **raw photo**. `labeler.py` matches that
  against the manifest, which includes `de/30th`. When it agrees with the pipeline's top-1, the label is accepted.
  Everything else goes to an offline `review.html`. The report splits accuracy into trained sets and the unseen
  set.
- **Labeler quality on 200 synthetic photos with known truth** (170 from trained sets, 30 from `de/30th`):
  - The labeler commits to a label for 64 % of photos and is right on **94.5 %** of those (7 wrong:
    5 other, 2 same Pokémon).
  - Coverage by language: EN 74 %, DE 58 %, JA 55 %, `de/30th` 57 %. It usually fails because it cannot read
    the tiny number on the 720×960 synthetic frames; 12 MP phone photos should do better.
  - **58 % of photos were auto-accepted, with 0 shared errors** (labeler and pipeline wrong in the same way)
    among those 116. A rough 95 % upper bound for the shared-error rate is 3/116 ≈ 2.6 %. If the two errors
    were independent, the rate would be ≈ 5.5 % × 4.5 % × P(same wrong card) ≪ 0.3 %. The other 42 % need
    a click in the review page.
- **Dry run on 30 synthetic photos** with random file names, `real-photos-dryrun`: 17 auto-accepted (0 wrong) and
  13 sent to `review.html`. The review round trip (export `review_choices.json`, then `review`, then `eval`) was
  tested on the 200-photo folder with simulated choices.
- **Pitfall:** if the right card is not among the candidates and the reviewer picks "unclear" instead of typing
  the key, that photo leaves the score and the accuracy looks better than it is. In the simulation, 4 such photos
  turned 95.5 % into 97.4 %. The page now says so.

## Recommendation for the app

1. **Embedding model:** MobileNetV3-Large + ArcFace, **256-d** (128-d is equal within noise and could halve the
   index later). 224 px square input, fine-tuned on GPU-synthetic renders. Ship the **int8-weight LiteRT model
   (5 MB)**, or float16 if the GPU delegate wants it. The verified float32 model is 18 MB. Do not use full-int8
   MobileNetV3 without quantization-aware training.
2. **Index:** float16, ~0.5 KB per card (~0.5 MB for 1,028 cards, ~20 MB for 40k cards across languages, split
   into language packs per ADR 0003).
3. **Expected accuracy (synthetic):** ~94.5 % top-1 end to end per single frame, and ~99.9 % when the card quad is
   good. In the app, accept a match only when the quad is good and the top-1 similarity margin is large, and
   combine 2–3 consecutive frames. Unsure frames go to the review queue as planned.
4. **Detector: yes, replace the classic CV.** It finds the card 98.9 % of the time, but the quad is too
   imprecise on ~12 % of frames, and those cause nearly all recognition errors. At ~78 ms per frame on a desktop
   core it is also slow. Train a small learned detector that predicts the four corners (YOLO-n pose with 4
   keypoints, or a segmentation head). The GPU renderer produces exact corner labels for free. It was not
   trained in this run.
5. **New sets:** adding embeddings to the index works without retraining (unseen-set gap ≤ 0.3 pp). Retrain
   periodically anyway, and test EN/DE twins of an unseen set first (see caveat above).
6. **OCR:** keep the number rerank, but treat it mainly as a confidence signal. Its accuracy gain is small here.
   Test ML Kit on real phone frames; EasyOCR is weak at this resolution.

## Open risks

- **Everything is synthetic.** Real holo, reverse-holo and textured foils, toploaders, worn cards, phone ISP
  processing and motion are not covered. The real-photo numbers are the deciding test.
- Language twins of an unseen set (see above).
- Detection quality on real backgrounds, and upside-down cards (the pipeline tries both 180° orientations).
- Full-int8 quantization loses accuracy; on-device latency and delegates were not measured.
- The VLM labeler is slow (~15 s per photo) and covers only ~60 % of photos at synthetic resolution. Real photos
  may need more manual review than the 42 % seen here.
- Three `de/30th` cards have no CDN image, and JP coverage on TCGdex is incomplete (DESIGN.md).
- The tooling depends on WSL for export (`litert-converter`), and EasyOCR and the CUDA stack are Windows-specific
  installs.

## Reproduce

See [README.md](README.md). Data and checkpoints are in `D:\AI\pokemon-cards-data\`, not in the repo.
