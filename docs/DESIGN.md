# Design

A free, open-source Android app for Pokémon TCG collectors. You point the camera at your cards, they are
identified **on-device** in many languages, priced, and sorted into collections.

Status: design agreed on 2026-11-15, nothing implemented yet. Each decision has a short ADR in [`adr/`](adr/).

## Goals

- Recognize cards fully offline. No image ever leaves the phone unless the user opts in to contributing it.
- Bulk-scan a pile of cards quickly.
- Support many languages: v1 covers EN, DE, FR, IT, ES, PT and JP. NL and PL wait for card data (TCGdex has
  none, 2026-11-18); KO and ZH come later.
- The free tier is fully usable: unlimited scans, collections and current prices.
- Use no Pokémon trademark in the app name or icon.

## Non-goals (v1)

- Cloud sync, price history charts and price alerts. These form the later **Pro** tier.
- eBay prices. Its API shows only active listings, sold data is closed, and calls are rate-limited.
- Per-language Cardmarket prices. The free price guide mixes all languages.
- An F-Droid build. It needs an open OCR engine first.

## Architecture

```
┌──────────────── Android app (Kotlin, Compose) ────────────────┐
│ CameraX ─► detector (YOLO-n) ─► rectify crop ─► embedding     │
│                                    │            model (LiteRT)│
│                                    └► number OCR (ML Kit)     │
│                 kNN over language-pack index + number filter  │
│                 ─► confident: add │ unsure: review queue       │
│ Room DB: copies, collections, cached prices, packs            │
└───────────────▲──────────────────────────▲────────────────────┘
                │ language packs (R2)      │ prices (Worker API)
┌───────────────┴──────── Cloudflare ──────┴────────────────────┐
│ Worker: price API, reads price files from R2                  │
│ R2: price files per set + daily snapshots (kept from day one) │
│ R2: language packs (model, index, card DB, deltas)            │
│ (later) auth, sync, alerts for Pro                            │
└───────────────────────────────▲───────────────────────────────┘
                                │ price files (S3 API)
Vulcan (homelab), nightly: TCGdex + Cardmarket price guide + TCGCSV
  ─► mapping ─► SQLite ─► price files + snapshot (ADR 0011)
Offline: training on the RTX 3080 (detector, embeddings, later the OCR model)
```

## Recognition

1. **Detect** the card in live frames with a YOLO-n detector, then perspective-correct the crop.
2. **Embed** the crop with a small fine-tuned model (MobileNet/EfficientNet class, ~10–20 MB). Run it on LiteRT
   with the GPU or NNAPI delegate, and fall back to the CPU at a lower frame rate.
3. **Match** the embedding against the reference index from the installed language packs (kNN).
4. **Disambiguate** by reading the collector number (e.g. `025/165`) with ML Kit text recognition and using it
   to filter or rerank the candidates. Later an own small OCR model replaces ML Kit (needed for F-Droid).
