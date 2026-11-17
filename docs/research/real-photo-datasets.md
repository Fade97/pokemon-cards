# Research: datasets of real photos of physical trading cards

Context: the recognition pipeline (`ml/REPORT.md`) is trained and scored almost entirely on synthetic renders.
There are only 34 own phone photos. Research date: 2026-11-17. No dataset over a few MB was downloaded and no
marketplace was scraped. **Annahme** = not verified on the primary page. Not legal advice.

## Conclusion

- **There is no large, clean, permissively licensed dataset of real Pokémon card photos.** The open alternatives
  are scans, synthetic scenes, or small detector sets with unclear image provenance.
- **Best for evaluation: FORB "animated cards"** (Snap, 2023). It has **6,025 real photos of Pokémon cards** (97 % eBay
  listing photos) with **exact-print labels** (pokemon.com set code + EN number), plus 12.5k official reference
  images. The dataset is for research only and the images belong to third parties, so it is fine for internal eval but
  not for redistribution. Training a published model on it is a grey area.
- **Best for the corner detector: the TCG-AR real set** (ULiège, 2026). It has 51 top-down 1080p photos with 832 cards,
  each annotated with **4 corners + card ID**. The code is GPL-3.0; the data license is not stated, so ask the authors.
- **CardCorpus ships no photos.** Its real training and eval photos are not published. Its Apache-2.0 **QuadDet
  detector**, trained on hand-checked real photos, could serve as a **teacher or auto-labeller** for our own corners.
- **Marketplace and user photos (eBay, TCGplayer, Cardmarket, Reddit, PSA) are effectively off limits for training**,
  because their terms prohibit scraping and/or ML use. The durable fix is our own opt-in photo collection.

## Candidates

V = verified on the page/repo, S = only search snippet (page blocked or JS-only), A = Annahme.

