# Competitor gap analysis (2026-11-21)

Council of three seats, all at high effort: Claude Opus 5.5, GPT-6.2 Sol and Gemini 4 Argon. Each seat first
researched on its own. Gemini then also critiqued the first two-seat draft. The synthesis below keeps only points
that were checked against the code or a source. Competitor facts were checked on 2026-11-21. Store prices differ
by storefront, and download bands are global Play totals. Anything marked **(assumption)** is unverified.
Issue #203.

**Bottom line:** Foilark is the only Android app found that recognises cards on the phone, works offline, needs
no account and has no scan limit. The biggest gaps:
- switching cost: there is no import from other apps;
- correcting and completing a collection: there is no batch edit and no master-set mode;
- a backup that also keeps the user's own photos;
- ways to collect beyond the set: by species and by artist.

## Competitors that matter

| App | Model (Play US unless noted) | Scanner | Prices | Notable |
|---|---|---|---|---|
| [Collectr](https://play.google.com/store/apps/details?id=com.collectrinc.collectr) | Free + [Pro](https://www.getcollectr.com/pro) $7.99/mo or $59.99/yr; 1M+, 4.8★ | Limited free, unlimited Pro | Cardmarket + eBay (EU), TCGplayer + eBay | Raw, graded, sealed; portfolio charts; export is Pro; [TCGplayer CSV import by email](https://getcollectr.notion.site/Import-Export-Your-Collection-9aa6e5f4d0cd4efb995dadc992760c8e) |
| [Dex](https://play.google.com/store/apps/details?id=com.dextcg.app) | Free + Dex+ from $3.99/mo (lifetime $109 on iOS only); 100K+; free tier capped at 3 folders | Dex+ only; batch variant assignment; "Sweep" several cards | TCGplayer, Cardmarket, CardTrader, eBay | Master sets (every printing its own card) **and** a per-species mode; JP + ZH-CN; decks; trade finder; card notes are Dex+; sealed, graded and history [planned](https://dextcg.com/help/planned-features/price-history-in-dex) |
| [PokéCardex](https://play.google.com/store/apps/details?id=com.application.pokecardex) | Free + Plus €4.99/mo, €49.99/yr (DE); 1M+ | 30 free scans, unlimited Plus | Cardmarket, TCGplayer; 180-day charts in Plus | Strongest EU overlap: offline, master sets, sealed, graded, **free** account sync |
| [TCGplayer](https://play.google.com/store/apps/details?id=com.tcgplayer.tcgplayer) | Free; 1M+ | Free, one card at a time | TCGplayer market + history | Marketplace; CSV lists |
| [Shiny](https://play.google.com/store/apps/details?id=io.getshiny.shiny) | Free + IAP; 100K+, 7.85K reviews | "Unlimited scanning" (Play listing) | Cardmarket, TCGplayer, eBay, history free | Unlimited items and folders free; sealed; graded; centering tool; alerts |
| [Pooka](https://play.google.com/store/apps/details?id=com.bowvie.pooka) | Free singles + Shiny tier, IAP $2.99–27.99 | On-device EN/JP; binder pages on Shiny; saving needs a connection | Cardmarket, TCGplayer, eBay | Closest on-device rival; master-set checklists; swap matches between friends |
| [HoloDex](https://play.google.com/store/apps/details?id=com.getholodex.app) | IAP; 1M+, 28K reviews | Camera scan + "AI grading" | Market estimates | Large multi-TCG scanner |
| [Rare Candy](https://play.google.com/store/apps/details?id=com.rarecandy.rarecandy) | Free, account required | EN/JP, reads slabs | Market estimates | Master-set tracker; paid digital pack openings |
| [Dragon Shield](https://play.google.com/store/apps/details?id=pt.tscg.pokemanager) / [Sorted](https://play.google.com/store/apps/details?id=com.ccc.tcgsorted) | Legacy app still live, 1M+; Sorted (successor) has ads, Pro €3.99/mo (DE) | Sorted: 5 scans a day as a guest; [privacy policy](https://www.tcgsorted.com/legal/privacy) mentions a third-party photo path | TCGplayer, Cardmarket | CSV import/export; trade calculator; decks |
| [pokedata.io](https://play.google.com/store/apps/details?id=com.browse.pokedata) | Free + paid tiers; 100K+ | Search, no live scanner | eBay, TCGplayer, Cardmarket, per grade | Investor focus: P&L, population, sealed |
| [Ludex](https://www.ludex.com/membership/) | 60 saved cards free, then $4.99–24.99/mo | Photo upload | eBay | eBay listing; sports-first |
| [PriceCharting](https://www.pricecharting.com/page/app) | Ads + IAP; web $6/mo | Photo and UPC lookup | eBay/auction sales per grade | Graded price data |
| [Pokellector](https://play.google.com/store/apps/details?id=air.com.pokellector.mobile) | Ads + IAP; last update 2024-10 | Mostly code-card QR | External | Stale; malicious-ad review |
| [DMM Myca](https://play.google.com/store/apps/details?id=com.kimura_0531.myca&hl=ja) | JP market, free + shop | Name lookup | JP shop and buyback | Benchmark for domestic JP prices; shop buyback built in |

There is no official Cardmarket collector app. Pokémon TCG Card Dex was
[retired in 2023](https://support.pokemon.com/hc/en-us/articles/18773957465748-Pok%C3%A9mon-TCG-Card-Dex-Sunset-Information).
The Pokefolio app (9 languages incl. KO/ZH-TW, Cardmarket prices per language) is iOS only. Other clones
with a million or more installs, such as MonPrice and DittoDex, add nothing beyond HoloDex **(assumption)**.

## Feature matrix

I = implemented, P = planned, N = none. "Pro" = paid tier.

| Capability | Foilark | Collectr | Dex | PokéCardex | Shiny | Pooka |
|---|---|---|---|---|---|---|
| Free scan limit | **I: none, no account** | Limited | No free scanner | 30 cards | Unlimited | Free singles |
| Bulk scanning | **I: live sequential + review queue**; one card per frame | Sequential, Pro | Sequential + Sweep, Dex+ | Auto-add, Plus | Bulk | Binder page, paid |
| Batch correction | **N** | ? | Batch variant | ? | ? | ? |
| Languages | **I: EN/DE/FR/IT/ES/PT/JP** (JP 7.4k of 13.2k indexed); KO/ZH-TW P | JP catalogue | JP, ZH-CN catalogue | FR/DE/JP catalogue | ? | EN/JP scans |
| Master set (each finish counts) | **N** (one slot per number) | ? | I | I | ? | I |
| Species / artist collecting | **N** | ? | I (species) | ? | ? | ? |
| Graded | **I: grade stored**, valued at raw price | Prices | Planned | Records | Prices | N |
| Sealed | **N** | I | Planned | I | I | N |
| Price provenance | **I: source, date, priced-copy coverage** | ? | ? | ? | ? | ? |
| Price history | **I, Pro-gated**; free weekly change | Pro | Planned | Plus | Free | Paid |
| Alerts | **I on device, Pro-gated** | I | ? | ? | I | Paid |
| Sync | **P (Pro, #16)**; Android backup without own photos | I | I | **I free** | I | I |
| Import from other apps | **N** (own JSON/CSV only) | TCGplayer CSV | N | ? | ? | N |
| Export | **I free JSON/CSV** | Pro | Dex+ | Web CSV | ? | ? |
| Notes per copy | **N** | ? | Dex+ | ? | ? | ? |
| Trade help | **N** (trade binder only) | Trade analyzer | Trade finder | N | ? | Swap matches |
| Sharing | **I local images, no card art** | Social feed | Friends, links | Shared binders | ? | Wishlists |
| Offline | **I: recognition, collection, cached prices** | ? | iOS scanner | I | ? | Recognition only |
| Ads / data for advertising | **None / none** | None / ? | None / ? | None / yes | None / ? | None / yes |
| Open source | **GPLv3** | Closed | Closed | Closed | Closed | Closed |

"Data for advertising": the Play data-safety form lists "Advertising or marketing" as a purpose, checked for
PokéCardex and Pooka.

## Gaps, ranked

All three seats put import first and batch correction plus master set second. The rest is merged from the
three rankings and adjusted by the checks below.

| # | Gap | Value | Effort | Fit | Tier | Why now |
|---|---|---|---|---|---|---|
| 1 | **Import from TCGplayer, Collectr and PokéCardex files** (#204) | H: switching cost; [Reddit](https://www.reddit.com/r/PokemonTCG/comments/1qubsey/is_there_a_way_to_import_your_collection_from_say/); third-party importers exist just for this | M: `BackupCsv.read` (`Backup.kt`) accepts only our own columns; add header-sniffing adapters, match by TCGplayer ID (`card_mapping.tcgplayer_id`) or set + number + language, preview unmatched rows | Fits | Free | Switchers arrive at launch (#15); Collectr gates export, so start with TCGplayer CSV and real sample files |
| 2 | **Batch correction + master-set mode** (#205) | H: [master-set thread](https://www.reddit.com/r/PokemonTCG/comments/1qfy0l8/master_set_tracker/); Pokellector review asks for reverse holos | S–M: session finish/edition/condition preset next to the "Scanning into" chip; multi-select edit in collection and set views; per-finish slots from `Printings.kt` / pack `printings` (#143) instead of one `owned` flag in `SetSlots`; show unknown coverage (TCGdex flags weak for XY/SM, #108) | Fits | Free | The scanner adds cards faster than a pile can be corrected; set completion is the core loop |
| 3 | **Automatic local backup, including own photos** (#206) | M–H: data loss is a top fear (Myca complaints); PokéCardex syncs free; today neither the JSON export nor Android backup keeps the user's photos (settings hint) | S–M: folder the user picks (SAF), weekly WorkManager job; zip with `backup.json` + `OwnPhotos` files, readable by import | Fits (no server) | Free | Takes the sting out of sync being Pro, and closes a silent data-loss hole |
| 4 | **Collect by species and by artist** (#207) | H for theme collectors; Dex's best-known feature | M: TCGdex ships `dexId` and `illustrator` (checked: `sv03.5-025` → [25], Naoyo Kimura); add to the pack card DB and `CardEntity` (Room migration); tappable artist on card detail; search; species checklist | Fits (MIT data; call it "Species", not "Pokédex") | Free | Data is free and already one API field away; works in every pack language |
| 5 | **Trade help: duplicates filter + trade calculator** (#208) | M: Collectr, Dragon Shield, Dex and Pooka all have one | S–M: "Duplicates" filter (quantity > 1) in collection and set views; local two-sided calculator in the main market's currency, EUR and USD never mixed | Fits (offline, no account) | Free | Small, and it serves in-person trading at shops and fairs |

Later, in this order:

| Gap | Value | Effort | Note |
|---|---|---|---|
| Notes and own value per copy (#209) | M | S | `note` and a custom value on the copy. Graded copies are valued at the raw price today, so label that. Dex charges for notes |
| Sealed products (#210) | M, H for sealed collectors | M–L | Cardmarket `products_nonsingles_6.json` is free (HTTP 200) and in the price guide we stream; new catalogue entity; no art |
| Missing-cards list in a format a marketplace can read (#211) | M (EU buying flow) | S | Today the checklist reads `003/165 Venusaur ★`. Cardmarket wants-list and TCGplayer mass-entry formats are unverified **(assumption)** |
| Price date everywhere (#212) | L | S | Provenance is mostly done (#133, #161); collection detail and set header lack the date |
| JP prices in USD (TCGCSV category 85) (#213) | L–M (US users with JP cards) | S | JP already has Cardmarket EUR: 10,035 of 12,833 JP cards priced (checked in the price DB). Label as US-market price |
| Read-only "trade table" mode (#214) | L–M | S | Hides purchase prices and locks edits when handing the phone over |
| Binder-page capture (#215) | M | L | First measure kept cards/minute with the sequential flow plus batch correction |
| Cloud sync (#16) | M | L | #16; tombstones, merge rules, photo consent |
| Centering check (#216) | L–M | M | On-device only; no grade or value prediction |
| Deck building | L | L | Off-focus for collectors |

## Where Foilark already wins

| Advantage | Store wording |
|---|---|
| Unlimited free scans, no account (limits elsewhere: PokéCardex 30, Sorted 5/day, Ludex 60 cards, Dex paid; Shiny is also unlimited but needs an account for sync) | "Unlimited free scanning. No account needed." |
| Recognition, collection and prices work offline (Pooka needs a connection to save) | "Scan offline after downloading a language pack." |
| Free JSON/CSV export (Collectr: Pro; Dex: Dex+) | "Export your collection as JSON or CSV, free." |
| Seven scan languages on Android incl. DE and JP | "Scans English, German, French, Italian, Spanish, Portuguese and Japanese cards." |
| Honest prices: source, date and priced-copy share shown; EUR and USD never converted; deep link filtered to the card's language | "Cardmarket (EUR) and TCGplayer (USD) prices, clearly labelled." |
| Wishlist never counts toward value; finish and edition separate | "Track holo, reverse holo and 1st edition separately." |
| Open source, no ads, no analytics SDK, no data for advertising (PokéCardex and Pooka declare it) | "Free, open source, no ads." **Not** "no tracking": ML Kit sends diagnostics to Google (`backend/src/privacy-en.ts`, section 5) |

Keep "for Pokémon TCG cards" plus the no-affiliation disclaimer. No "best" or "fastest" claims until measured.

## Do not copy

| Feature | Who | Why not |
|---|---|---|
| Paid digital pack openings | Rare Candy | Gambling mechanic; #176 rules |
| "AI grading", grade predictions, price forecasts | HoloDex, clones | A phone photo can't judge surface or print lines; can't be checked |
| Per-language prices scraped from listings, shop scraping (Yuyu-tei) | Manakodex, TCG Vault | Cardmarket API closed; legal-sources rule |
| eBay sold or PriceCharting data | Ludex, PriceCharting, Shiny | Closed to new apps, or the licence forbids redistribution |
| USD converted to EUR as a stand-in | TCG Vault, others | EUR and USD never mix |
| Made-up condition multipliers (LP = 0.7 × NM) | Generic trackers | The free price guide has no condition split |
| Server-side photo matching as the default | Sorted | Images stay on the phone |
| Scan caps, ads before scans, export or notes paywall | Collectr, PokéCardex, Dex+, CardDex | Free tier must be fully usable |
| Badges, social feed, marketing emails, data for advertising | Dex+, Collectr, Dragon Shield, Pooka | Streak/nag rules, moderation load, privacy |

## Council disagreements and checks

| Point | Opus | Sol | Gemini | Check |
|---|---|---|---|---|
| JP prices | Gap #2, assumed few JP cards priced | Confirm access first; TCGCSV returned 401 | Not raised | 78 % of JP cards have a Cardmarket price; category 85 answers (463 groups). Moved to "later" |
| Price provenance | — | Gap #3 | Mostly built already | Gemini is right: source and coverage are shown on value screens; only the date is missing in two places. Moved to "later" |
| Backup | Gap #5, run the JSON export | Not raised | Must include own photos | Settings hint confirms photos are in no backup today. Now #3 with photos |
| Species / artist | Later, "if the pack has dex ids" | Not raised | Top 3 | TCGdex has `dexId` and `illustrator`; neither is in `CardEntity`. Now #4 |
| Trade help | Later | Not raised | Top 4, with duplicates filter | No duplicates filter exists. Now #5 |
| Sealed | #4 | #8, start manual | #6 | Later: needs a new entity and has no art, while #3–#5 reuse what exists |
| Shiny | — | — | Unlimited free scans, Cardmarket, free history | Confirmed on the Play listing; table fixed |
| "No tracking" claim | Suggested | Noted ML Kit diagnostics | — | Dropped; privacy text discloses ML Kit |
| Wider scope (offline wishlist swap via QR) | — | — | Suggested | Left out: needs a sharing format and adds little until trade help exists |

## Open decisions

1. Which import comes first: TCGplayer CSV (documented format, US reach) or Collectr? Real sample files needed.
2. What counts toward a master set: stamped promos, deck exclusives, language-specific prints (#108)?
3. Pro scope at launch: Shiny, TCGplayer and Rare Candy give history free. Short free range (e.g. 30 days) or keep
   it all in Pro?
4. Sync: Pro-only while PokéCardex and Shiny sync free, with automatic local backup as the free middle ground?
5. Species view: one card per species (binder style) or every card of a species, or both?