5. **Decide**: confident hits are added with the default variant and condition; a card printed in a single finish
   (holo-only rares, ex/V/full arts, from the pack's `printings`, #143) gets that finish instead. Unsure hits go to
   a **review queue** with a top-3 picker and variant chips. Normal and reverse holo can't be told apart reliably
   from a photo, so the variant is always editable.
   Scans land in All Cards; a "Scanning into" chip on the Scan screen adds them to one custom collection as well
   (kept until changed; a deleted collection falls back to All Cards only).
6. **Reveal** (#168): an added card worth **€20 / $20 or more** gets a brief light halo around it in the frame, a
   double-pulse haptic and the hint "Worth €20 or more. Added." The test is the hit's own price (for its finish and
   edition) in the user's **main market**, against the threshold in that market's currency: €20 for Cardmarket, $20
   for TCGplayer. There is no conversion and no fallback to the other market, so EUR and USD never mix; a card the
   main market doesn't price gets no reveal. The reveal never waits: the camera keeps deciding, and the next
   decision replaces it. With animations off there is no halo, only the haptic and the text. No sound.

Reference images: TCGdex for the EU languages. For JP, TCGdex has images for only ~3.9k of ~13k cards, so the
gaps are filled by a one-time crawl of the official JP card database (check its terms first) and by opt-in
user scans.

## Language packs

The APK stays small. On first launch the user picks languages, and the app downloads a pack for each one from
R2: card metadata, the reference embedding index and model files. New sets arrive as **delta** packs, without
an app release. Format, deltas and the app's checksum checks: [packs.md](packs.md).

## Collections and data model

- **All Cards** holds every owned copy.
- **Set collections** are created automatically for each set the user owns a card from. They show completion
  (e.g. 87/165) and have a language filter. EN/DE/FR/… prints of an international set share one collection; JP
  sets such as SV2a are their own set collections.
- **Custom collections** are made by the user: binders, a trade pile.
- The **wishlist** holds wanted prints, not copies, so they count nowhere: not as owned, not in set completion, not
  in the value. A wanted print the user then adds a copy of shows as found until it is removed.
- **Per copy**: quantity, finish (normal/holo/reverse holo) and edition (unlimited/1st edition/shadowless) as two
  separate fields, so a 1st-edition holo fits; condition (NM…Poor) or grade (PSA/CGC…), purchase price and date,
  and optionally the user's scan photo. Each copy has a stable uuid and an `updatedAt` for export/import and sync.
- **Storage** is local-first in Room. A free JSON/CSV export serves as backup, and the last-known prices stay
  cached offline.

## Prices

| Source | Data | Notes |
|---|---|---|
| Cardmarket price guide (`price_guide_6.json` + `products_singles_6.json`) | EUR trend/avg/low, nightly | Free and keyless. The prices **mix all languages**, so they're labeled that way, with a deep link filtered to the card's language. |
| TCGCSV (TCGplayer dumps) | USD market price for each variant, daily | Free; EN cards only |

- Our own nightly import maps TCGdex cards to Cardmarket `idProduct` and TCGplayer `productId`. The TCGdex
  mapping is the seed, matching uses set and number, and a manual override table fixes mistakes.
- **Currency** follows the region: EU locales show Cardmarket EUR first, US shows TCGplayer USD first. The
  user can switch, and the secondary price is shown smaller.
- **Daily snapshots** are stored from day one, so Pro history and charts have data when Pro launches.

## Card images

Card images load from the TCGdex CDN and are cached locally. No card art is bundled in the APK, and the user's
own scan can stand in as the card's image.

## Pro tier (after the MVP)

Pro covers cloud sync across devices, price history and portfolio charts, and price alerts. It's sold as a
cheap subscription plus a lifetime option through Play Billing, with sign-in through Google Sign-In
(Credential Manager). Pro features are server-side, so the open-source code doesn't weaken them. Price history comes from
per-set monthly files derived from the daily snapshots; until billing exists, Pro is gated by tester tokens
([ADR 0012](adr/0012-price-history-and-pro-gate.md)). Price alerts are the one exception: until sign-in and push
exist, the phone checks them once a day against the free current prices, a soft gate that moves to the server with
#16 ([ADR 0013](adr/0013-price-alerts-on-device.md)).

## Open data and contributions

- The code is fully open source: the app under **GPLv3**, the backend (`backend/`) under **AGPLv3**.
- Model weights, the reference index and the training code are published.
- The **dataset** is published as a manifest (card IDs, source URLs, crop boxes, labels) plus a download script.
  Card art from TPC is not re-hosted.
- **User scans** are opt-in and off by default. Only the rectified card crop is uploaded, with no EXIF,
  location or device data. Contributors explicitly consent to release it under **CC0**, and a withdrawn scan is
  dropped from future dataset releases.

## Interface

The UI direction is **Pearl** ([ADR 0014](adr/0014-ui-direction-pearl.md)): porcelain surfaces, sea-glass actions,
Schibsted Grotesk in `sp`, a companion night scheme, and one thin foil edge on the featured set cover. From Index
([ADR 0010](adr/0010-ui-direction-index.md)) it keeps the collector number as the main type, one slot per card with
owned slots solid and missing ones hollow, and the value header with its weekly change. The design study is in
`design/mockups/lumifoil/`.

The shared Compose pieces live in `ui/theme` (colour roles, type, shapes, spacing) and `ui/components` (app bars,
buttons, chips, focus ring, foil cover, rows, notices, wordmark). Screens use these roles, never raw colours. The
header shortcut always means **Scan**. Which screens already follow Pearl is tracked in #95.

## Platform and distribution

- minSdk 26, aimed at mid-range phones from 2020 on.
- Distributed through the Google Play Store. A new personal developer account must run a closed test with 12
  testers over 14 days.
- The name is **Foilark** (#15, chosen 2026-11-18 after the trademark screening in
  `docs/release/play-launch.md`; it replaced Lumifoil the same day): a neutral TCG/binder name without "Poké…" and without a Pokéball icon. The store
  text says "for Pokémon TCG cards" and carries a disclaimer of no affiliation. Play package
  `de.fabianduerkop.foilark`; the Kotlin namespace stays `de.fabianduerkop.cardindex`.

## MVP scope

1. Live scanning for the EU languages and JP, with language packs and a review queue.
2. Collections (All Cards, set and custom) with per-copy details.
3. Current prices from Cardmarket and TCGplayer, with the backend import and snapshots running.

Later: Pro, KO/ZH, an own OCR model with an F-Droid flavor, and eBay.

## Research basis (2026-11-15)

- TCGdex: MIT, no key, self-hostable. Card counts / with image: en 23.9k/22.1k, de 20.8k/15.2k, ja 13.2k/3.9k,
  ko 239/0, zh-cn 877/0.
- pokemontcg.io is deprecated: no new keys, and existing keys stop working on 2027-03-01.
- The Cardmarket API is closed to new apps; the price-guide JSON files are public.
- The TCGplayer API is closed; TCGCSV mirrors its data daily.
- Prior art: CardCorpus (DINOv3-based, ~335 MB, 87–97 % top-1), plus small open projects that use YOLO +
  MobileNet/EfficientNet embeddings or pHash + number OCR.