| Source | Content | Labels | License / terms | Usefulness for us |
|---|---|---|---|---|
| [FORB](https://github.com/pxiangwu/FORB) "animated_cards" ([paper](https://arxiv.org/abs/2309.16249)) | **6,025 real query photos**: 5,879 from i.ebayimg.com, the rest OfferUp, YouTube, TCGplayer. **12,529 database images**: official scans from assets.pokemon.com. EN only. Has slabs/sleeves (A). V | Query → GT database image, which is `SET_EN_<no>` (e.g. `SM7_EN_52`), so **set+number**, plus a `difficulty` field. No boxes. V | Code MIT. Dataset: "research purposes only… third-party images assembled from the internet… as-is". Images are © eBay sellers and TPC. Images on Google Drive; size unknown. V | **Eval: high.** Map to TCGdex IDs (`sm7-52`). Detector: none, no corners. Training: legal grey area, never redistribute. |
| [TCG-AR real set](https://github.com/ULiege-VIULab/tcg-ar) ([arXiv 2607.02090](https://arxiv.org/abs/2607.02090)) | **51 images, 1920×1080**, overhead webcam over a play mat. **832 card instances**: sleeved, holo, occluded, face-down. Pokémon, language not stated. V | Per card **4 corner points (DOTA format) + pokemontcg.io ID** (`sv1-166`, `sv3pt5-146`) + orientation. Annotations are in the repo; images are a Drive zip. V | Code **GPL-3.0**. No separate data license; the authors say they are "open-sourcing all code, models, and datasets" and ask for a citation. V | **Detector eval: high**, multi-card with real sleeves and holo. Cards are small (~150 px) and top-down, a different domain from close-up phone shots. Embedding eval: medium (631 unique cards). |
| [CardCorpus scanner](https://huggingface.co/cardcorpus/pokemon-card-scanner) / [dataset](https://github.com/cardcorpus/pokemon-card-dataset) | Detector, DINOv3 matcher and number reader for EN/JA/ZH. Trained on "real photos whose cards' corners were marked or reviewed by hand". Eval on 641 + 139 + 672 real cards. **Neither the photos nor the eval set are published.** The dataset repo holds metadata only, no pictures. V | Index: CardCorpus address `<set>/<card>`. V | Detector, picker, number reader: **Apache-2.0**. Matcher: DINOv3 License. Index and dataset: CC BY 4.0. Card art © TPC. V | **No data.** The **detector is usable as a teacher** to pre-label corners on our photos (Apache-2.0, GPL-compatible). Their eval table is a reference point. |
| [rrhagentbiz/pokemon-card-centering-measurements](https://huggingface.co/datasets/rrhagentbiz/pokemon-card-centering-measurements) | 320 rows, 302 cards, from eBay photos. **CSV only: no images, no card ID**, just border percentages per eBay item ID. V | Centering percentages. V | CC BY 4.0, measurements only. V | None. |
| [stevelohwc/…authenticity_classification](https://huggingface.co/datasets/stevelohwc/pokemon_card_image_for_authenticity_classification) | 290 iPhone 16 Pro photos (4032×3024): 145 fronts, 145 backs, authentic/counterfeit. EXIF includes GPS. V | Side + authenticity only, **no card ID**. V | MIT. V | Small detector extra; corners must be labelled by us. |
| [Alexleplusmeilleur/pokemon-card-dataset](https://huggingface.co/datasets/Alexleplusmeilleur/pokemon-card-dataset) | ~40 Pixel 8 photos (2268×4032) of 15 EN cards, 2026-09. EXIF includes GPS. V | Folder name = name_number_total_set. V | **No license**, so all rights reserved. V | Would be a nice eval sample, but only with the author's permission. |
| [netprtony/pokemon-cards-image-and-annotations](https://huggingface.co/datasets/netprtony/pokemon-cards-image-and-annotations) | 4,033 × 640×640 images. A sample is an **official scan**. V | Name + number JSON. V | No license. V | None (scans). |
| [jyesr/pokemon-tcg-grading](https://huggingface.co/datasets/jyesr/pokemon-tcg-grading) | 20k+ front/back "scans", 17 GB, source not stated. V | Name, "expected" PSA grade (A). | No license. V | Low: scans, unknown provenance. |
| [antokun/pokemon-card-segmentation](https://huggingface.co/datasets/antokun/pokemon-card-segmentation) | One 91 MB tar, no README. V | Masks? (A) | CC0. V | Unknown. Worth one look (91 MB). |
| Roboflow Universe: [workspace-nh28l/pokemon-cards](https://universe.roboflow.com/workspace-nh28l/pokemon-cards-hrgfe) (2,582 img), [invention-time](https://universe.roboflow.com/invention-time/pokemon-card-detection-haiqn) (981 img; card/name/number/set boxes), [yolo-pjxst](https://universe.roboflow.com/yolo-pjxst/pokemoncarddetectcard) (972), [tcg-detector seg](https://universe.roboflow.com/tcg-detector/pokemon-card-detection-7aaz7-mxbhx) (242, polygons), [lewis seg](https://universe.roboflow.com/lewis-xaa6i/pokemon-cards-sk39y) (109), pokemon-card-obb (206) | Mixed photos and screenshots (A). S | Mostly axis-aligned **bboxes**, a few polygons/OBB, **no card ID** (invention-time has set-name classes). S | Mostly CC BY 4.0. Image provenance often unclear, likely scraped (A). S | **Detector pretraining/eval: medium.** Bboxes give no corners, so we would relabel or convert polygons. Pages blocked scripted access (Cloudflare). |
| Kaggle [ongshujian/real-and-fake-pokemon-cards](https://www.kaggle.com/datasets/ongshujian/real-and-fake-pokemon-cards) | 451 DSLR macro photos, 256×256, real/fake. S | Real/fake. S | Not verified. | Low. |
| Kaggle "Pokémon TCG Real Card Images Bank" (ELLIMAC, 123k files, 51 GB) and "…1K Subset" | Seen only in Kaggle sidebars. Probably official images despite "Real" (A). S | ? | ? | Check by hand with a Kaggle login. |
| [Kaggle ellimaaac/pokemon-tcg-all-image-cards](https://www.kaggle.com/datasets/ellimaaac/pokemon-tcg-all-image-cards) | 21k official card images, 3.7 GB. S | Set folders. | ? | None (we have TCGdex). |
| **Fallback, other TCGs** | | | | |
| [gabraken/mtg-detection](https://huggingface.co/datasets/gabraken/mtg-detection) | 13k **synthetic** MTG scenes, 28.5 GB, gated. V | 4 corners + visibility + angle. V | MIT (+ WotC Fan Content Policy). V | Corner-label format worth copying; data synthetic. |
| [PSA-Baseball-Grades](https://github.com/samsilverman/PSA-Baseball-Grades) | 11,500 baseball card images, 10 PSA grades, ~6 years old. S | Grade only. S | ? (A: PSA images) | Slab-detector extra at best; PSA owns the images (see below). |
| [GotThatData/sports-cards](https://huggingface.co/datasets/GotThatData/sports-cards) | Small (56 MB) sports-card fronts/backs + OCR text. S | Player/team/year. S | ? | Low. |
| MTG [reeshof/MTG-card-scanner](https://github.com/reeshof/MTG-card-scanner), [CollectorVision](https://github.com/HanClinto/CollectorVision) (AGPL; neural corner model; Pokémon "preview"), Yu-Gi-Oh [DRAW](https://github.com/HichTala/draw), [yugioh-ar](https://github.com/Adrian-Sandwich/yugioh-ar) | Real test sets either not released or still being collected. CollectorVision does not document its training data. S/V | – | Code licenses only. | None as data. CollectorVision's corner model is a possible second teacher (AGPL, check weights). |

## Top 3 in more detail

1. **FORB animated cards (eval).** The labels are exact prints, and `SM7_EN_52` maps to TCGdex `sm7-52`; older `HGSS3`/`DP2` codes
   need a small table. eBay photos are mostly one centred card, often on a table, sometimes slabbed or in a top loader
   (A, check a sample). That is close to our "card in the guide" UI. Caveats:
   - **EN only**, and skewed toward valuable/vintage cards.
   - Many cards are outside our trained 870, so the gallery must hold the matching TCGdex EN cards. That is also an
     honest test of unseen-card generalisation.
   - Images come via Snap's Google Drive copy of eBay photos. **Keep the photos local and unpublished, report only
     aggregate numbers**, and don't train the published model on them without a legal decision.
2. **TCG-AR real set (detector eval, maybe fine-tuning).** These are the only public real Pokémon photos with corner labels.
   - The ID scheme is pokemontcg.io (`sv3pt5-146` vs. TCGdex `sv03.5-146`), so set-ID mapping is needed.
   - Overhead, 1080p, ~150 px cards: a different domain from our close-up S25 shots, but it tests sleeves, holo and
     neighbours.
   - The code's GPL-3.0 matches the app. Before any training use, ask the authors for an explicit data license
     (CC BY would be ideal).
3. **CardCorpus QuadDet as a teacher.** Its data isn't available, but its Apache-2.0 detector was trained on real photos
   with hand-checked corners. Run it on our own photos, and internally on FORB, to pre-label corners, then review them in
   the existing `review.html` flow. That cuts manual corner labelling to clicks. Their eval (87–97 % top-1, no slabs)
   is a useful external benchmark.

## Marketplace and user photos: terms and legal risk

| Source | What the terms say | Risk for training / redistribution |
|---|---|---|
| eBay | The [API License Agreement](https://developer.ebay.com/join/api-license-agreement) (updated 2025-06) bans using "eBay Content", images included, "to train algorithms, conduct machine learning, develop synthetic data sets…". The User Agreement bans scrapers. S (page returned 403; wording from search results) | **High.** API use for this is explicitly forbidden. Seller photos are also © the sellers. |
| TCGplayer | [ToS](https://help.tcgplayer.com/hc/en-us/articles/205004918-Terms-of-Service): "not to crawl, scrape or spider… without express permission". API closed to new developers. S | **High.** |
| Cardmarket | Scripted access is blocked by Cloudflare. ToS not checked here (A: no scraping). | **High** (A). |
| Reddit (r/PokemonTCG etc.) | The Data API terms forbid using User Content for "training a machine learning or AI model, without the express permission of rightsholders" (since 2023). Scraping is banned in the User Agreement. S | **High.** A user asking individual posters for permission is fine; that is effectively our own collection. |
| PSA cert images | Cert DB access is "for the sole purpose of confirming data… with a particular certification number". PSA claims ownership of its images. S | **High** for bulk use. |
| EU/DE text and data mining (§44b UrhG / Art. 4 DSM) | Allows TDM on lawfully accessible works unless the rightsholder has opted out machine-readably; copies must be deleted when no longer needed. A | It does **not** cover redistribution or overriding contract terms (ToS/API). Get a legal opinion before relying on it. |

## Recommendation

1. **Now (eval):** download the FORB metadata (small, already in the repo) and the TCG-AR real set. Map both to TCGdex IDs
   and add them as eval-only sets next to the 34 own photos. Keep the images out of git, as with `ml/` real photos.
2. **Corner labels:** use CardCorpus QuadDet (Apache-2.0) as a pre-labeller on own photos, then correct by hand. Ask the
   TCG-AR authors for a data license. Check the CC0 `antokun` tar and the CC BY Roboflow polygon/OBB sets as detector
   extras, after looking at a sample for provenance.
3. **Durable source: own opt-in collection.**
   - In the app or test build: "contribute this scan" with explicit consent to a CC0/CC BY license, and EXIF/GPS
     stripped on upload.
   - Plus targeted shoots: binder pages, sleeves, top loaders, slabs, holo, JA/DE.
   - This is the only route that allows training *and* publishing the data and models cleanly.
4. **Don't** scrape eBay, TCGplayer, Cardmarket, Reddit or PSA, and don't redistribute FORB images.
