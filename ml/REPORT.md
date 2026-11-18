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

First real-photo results are in [Real photos (2026-11-16)](#real-photos-2026-11-16) below. This section describes
the auto-labelling flow; when it was written, the photo folder was still empty. The flow was tested end to end:

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

## Real photos (2026-11-16)

**Read this with the sample size in mind:** 34 phone photos (Galaxy S25 Ultra), 29 of them labelled by hand:
- 21 German "30 Jahre" (`de/30th`) cards, mostly in binder pockets or sleeves under warm indoor light. 7 of these
  are Pikachu cards whose number was unreadable, so they are scored at name level.
- 8 German "Wachsendes Chaos" (`de/me04`) cards lying on fabric.
- Not scored: the 4 photos with two cards side by side, plus 1 more unlabelled photo.

One photo more or less moves a rate by 3–12 pp, so treat the numbers below as a direction, not an estimate.
`eval_real_manual.py` reproduces the Android test app exactly: resize to 2048 px, centred card guide (63:88 at
78 % of the height, plus 7 % margin), detector inside the guide, `.tflite` w8 embedding (both 180°
orientations), kNN over the full 1,274-card gallery (870 trained cards plus the holdouts `de/30th`, `de/me04`,
`de/me03`). With the old model and the classic detector it reproduces the app's numbers closely: 17 vs. 16 top-1
on 30th, identical on me04.

### What changed

1. **Embedding `mnv3_256_real`**: fine-tuned from `mnv3_256` for 10 epochs (18.5 min, ~255 samples/s, the GPU
   was shared) with realistic augmentation in `real_aug.py`:
   - white balance 2700–7500 K, partly uncorrected
   - exposure changes, low-light shot and chroma noise, phone denoise plus sharpening
   - holo rainbow sheen on the art, reverse-holo sparkle
   - broad sleeve glare and reflections, a translucent sleeve edge, black pocket backgrounds
   - imprecise crops: dark margins of 0–12 % per side, corner jitter up to 5 %, partial crops (8 %)
   - same 870 classes, ArcFace
2. **Learned corner detector `corners_mnv3`**: MobileNetV3-Large features → FPN-lite → 4 corner heatmaps at
   stride 4 → soft-argmax inside the model. 3.1 M parameters, 34 min training.
   - Trained on GPU-synthetic guide-area scenes (`corner_scenes.py`): black 3×3 binder pages with neighbouring
     cards in pockets, a penny sleeve or toploader, loose cards on fabric or table with neighbours side by side
     or on top, fingers, perspective, glare and the camera effects above.
   - The label is always the **card edge**, never the sleeve or pocket.
   - I/O is documented in the README.

### Real photos: old/new embedding × classic/learned detector

Columns: top-1 and top-5 exact; shown = top-1 similarity ≥ 0.5, given as shown / correct / wrong.

| Embedding + detector | 30th top-1 | 30th top-5 | 30th shown | 30th median sim (correct) | me04 DE top-1 | me04 DE top-5 | me04 shown | me04 artwork-level top-1 (EN or DE) |
|---|---|---|---|---|---|---|---|---|
| old `mnv3_256` + classic (current app) | 17/21 | 19/21 | 11 / 11 / 0 | 0.57 | 4/8 | 5/8 | 4 / 4 / 0 | 6/8 |
| old + learned | 17/21 | 20/21 | 14 / 14 / 0 | 0.63 | **8/8** | 8/8 | 8 / 8 / 0 | 8/8 |
| new `mnv3_256_real` + classic | **21/21** | 21/21 | 15 / 15 / 0 | 0.56 | 5/8 | 5/8 | 4 / 4 / 0 | 5/8 |
| **new + learned** | **21/21** | **21/21** | **19 / 19 / 0** | **0.65** | 7/8 | **8/8** | 8 / 7 / **1** | **8/8** |

**Similarity distribution** (`results/real_manual_matrix.json`):

- **New + learned, 30th:** correct matches 0.38–0.79 (median 0.65), no wrong matches at all.
- **Old + classic, 30th:** correct 0.38–0.83 (median 0.57), wrong 0.36–0.50.
- **The one wrong match of new + learned:** a German me04 card matched its **English twin**
  (`en/me04:014`) at **0.87**. That is a confident language error, and no threshold can catch it.

**Threshold sweep for new + learned** (shown / wrong, out of 29):

| Threshold | 0.35 | 0.40 | 0.45 | 0.50 | 0.55 | 0.60 |
|---|---|---|---|---|---|---|
| Shown / wrong | 29 / 1 | 28 / 1 | 27 / 1 | 27 / 1 | 23 / 1 | 21 / 1 |

### Language twins (de/me04 vs. the trained en/me04)

The German and English me04 prints differ more than the first app test suggested. With only the EN twin in the
index, the German Chespin/Igamaro photos reached 0.28–0.40. With `de/me04` indexed, the same model matches them
at 0.70–0.73. The EN twin does **not** stand in for the DE print, so every language a user scans needs its own
index entries (that fits the language packs of ADR 0003).

With both prints in the gallery, the model mostly picks the right language without ever having trained on
`de/me04`:
- **Synthetic, new model + learned detector:** 98.2 % exact. 11 of 610 images (1.8 %) pick the EN twin.
- **Real photos:** 7/8 exact, 8/8 at artwork level.

The remaining errors are confident (0.87 above). They need a rule, not a threshold. See the recommendation.

### Synthetic test sets: does the realistic augmentation cost accuracy?

No. Top-1 (top-5), full gallery, collector-number rerank simulated:

| | Trained sets (870 gallery) | Unseen 30th (1,274 gallery) | Twin de/me04 exact (artwork level) |
|---|---|---|---|
| old + classic detector | 94.6 % (95.3) | 94.2 % (95.4) | 90.2 % (94.3) |
| new + classic detector | 94.8 % (95.3) | 94.3 % (95.3) | 88.9 % (94.4) |
| old + learned detector | **100.0 %** (100.0) | 99.9 % (100.0) | 97.5 % (100.0) |
| new + learned detector | **100.0 %** (100.0) | 99.9 % (100.0) | **98.2 %** (100.0) |

The learned detector removes almost all of the earlier synthetic errors, which were bad quads. It was trained on
the GPU renderer and tested on the independent CPU/OpenCV renderer, though both are synthetic.

On the hard GPU binder, sleeve and loose scenes (2,000 fixed scenes, unseen cards included):

| Detector | Mean corner error | Median corner error | IoU ≥ 0.9 | IoU ≥ 0.8 |
|---|---|---|---|---|
| learned (PyTorch) | 2.2 % of card height | 0.49 % | 79 % | 95.7 % |
| learned (w8) | — | 0.67 % | — | — |
| classic | 33 % | 20 % | 16 % | 31 % |

> **Rerun needed:** these learned-detector numbers come from the checkpoint that was *selected* on the same 2,000
> test scenes (best of the evaluations every 5 epochs), so they are slightly optimistic and not an independent
> test score. `train_corners.py` now selects on a separate validation set (500 scenes, seed 90001, trained cards
> only) and scores the test set once with the selected checkpoint (`test_selected` in
> `results/train_corners_mnv3.json`). The run and `export_corners.py` must be repeated on the training PC to
> replace the rows above; the classic row is unaffected (it is not trained).

The classic detector also finds a card in only 85 % of these scenes. Adding the German twin sets to the gallery
lowers the trained EN sets slightly (EN 94.0 → 90.8 % with the classic detector), because the language twins now
compete.

### Exported files

| File | Size | Parity with PyTorch |
|---|---|---|
| `mnv3_256_real_w8.tflite` | 5.0 MB | cosine mean 0.968; top-1 on the subset 94.3 vs. 95.0 % for float32 |
| `mnv3_256_real_f32.tflite` | 18.2 MB | cosine 1.0000 |
| `corners_mnv3_w8.tflite` | 3.5 MB | mean coordinate difference 0.50 px; max 31 px on one of 2,000 scenes; median corner error 0.67 vs. 0.49 % |
| `corners_mnv3_f32.tflite` | 12.6 MB | max difference 0.005 px |

The detector input is 1×256×192×3 and the output 1×4×3. On x86 it takes 9 ms (w8) or 4 ms (f32) with 4 threads;
phone latency is not measured yet. The index for 1,274 cards × 256 is 637 KB in float16.

### Recommendation

1. **Ship the new embedding and the learned detector together.** On real photos that is 21/21 on 30th and 8/8 at
   artwork level on me04, versus 17/21 and 6/8 today. Use the w8 files (5.0 + 3.5 MB). Keep the classic detector
   only as a fallback when the detector's mean `conf` is below ~0.08–0.10.
2. **Threshold: lower it to 0.45** (from 0.5). On this sample, 0.45 and 0.5 show the same photos. With the new
   models no non-language wrong match scored above 0.35, while correct matches go down to 0.38. 0.45 keeps a
   margin from the old model's wrong matches (up to 0.50). Revisit it with more photos; 0.40 would show one more
   photo here.
3. **Language rule.** When the top-2 are the same card (same `card_id`) in different languages and their
   similarities are within ~0.05, do not decide by similarity:
   - prefer the language of the user's installed pack, or
   - send the scan to the review queue with a language chip.

   Better still, keep only the user's languages in the gallery (ADR 0003). Then the EN/DE confusion cannot occur
   for a DE-only user.
4. **Two cards in the guide** (4 photos, not scored) are not handled. The detector returns one card, so the app
   should ask the user to centre one card.

### Open risks

- **29 scored photos** from one phone, one binder and one room. Untested: other phones, daylight, toploaders and
  graded slabs, worn cards, other sets, and Japanese cards in real photos.
- **Language twins:** confident wrong-language matches still occur (1/8 real, 1.8 % synthetic). This needs the
  language rule above.
- **Int8-weight embedding:** it loses ~0.7 pp top-1 against float32 on the synthetic subset. Check on the phone
  whether float16 is affordable.
- **Phone latency** of the detector (target ≤ 30 ms) and of the embedding has not been measured on the device.

## Public real-photo sets (2026-11-17)

First evaluation on real photos at scale, with the gallery the app actually searches: the **full EN language pack**
(22,213 TCGdex EN cards, model `mnv3_256_real_w8`), not the 870 trained cards. Both sets are used with permission
for eval and training; the images stay in `<data>/external/` and are never committed (research: #48). No number
OCR rerank here, so the app should do somewhat better on cards with a readable number.

### FORB "animated cards" (`eval_forb.py`)

6,025 real photos of EN cards, 97 % from eBay listings: hands, sleeves, top loaders, graded slabs, busy
backgrounds. 1,314 of the 1,392 ground-truth prints map to a TCGdex id: 1,309 directly by the set code and number
in the pokemon.com URL, 5 XY alternate arts (which TCGdex files under their original set) by the scan's embedding
with the same number and similarity ≥ 0.6. 76 do not map, mostly promos without a TCGdex image, and 2 have no
number. That leaves 5,578 scored photos. The detector runs on the whole photo, because the seller framed the card.

| Detector | Top-1 | Top-5 | Name top-1 | Easy (683) | Medium (4,494) | Hard (401) | Shown at sim ≥ 0.5 (wrong) |
|---|---|---|---|---|---|---|---|
| classic | 54.1 | 60.4 | 55.9 | 84.2 | 52.7 | 18.0 | 3,005 (317) |
| **learned** | **72.4** | **78.9** | **74.4** | **88.7** | **74.0** | **26.7** | 4,005 (337) |

- The train/test split (fixed 20 % of the cards, `split_of`) scores the same today (learned: 72.4 vs 72.6), as it
  should before any training on FORB. From now on, only the **test** split counts once FORB photos go into training.
- Of the 1,538 learned-detector misses, only 110 had the right Pokémon name, so most misses are not near-duplicate
  prints but plain failures (crop or embedding).
- Misses concentrate on promos and special sets: `swshp` 261, `smp` 242, `xyp` 135, `sma` (Shiny Vault) 123,
  `ex1` 108. Many of these are far from the 870 trained cards in style.
- At the 0.5 threshold the learned path shows 72 % of the photos, and 8.4 % of what it shows is wrong.

### TCG-AR real set (`eval_tcgar.py`)

51 overhead 1920×1080 webcam photos of play mats, 832 annotated cards (4 corners each, 635 with an EN id mapped to
TCGdex). Cards are small (median 144 px wide), so this is a stress test, not an app estimate. Per card, the
app's guide box (card at 78 % of its height, 7 % margin) is cut around it with the neighbours inside, and sideways
cards (72, judged on the annotated quad) are turned upright.

| Detector | Found | Median corner error (% of the diagonal) | ≤ 3 % | ≤ 5 % |
|---|---|---|---|---|
| classic | 95.1 % | 13.4 | 23.4 % | 31.2 % |
| **learned** | **100 %** | **1.62** | **67.3 %** | **75.8 %** |

Recognition over the EN gallery: 71.7 % top-1 with the annotated corners, **71.2 %** with the learned detector
(top-5 82.4 / 83.0 %).

### Takeaways

1. **The learned corner detector is the right default.** It adds 18 pp top-1 on FORB, and its corners on TCG-AR
   come close to the hand annotations.
2. **The embedding is now the bottleneck on real photos**: hard FORB photos (27 %), promos and Shiny Vault. Next is
   fine-tuning on the FORB/TCG-AR train split plus more classes than the 870, scored on the held-out split.
3. FORB numbers (EN, mostly vintage/promo, eBay framing) and our own S25 photos (DE, binder, guide) measure different
   things. Keep both.

## Fine-tune on real photos: `mnv3_256_forb` (2026-11-17)

I fine-tuned `mnv3_256_real` on the FORB and TCG-AR **train split** with a larger class set. Both models are scored
on the held-out **test split**, searching the full EN pack gallery (22,127 cards). Both columns below were run on
Fabian-PC, with the same gallery and the same ground truth (the set+number FORB mapping and the TCG-AR rotation fix
from #53). The baseline column comes from `results/baseline_fabianpc/`. The baseline files directly in `results/`
are the Vulcan run of the section above (gallery 22,213); their test-split numbers are the same.

### Data and split

- **Card roles** (`realset.role_of`, fixed per card id):
  - **test**: the 20 % holdout of `split_of` (`common.py`). A test card gets no photo, no crop and no ArcFace
    class.
  - **val**: 10 % of the remaining cards, picked with a separate hash. Val cards get the same treatment as test
    cards. Their 535 real crops (124 cards) choose the epoch.
  - **train**: all other cards.
- **What this run did not exclude** (found in review, fixed in code for the next run, no retrain here):
  1. **Inherited backbone.** The run starts from `mnv3_256_real`, whose backbone saw all 870 manifest cards as
     classes, including the 183 with a test-split id. Only their ArcFace rows were dropped. No FORB or TCG-AR test
     card is a manifest card (`leak_check_mnv3_256_forb.json`: 0 of 1,132 and 0 of 139), so the real-photo test
     scores are not affected.
  2. **Language twins.** ja `SV2a-NNN` and en/de `sv03.5-NNN` share the art but have different ids, so they got
     separate roles. 113 classes of this run are the twin of a test card: mostly sv03.5 ↔ SV2a pairs, plus
     reprints. Cosine cannot find these pairs, because the baseline separates languages on purpose (cos ≈ 0
     between the JP and EN print of one card). `realset.py twins` therefore matches them by collector number (and
     by dex id among secret rares).
  3. **Reprints with the same art** under another id, found by baseline cos ≥ 0.9. Two TCG-AR test cards have one
     as a class: sv05-157 ↔ sv08.5-119 (Prime Catcher) and swsh4.5-9 ↔ swsh1-9 (Dhelmise V), 10 photos. The
     TCG-AR row below therefore also gives the score without them. No FORB test photo is affected.

  `train.py --real` now gives no class to any twin or reprint of a test or val card. That blocks 513 class
  candidates, 198 of them manifest cards.
- **Ground truth.** FORB maps 1,314 of 1,392 prints, giving 5,578 scored photos. The mapping was made once with the
  baseline (`eval_forb.py map`) and then kept fixed. The training ran on the old mapping: 16 crops of 2 prints were
  labelled wrong there (`SM35_EN_14` → sm2-14, `SM35_EN_8` → sv06-008); the new mapping leaves both unmapped. The
  TCG-AR training crops were made before the rotation fix, so the detector saw sideways cards sideways. The
  rectified crop is upright either way, but its corners are worse. A production retrain re-crops both.
- **Crops** (`realset.py build`) are made exactly as the evaluators make them:
  - FORB: learned corners on the whole photo, long side 2048 px.
  - TCG-AR: learned corners inside the app guide box around the annotated card.
  - Then rectify, and turn the crop to whichever 180° orientation matches the reference better.

  This gave 4,373 train crops (3,953 FORB + 420 TCG-AR). 85 crops with baseline similarity below 0.10 to the true
  reference were dropped: card backs, several cards in one photo, slab close-ups, background (checked by eye on a
  grid). That leaves **4,288 crops of 1,068 cards**. Val keeps all its crops, like test (`realset_mnv3_256_forb.json`).

  New for the next run, not used here: a train crop whose baseline top-1 is a test or val card is also dropped (the
  detector caught a neighbouring card). The crops are redone when the card id or the cropping code changes.
- **Leak check** (`leak_check.py`): 0 classes with a test or val id, 0 training crops of test or val cards, and 0
  test photos whose true card is a class.

### Training (`train.py --real`)

- **Init:** `mnv3_256_real`. The ArcFace head keeps the trained rows of the 619 manifest cards that remain
  classes. Each of the 1,069 new EN cards starts at the embedding of its reference image.
- **Synthetic part:** the existing GPU pipeline (`gpu_aug.py --realistic`) runs on the reference image of every
  class.
- **Real part:** 25 % of every batch (32 of 128) are real crops. They are sampled so that every card is drawn
  equally often, however many photos it has. Their augmentation is light (`gpu_aug.augment_real`: ~1.5 % corner
  jitter, ±3 % scale, ±12 % colour and contrast, some blur and JPEG). The photos already carry real light, glare,
  sleeves and detector error.
- **Model selection:** after every epoch, the real val crops are searched against the full EN gallery
  (`val_real.py` gives the same score for any checkpoint).
- **Run:** 10 epochs, lr 5e-4, cosine schedule, 34 min. Real val top-1 went from 72.9 % (baseline) to 85.0 % after
  epoch 1 and peaked at **88.2 % at epoch 6**. Epochs 7–10 scored 87.1–87.7 %. Synthetic val stayed between 93.3
  and 94.1 % in every epoch; the baseline's best was 92.8 % (`train_mnv3_256_forb.json`).
- **Export:** LiteRT in WSL. `mnv3_256_forb_w8.tflite` is 5.0 MB and `_f32.tflite` 18.2 MB. The w8 model stays as
  close to PyTorch as the baseline's did (mean cosine 0.974 vs. 0.968).

### Results (w8 `.tflite`, learned detector, full EN gallery, no OCR rerank)

| Set | `mnv3_256_real` | **`mnv3_256_forb`** |
|---|---|---|
| **FORB test split** top-1 / top-5 (n = 1,132) | 72.6 / 80.7 | **87.7 / 91.8** |
| FORB test: easy (137) / medium (915) / hard (80) | 89.1 / 74.3 / 25.0 | 97.1 / 90.4 / 41.2 |
| FORB test, shown at sim ≥ 0.5 (wrong) | 823 (76) | 932 (19) |
| **TCG-AR test split** top-1 / top-5, learned corners (n = 139) | 64.0 / 79.1 | **86.3 / 94.2** |
| TCG-AR test without the 2 reprinted cards (n = 129) | 68.2 | 87.6 |
| TCG-AR test, annotated corners | 66.2 | 92.1 |
| Real val crops (535, never trained) | 72.9 | 88.2 |
| *FORB non-test split: train + val cards, mostly trained on (4,446)* | *71.9* | *95.5* |
| *TCG-AR non-test split: train + val cards (496)* | *73.2* | *92.9* |
| Own photos de/30th top-1, shown at ≥ 0.5 | 21/21, 19 | 21/21, 18 |
| Own photos de/me04 (twin), exact top-1 | 7/8 | 8/8 |
| Synthetic `evaluate.py`, main (870) | 94.8 | 95.0 |
| Synthetic, trained sets in the full gallery | 93.5 | 93.6 |
| Synthetic, unseen de/30th | 94.3 | 95.4 |
| Synthetic, language twin de/me04 | 88.9 | 91.5 |
| Synthetic with learned detector: main / twin | 100 / 98.2 | 99.98 / 98.5 |

Sources: `results/baseline_fabianpc/{forb,tcgar}_mnv3_256_real*.json` and `results/{forb,tcgar}_mnv3_256_forb*.json`.
The test × difficulty table and the misses by set are in the summary JSON. `leak_check_mnv3_256_forb.json` holds
the twin and reprint numbers.

- **FORB test misses** fell from 310 to 139. The promo and special sets improved most: `sma` (Shiny Vault) 22 → 3,
  `smp` 37 → 12, `swshp` 47 → 23, `sm1` 29 → 13, `xyp` 16 → 6. `xy0` did not change (14 of 27), and `ex1` is still
  weak (23 → 14 of 78). 20 of the 139 remaining misses have the right name, so most are still crop or embedding
  failures, not near-identical prints.
- **The display threshold is safer.** At 0.5 the new model shows 82 % of the test photos and 2.0 % of what it shows
  is wrong. The baseline showed 73 %, with 9.2 % wrong.
- **TCG-AR:** the reprints inflate the gain a little. Without the 10 reprint photos the gain is +19.4 pp; with them
  it is +22.3 pp (the new model gets 7 of the 10 right, the baseline 1). With the rotation fix from #53, learned
  corners (86.3 %) now come close to annotated corners (92.1 %). The corner median error is 1.62 % of the diagonal.
- **Manifest cards that are no longer a class** (synthetic test, classic detector) score about the same as the
  classes: 95.2 % for the 3,095 class images, 94.6 % for the 1,255 non-class images. The non-class group splits
  into 94.1 % for the 690 whose twin is still a class and 95.2 % for the 565 without one. So the twins do not
  inflate this number. It still does **not** show that unseen cards come free: the init model had all 870 cards as
  classes. It only shows that the fine-tune did not forget them.
- **Our own phone photos:** no regression. The one twin miss is now correct. One de/30th photo is still right, but
  its similarity fell from 0.503 to 0.407, so it is no longer shown at 0.5. The median similarity of correct matches
  rose from 0.67 to 0.72.

### Decision

**`mnv3_256_forb` is clearly better than `mnv3_256_real` on real photos of cards it never trained on.** On the test
split, top-1 rises by 15.1 pp on FORB and by 22.3 pp on TCG-AR (+19.4 pp without the reprints). It does not
regress on the synthetic sets or on our own photos. It is the recommended embedding. This PR changes nothing in the
app and uploads nothing.

Before shipping:

1. **Production model:** the same recipe without a holdout. Classes are every card with a real photo plus all 870
   manifest cards. Re-crop first (`realset.py build`: new ground truth, TCG-AR rotation fix). Train for a fixed
   ~6 epochs. The test split was only needed to *measure* the recipe.
2. Re-embed the language packs with that model. Pack rows and model must ship together (`build_packs.py embed`).
3. The 0.5 threshold can stay. The new model is better calibrated (see above).

Still open: hard FORB photos (41 %), `xy0` and `ex1`. The optional corner fine-tune with TCG-AR corners was not done.

## Corner detector on real photos: error analysis (#59, 2026-11-18)

The question: where does the shipped learned corner detector (`corners_mnv3_w8`) still fail on real photos, and is
a fine-tune on TCG-AR/FORB worth it? Run on Vulcan (CPU) with `corner_analysis.py`. The embedding is the shipped
`mnv3_256_real_w8` against the full EN pack gallery (22,213), because `mnv3_256_forb` is not on Vulcan. The
TCG-AR rows for `mnv3_256_forb` come from the PC run above. No OCR rerank.

Every photo is recognised with several crops:
- **learned**: the detector on the evaluator's input. For FORB that is the whole photo at 2048 px; for TCG-AR it
  is the guide box around the card.
- **refine**: a second detector pass on the guide box cut around the first quad. The card fills ~78 % of the box
  height, the framing the detector was trained on and the one the app's guide gives.
- **full**: the whole FORB photo, without detection.
- **gt**: the annotated TCG-AR corners.

A miss that another crop gets right counts as a corner failure. Source: `results/corner_analysis_mnv3_256_real.json`.

### TCG-AR (annotated corners)

| Cards | n | Median corner error (% of the diagonal) | ≤ 3 % | > 10 % |
|---|---|---|---|---|
| all 832 (as in the issue) | 832 | 1.62 | 67.3 % | 6.5 % |
| test split | 139 | 1.58 | 74.8 % | 0.7 % |
| sideways (photo turned 90° by `upright`) | 72 | 6.44 | 38.9 % | 15.3 % |
| val split (`corner_realset.py`: 10 % of the non-test cards; selection set of the fine-tune) | 90 | 1.24 (mean 2.71) | 74.4 % | 5.6 % |

| Recognition, top-1 | annotated | learned | refine |
|---|---|---|---|
| test split, `mnv3_256_real` (n = 139) | 66.2 | 63.3 | 59.0 |
| all mapped, `mnv3_256_real` (n = 635) | 71.7 | 71.2 | 70.2 |
| test split, `mnv3_256_forb` (PC, `tcgar_mnv3_256_forb_w8.json`) | 92.1 | 86.3 | – |

- **The worst 40 cards** (contact sheet, by eye) are not app scenes: fans of cards held in the hand, deck piles
  and card backs, where the detector frames the sleeve instead of the card, and energy cards tucked under a
  Pokémon. Most of these have no TCGdex id, so they count for corner error but not for recognition.
- **With the shipped embedding, the corners cost almost nothing on TCG-AR.** Annotated and learned corners differ
  by 0.5 pp overall. The test split differs by 9 vs. 5 cards.
- **Most disagreements are near-ties, not corner failures.** In most cards where annotated and learned corners
  give a different result, the learned quad is ≤ 3 % off and the true card ranks 1 instead of 0.
- **With the stronger `mnv3_256_forb`, the test gap is 5.8 pp (8 cards of 139).** That is the most a corner fix
  can gain on TCG-AR.

### FORB (no corner labels)

| FORB, top-1 % | n | learned | refine | higher-similarity of the two | any crop (oracle) | classic |
|---|---|---|---|---|---|---|
| all | 5,578 | 72.4 | 75.3 | 77.3 | 83.2 | 54.1 |
| **test split** | 1,132 | **72.6** | **76.0** | **77.8** | 83.2 | 54.7 |
| test, hard | 80 | 23.8 | 30.0 | 31.2 | 37.5 | 15.0 |

- **The oracle overstates the corner share.** Of the 702 test photos where the two passes give nearly the same
  quad (shift < 1 %), 6.1 % still flip between right and wrong: that is embedding noise. A "best of 4 crops"
  oracle profits from that noise, so read 83.2 as a loose upper bound, not as the corner share of the 310 test
  misses.
- **Geometric suspects** are photos where the learned quad is not card-shaped or the second pass moves it by
  ≥ 5 %. They are 13.3 % of the test photos (150), and only 30.0 % of them are recognised (refine: 46.7 %).
  - Landscape photos (all 5,578 photos): 509, 40.5 % top-1 vs. 76.3 % for portrait photos. The whole photo is stretched into the
    portrait 192×256 input.
  - Quads that are not card-shaped (all photos): 378, 15.9 % top-1.
- **Contact sheets** (in `<data>/report/corner-analysis/`). 36 photos the learned crop missed but another crop
  got right, by eye:
  - 14 real geometric failures:
    - 6 graded slabs, where the quad takes in the PSA/CGC label
    - 3 small cards in a wide frame, with a twisted or collapsed quad
    - 3 photos with several cards or packaging
    - 2 cards cut by the photo edge
  - ~16 near-ties: the quads look the same, and the true card ranks 1–3.
  - The rest are unclear.
- **Photos no crop gets right** are mostly embedding failures with good quads: glare, promos, Shiny Vault. A few
  are slab close-ups or deck boxes.

### Decision

**A fine-tune is worth one PC run, but it is not where the big gain is.**

- **The failure mode is clear and fixable:** slabs and top loaders, a small card in a wide frame, and neighbours.
  These are 13 % of the FORB test photos, recognised at 30 %.
- **What a fine-tune can gain is small.**
  - TCG-AR (guide framing, like the app): at most 5.8 pp with `mnv3_256_forb`.
  - FORB: the refine pass already lifts the suspects from 30.0 to 46.7 % top-1 without any training.
  - The FORB landscape failures come mostly from the evaluator feeding the whole photo, which the app never does.
- **The pseudo-labels carry little new signal.** 2,907 FORB train photos pass the filter, but only 190 of them
  are photos where the learned crop failed. The other labels mostly confirm what the detector already does.
- **The tooling is done, and the run costs about an hour of PC time.**

Accept the fine-tuned detector only if all of these hold:
- the TCG-AR test corner error goes down
- FORB test top-1 goes up for both learned and refine
- the synthetic test does not regress (`train_<run>.json`)

**Cheaper and larger:** two-pass detection gives +3.4 to +5.2 pp FORB test top-1 with today's models:
1. detect
2. cut the guide box around the quad and detect again
3. keep the crop with the higher top-1 similarity

It costs one more detector call (9 ms) and two embeddings. Follow-up issue: try it in the app's photo/lab path;
live scan already frames with the guide.

### Real-photo training set and fine-tune

`corner_realset.py build` → `<data>/corner-train/real_corners.npz` (1.17 GB; counts in `results/corner_realset.json`):
- **TCG-AR:** 567 train-role cards with annotated corners. The 90 val-role cards are the selection set, in the
  evaluator's framing. The 175 test-role cards are left out.
- **FORB:** 2,907 pseudo-labels from train-role photos where recognition with the learned (1,433) or refine
  (1,474) quad is top-1 right at similarity ≥ 0.5 and the quad is card-shaped.
  - 1,078 train photos are rejected.
  - Val (461) and test (1,132) photos are never used.
- **Roles:** `realset.role_of` on the card id, the same holdout as the embedder.
- **Caveat:** a TCG-AR train crop can show a test card as an unlabelled neighbour. Almost every TCG-AR photo holds
  a test card, so a photo-level split is not possible.

`train_corners.py --real --init corners_mnv3`:
- **Batches:** half of every batch are real samples (40 % of them TCG-AR). A random guide box is cut from each
  sample's context window on the GPU: card at ~50–97 % of the height, the box clipped to the photo and stretched
  to 192×256 like the app.
- **Selection:** on the real val cards. The synthetic val and test sets are still scored.
- **Real test split:** scored after the WSL export by `corner_analysis.py --corners <run>`, then
  `corner_analysis.py compare`.
- **On Vulcan** (CPU, no GPU): the sampler and one training step were smoke-tested; the run itself was not done.

PC command (RTX 3080; the EN pack in `<data>\packs-work\en` must be embedded with `-Embedder`):

```powershell
cd D:\AI\pokemon-cards\ml
powershell -ExecutionPolicy Bypass -File run_corner_finetune.ps1 -Embedder mnv3_256_real
```

Expected time: ~50 min.
- rows: ~25 min (skipped when `corner_rows_mnv3_256_real.json` for FORB and TCG-AR is copied over from Vulcan)
- build: 3 min
- training, 10 epochs: ~15 min
- export: 2 min
- test-split evaluation: ~6 min

### PC run: `corners_mnv3_real` is not accepted (2026-11-18)

Run on Fabian-PC (RTX 3080) with `run_corner_finetune.ps1 -Embedder mnv3_256_forb`, from 09:09 to 09:38.
- **Embedder:** the PC's EN pack (`packs-work\en`) is embedded with `mnv3_256_forb`, so the old and the new
  detector are both scored with it, on the same machine and gallery.
- **Training set:** `real_corners.npz` was built from the Vulcan `mnv3_256_real` rows. It is the same set as above:
  3,474 train, 90 val, `results/corner_realset.json` unchanged. The pseudo-labels only depend on which crop was
  recognised right.
- **Training:** 10 epochs, lr 2e-4, 19.7 min. The real val cards pick epoch 3.

Sources:
- `results/train_corners_mnv3_real.json`
- `results/export_corners_mnv3_real.json`
- `results/corner_analysis_mnv3_256_forb.json` (baseline)
- `results/corner_analysis_mnv3_256_forb_corners_mnv3_real.json`
- `results/corner_compare_mnv3_256_forb_corners_mnv3_real.json`

| Test split (never seen in training or selection) | n | `corners_mnv3` (shipped) | `corners_mnv3_real` | Criterion |
|---|---|---|---|---|
| TCG-AR: corner error, median % of the diagonal | 139 | 1.59 | **1.26** | met |
| TCG-AR: within 3 % / over 10 % | 139 | 74.8 / 0.7 % | 75.5 / 0.7 % | |
| TCG-AR: top-1 learned / refine | 139 | 87.1 / 84.9 % | 84.9 / 86.3 % | |
| FORB: top-1 learned | 1,132 | **87.7 %** | 87.4 % | **not met** |
| FORB: top-1 refine (second pass) | 1,132 | **90.5 %** | 89.4 % | **not met** |
| FORB: top-1 higher-similarity of the two | 1,132 | 90.6 % | 89.8 % | |
| FORB hard: top-1 learned | 80 | 41.2 % | 43.8 % | |
| Synthetic test (PyTorch, 2,000 scenes): corner error mean / median, % of card height | 2,000 | **2.25 / 0.49** | 2.32 / 0.57 | **not met** |
| Synthetic test: PCK@2 % / PCK@5 % | 2,000 | 0.758 / 0.852 | 0.753 / 0.850 | |

The real val cards (selection set, PyTorch for both) improve a lot:
- mean corner error 2.62 → 1.73 % of the diagonal
- within 3 %: 75.6 → 91.1 %

Paired, on the same test photos (`corner_analysis.py compare`):
- **TCG-AR:** the corners clearly improve. 12 cards get better by more than 1 % of the diagonal and 1 gets
  worse; the mean goes from 2.40 to 2.17 %. Recognition does not follow:
  - learned: 5 cards lost, 2 gained
  - refine: 4 lost, 6 gained
  - higher-similarity rule: 5 lost, 5 gained

  With 139 cards these are near-ties.
- **FORB:**
  - learned: 28 photos lost, 24 gained (−0.3 pp)
  - refine: 24 lost, 12 gained (−1.1 pp)

  On whole photos the new model is less confident. The share of learned scores under the live-scan gate
  (`CornerDetector.DEFAULT_MIN_SCORE` = 0.10) goes from 5.9 % to 11.6 %. In the guide framing (TCG-AR) it goes the
  other way, from 4.3 % to 1.4 %.

**Decision: keep `corners_mnv3`.**
- Two of the three criteria fail: FORB top-1 for both passes, and the synthetic test.
- The fine-tune learned what TCG-AR teaches, the printed edge in the guide framing.
- It did not turn that into better recognition, and it lost a little on FORB whole photos and on the synthetic
  scenes.
- This fits the analysis above: with today's embedding, corners cost little on TCG-AR. The FORB pseudo-labels mostly
  repeat what the detector already does.
- The exported `corners_mnv3_real_{f32,w8}.tflite` has the app's I/O contract: float32 `[1, 256, 192, 3]` in,
  `[1, 4, 3]` out, the same as `corners_mnv3`. It stays in `<data>/export/` and is not shipped.
  - w8 sha256: `d89c413a6e16bedbf9f48f02feedc46f425344db327ca47024385b657531fefb`
- **Note:** `export_corners_mnv3_real.json` checks 400 synthetic scenes, while `export_corners_mnv3.json` checks
  2,000. Its w8 numbers are therefore not comparable. The synthetic criterion uses the PyTorch `test_selected`
  (2,000 scenes, same `corners_test.pt`) against epoch 30 of `train_corners_mnv3.json`.

Two-pass detection (#78) is still the larger and cheaper gain: +2.8 pp FORB test top-1 here (87.7 → 90.5), with
the shipped detector.

## Production model `mnv3_256_prod` and packs v2 (2026-11-18, issue #58)

This is the shipped version of the `mnv3_256_forb` recipe. It has no holdout, so there is no fair real-photo score
for it. The real-photo gain is the one measured in the section above. Here I only check that nothing got worse.

### Data and training

- **Crops** (`realset.py build --all-cards`, CROP_VERSION 2): every FORB and TCG-AR photo, with the fixed FORB
  mapping and the TCG-AR rotation fix. That is 6,213 photos (5,578 FORB + 635 TCG-AR). 128 detector failures
  (baseline similarity < 0.10) are dropped, which leaves **6,085 crops of 1,517 cards**. The #57 run had 4,288
  crops of 1,068 cards (`realset_mnv3_256_prod.json`). The crops are in `<data>/real-train/all/`.
- **Classes:** 2,389. These are all 870 manifest cards (so no twin is blocked) and the 1,519 other EN cards with a
  real photo. The 870 keep their trained ArcFace rows from `mnv3_256_real`. Each new class starts at the embedding of
  its reference image.
- **Run:** `train.py --run mnv3_256_prod --realistic --init mnv3_256_real --real --all-cards --epochs 6 --lr 5e-4`,
  32 min on the RTX 3080. There is no real val set, so the run keeps the last epoch. Epoch 6 was the best real-val
  epoch of the 10-epoch #57 run, and here the cosine schedule ends at epoch 6. Synthetic val was 93.4–93.8 % in
  every epoch (`train_mnv3_256_prod.json`).

### Export and pack model

The packs ship the **f16** file (fp16 weights, float math), not w8, for #54. On the phone CPU, w8
(`dynamic_wi8_afp32`) quantizes the activations to int8 at runtime and lands at cosine 0.88 to the float model. A
GPU computes in float, so CPU and GPU disagreed. With f16 they agree, and the app can use the GPU delegate.

| Variant | Size | Cosine to PyTorch, mean / min (300 test crops) |
|---|---|---|
| `mnv3_256_prod_f16` (**packs v2**) | 9.3 MB | 1.0000 / 0.9999 |
| `mnv3_256_prod_wo8` (weight-only int8) | 5.0 MB | 0.9976 / 0.9747 |
| `mnv3_256_prod_w8` (dynamic int8) | 5.0 MB | 0.972 / 0.804 |
| `mnv3_256_prod_f32` | 18.2 MB | 1.0 |

### Sanity check against the live model

| | `mnv3_256_real` (packs v1) | **`mnv3_256_prod`** |
|---|---|---|
| Synthetic `evaluate.py`, main (870) | 94.78 | **95.08** |
| Synthetic, trained sets in the full gallery | 93.47 | **93.66** |
| Synthetic, unseen de/30th | 94.30 | **95.44** |
| Synthetic, language twin de/me04 | 88.85 | **91.64** |
| Own photos de/30th top-1 (w8; prod also f16 / f32) | 21/21 | 21/21; 20/21 / 20/21 |
| Own photos de/me04 twin, exact (w8; prod also f16 / f32) | 7/8 | 7/8; **8/8** / **8/8** |
| Own photos shown at sim ≥ 0.5 | 19 (0 wrong) | 19 (0 wrong) in every variant |
| Median similarity of correct own-photo matches | 0.669 | 0.705 (w8), **0.751** (f16) |

Both models get 28 of 29 own photos exactly right. The f16 and f32 miss is a de/30th photo at similarity 0.30. The
baseline found it at 0.38, which is also below the display threshold, so the app shows nothing for that photo with
either model. Since f16 and f32 agree on it, the miss comes from the model and not from the f16 conversion.
FORB and TCG-AR are training data now and only serve as a smoke test. TCG-AR (`eval_tcgar.py --variant f16`, v2 EN
gallery, all 635 cards, every one trained on) gives 91.8 % top-1 with learned corners: the baseline had 71.2 % and
`mnv3_256_forb` 91.5 % (`results/tcgar_mnv3_256_prod_f16.json`). Nothing broke, and that is all this number shows.
FORB was not re-run.

Sources: `results/eval_mnv3_256_prod.json`, `results/real_manual_mnv3_256_prod{,_f16,_f32}_learned.json`,
`results/export_mnv3_256_prod.json`.

### Packs v2 (published 2026-11-18)

All seven live languages were re-embedded with `mnv3_256_prod_f16.tflite` (`build_packs.py embed`, CPU on
Fabian-PC, about 25 min in four processes). They were built against the published v1 catalog on Vulcan and share one
model, **`mnv3_256_prod_f16-0635d6230b38`** (embedder 9.3 MB plus the unchanged corner model). The card metadata is
the same as v1, so the indexed counts are the same: en 22,213, de 15,598, ja 7,359, fr 18,767, it 14,622, es 14,916,
pt 13,144. The model changed, so there is no delta: an installed v1 pack downloads v2 in full (124 MB for all seven
plus the models). MEE (#60) is not in v2, because it has no image source that allows automated downloads yet.

**App check before the catalog switch** (talos emulator, debug build of `main` @ 89dfa02 with #72, CPU backend).
The test build loaded the staged v2 files from a local mirror, the same bytes that were uploaded. Its first-run
picker downloaded all seven languages: `engine ready: mnv3_256_prod_f16-0635d6230b38 on CPU, [de, en, es, fr, it, ja,
pt]`, 106,619 cards, no out-of-memory error. Then `RealPhotoTest` ran on the same 29 labelled own photos, once with
v1 and once with v2, both with all seven packs installed:

| `RealPhotoTest`, 29 photos | v1 `mnv3_256_real_w8` | **v2 `mnv3_256_prod_f16`** |
|---|---|---|
| Learned outline, all languages: right / shown / shown wrong | 20 / 28 / 8 | **23 / 27 / 5** |
| Learned outline, German only | 27 / 28 / 1 | **28 / 27 / 0** |
| Classic outline, all languages | 11 / 27 / 16 | **21 / 23 / 4** |
| Classic outline, German only | 22 / 23 / 3 | **27 / 23 / 0** |

The photos are German prints. Installing all seven languages costs 5 of 28 (v2) top-1 answers against German only;
which other-language cards they land on was not broken down. The S25 check was not done (#54 covers the GPU path
there).

### Packs v2/v3 on the S25 (#83, 2026-11-18)

Galaxy S25 Ultra (SM-S938B, Snapdragon SM8750, Android 17), debug build of `main` @ f0079e3, all seven packs installed
(en, de, fr, it, es, pt at v3 = v2 plus MEE, ja at v2), model `mnv3_256_prod_f16-0635d6230b38`, 106,715 cards. The
phone was reached over Tailscale with its screen locked, so the packs were installed by an instrumentation helper
instead of the first-run picker (same `PackManager.addLanguages` + `sync` path).

**GPU backend.** Loading the engine as the Scan screen does (`preferGpu = true`) keeps the GPU: `GPU embedder agrees
with the CPU (cosine 1.0); 1 ms vs 14 ms`, `backend=GPU`. `RealPhotoTest` itself loads with the CPU default, so its
times below are CPU times.

**GPU precision** (`EmbedderGpuProbe`, synthetic probe + 3 card images, 50 runs):

| Model | CPU | GPU FP32 | GPU FP16 | FP16 vs CPU (cosine, min) |
|---|---|---|---|---|
| `mnv3_256_prod_f32` | 12.5 ms | 2.4 ms | 1.6 ms | 0.9999 |
| `mnv3_256_prod_f16` (shipped) | 12.5 ms | 2.4 ms | 1.6 ms | 0.9999 |

FP16 is well above the 0.995 agreement gate and saves 0.8 ms per embedding (a scan embeds two orientations). The app
keeps `GPU_PRECISION = FP32` for now: the gain is about 1.6 ms per frame next to ~25 ms detection.

**`RealPhotoTest`, 29 labelled own photos** (right / shown / shown wrong; median detection + identification, CPU):

| Outline | One pass | Two-pass (`-e twoPass true`) | Emulator v2 (above) |
|---|---|---|---|
| Learned, all languages | 24 / 28 / 4, 257 ms | 24 / 28 / 4, 509 ms (second crop kept 20×) | 23 / 27 / 5 |
| Learned, German only | 29 / 28 / 0, 122 ms | 29 / 28 / 0, 224 ms (kept 19×) | 28 / 27 / 0 |
| Classic, all languages | 21 / 23 / 4, 272 ms | (one pass) | 21 / 23 / 4 |
| Classic, German only | 27 / 23 / 0, 133 ms | (one pass) | 27 / 23 / 0 |

- The phone matches or beats the emulator run of the same packs (learned: +1 right, −1 shown wrong).
- **Two-pass changes no answer on these photos** and doubles the time on the photo path (+250 ms all languages,
  +100 ms German only). That is expected: these 29 photos were taken with the app's guide, where the first crop is
  already good. The FORB gain (+2.9 pp, #78) came from wide or landscape frames, i.e. the gallery path.

## Two-pass corner detection (#78, 2026-11-18)

Run on Fabian-PC (CPU) with the shipped detector `corners_mnv3_w8`, no OCR rerank. The steps
(`detect.two_pass`, flag `--two-pass` in `eval_forb.py`, `eval_tcgar.py`, `eval_real_manual.py`, `eval_real.py` /
`pipeline.Pipeline(two_pass=True)`):
1. detect
2. cut the guide box around the quad (`detect.guide_crop`: card at 78 % of the box height, 63:88, 7 % margin)
3. detect again on that box
4. embed both crops (both 180° orientations each) and keep the crop with the higher top-1 similarity

The second pass never decides alone. A tie keeps the first pass, and a box under 24 px skips the second pass. One
run scores both: the one-pass result of the same photos is kept next to the two-pass one. The one-pass numbers
reproduce #59 exactly: all 1,132 FORB test photos have the same top-1 outcome as the `learned` and
higher-similarity columns of `corner_rows_mnv3_256_forb.json`.

**Fair numbers** (cards the embedder never trained on):

| Top-1 (exact print) | n | one pass | two-pass | delta | gained / lost |
|---|---|---|---|---|---|
| **FORB test**, `mnv3_256_forb` w8, EN gallery `packs-work` | 1,132 | 87.7 % | **90.6 %** | **+2.9 pp** | 36 / 3 |
| FORB test, hard | 80 | 41.2 % | 46.2 % | +5.0 pp | 5 / 1 |
| FORB test, medium | 915 | 90.4 % | 93.4 % | +3.1 pp | 30 / 2 |
| FORB test, easy | 137 | 97.1 % | 97.8 % | +0.7 pp | 1 / 0 |
| **TCG-AR test**, guide framing, `mnv3_256_forb` w8 | 139 | 86.3 % | 86.3 % | ±0 | 2 / 2 |
| Own photos (29, `eval_real_manual.py`, the app's guide path), `mnv3_256_prod_f16` | 29 | 28 / 29 | 29 / 29 | +1 photo | 1 / 0 |

- **FORB:** the second crop is kept for 567 of 1,132 photos. Top-5 goes from 91.8 to 92.9 %. Shown at similarity
  ≥ 0.5: 932 photos (19 wrong) with one pass, 986 (17 wrong) with two-pass. So more answers are shown and fewer of
  them are wrong.
- **TCG-AR:** no gain. The second crop is kept for 71 of 139 test cards; top-5 goes from 94.2 to 96.4 %. On all
  635 mapped cards top-1 goes from 91.5 to 91.3 % (4 gained, 5 lost). The corner error of the kept quad does not
  improve either: median 1.21 → 1.24 % of the diagonal, ≤ 3 %: 82.5 → 81.6 % (same 635 cards). The input is
  already the guide box, so the second pass sees nearly the same framing. This fits #59: with this embedding the
  corners cost little in guide framing.
- **Own photos:** the one photo that flips (de/30th) goes from similarity 0.300 (wrong) to 0.307 (right). Both are
  under the 0.5 display threshold, so what the app shows does not change: 19 of 21 de/30th shown, 0 wrong, in
  both runs. The median similarity of the right answers goes from 0.751 to 0.760.

**Smoke test only** (`mnv3_256_prod_f16`, gallery `packs-work-v2`; prod trained on every FORB and TCG-AR photo,
so these are not fair scores):

| Top-1 | n | one pass | two-pass | delta | gained / lost |
|---|---|---|---|---|---|
| FORB test | 1,132 | 96.6 % | 97.0 % | +0.4 pp | 8 / 4 |
| TCG-AR test | 139 | 89.2 % | 89.9 % | +0.7 pp | 1 / 0 |

**Cost:** one more detector call (~9 ms on x86, #78) and two more embeddings per photo.

**Recommendation: give the app's photo/lab path two-pass detection, and leave live scan as it is.**
- It helps where the framing is loose: whole photos, a small card in a wide frame, a twisted first quad. FORB
  (+2.9 pp, 36 gained against 3 lost) is that case. A gallery photo in the lab path can be framed anyhow.
- In the guide framing it is neutral (TCG-AR ±0, own photos +1 under the threshold). It never made things clearly
  worse in any set, so it is a safe default for single photos.
- Live scan already frames with the guide, so the gain there is likely about zero. The cost per frame (one
  detector call plus two embeddings) is not worth it.
- **Open step:** measure it in the app. Run `RealPhotoTest` on the S25 with and without two-pass on the photo/lab
  path, for accuracy and latency. Nothing was measured on a phone here.

Sources:
- `results/forb_mnv3_256_forb_w8_learned_twopass_test.json`
- `results/tcgar_mnv3_256_forb_w8_twopass.json`
- `results/real_manual_mnv3_256_prod_f16_learned{,_twopass}.json`
- smoke tests: `results/forb_mnv3_256_prod_f16_learned_twopass_test.json`, `results/tcgar_mnv3_256_prod_f16_twopass.json`

`eval_real_manual.py` now leaves cards without a local reference image out of its gallery. These are the 32 en/de
`mee` cards (#60); none of the own photos shows one. With them in the manifest the script stopped with
`FileNotFoundError`. The one-pass result is unchanged (`real_manual_mnv3_256_prod_f16_learned.json`).

## Reproduce

See [README.md](README.md). Data and checkpoints are in `D:\AI\pokemon-cards-data\`, not in the repo.
