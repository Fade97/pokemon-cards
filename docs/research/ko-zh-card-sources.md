# Research: card data and reference images for Korean and Chinese

Part of #18. Checked 2026-11-18. Not legal advice. Items marked **Annahme** are unverified.

**Requests made:** TCGdex API (card and set lists for `ko`, `zh-cn`, `zh-tw`, a few card and set pages) and 26 `HEAD`
probes on its CDN; pokemoncard.co.kr (robots.txt, card search page, 1 detail page, terms page, 7 image requests);
asia.pokemon-card.com (robots.txt, 3 list pages, 3 detail pages, terms page, 1 image); pokemon.cn (robots.txt, 1
article, terms page); limitlesstcg.com (robots.txt, 1 card page, 20 `HEAD` probes on its CDN); robots.txt of 8
community sites; the GitHub API and the JSON file of one community dataset. All one at a time, ≥1 s apart, with a
generic browser User-Agent. **Images:** 2 spot checks (one KO, one TW) plus 11 images (8 Traditional Chinese from TCGdex, 3 Korean
from pokemoncard.co.kr) for the embedding test in §4. All were deleted afterwards.

## Summary

- **Korean and Traditional Chinese mirror the Japanese sets.** The official Korean site and the official Taiwan/HK site
  use the Japanese set code and number (`M6a` 001/103, `SV7` 050/102, `SV8a` 100/187), and TCGdex gives KO and
  zh-tw cards the **same id as the JA card** (`SV4K-001`). Mapping is free.
- **Simplified Chinese does not.** Mainland China has its own sets (`CSM1aC`, `CS1aC`, `CSV1C` …) with their own
  numbers, made from cards of several Japanese sets. TCGdex `zh-cn` today is only a translation of Japanese cards,
  not the real mainland sets (tcgdex/cards-database#958). Each card has to be matched to a Japanese card one by one.
- **TCGdex today:** `ko` 239 cards / 0 images, `zh-cn` 877 / 0, `zh-tw` 7,436 / 2,146. No CDN gap: 21 of 21 unlisted
  files probed were 404.
- **A Japanese reference image can stand in** for KO and zh-tw: in a small test (11 cards, §4) the KO or zh-tw image
  found the same Japanese card first 7 times, and second in the other 4 cases. In 3 of those 4 the card ranked
  first was a reprint with the same artwork, which the number check would sort out. Only clean images were tested,
  not phone photos.
- **Official sources:** Korea and Taiwan/HK each have a full official card database with images (HTML + JSON, no
  API). Their terms forbid copying without permission. Korea adds a **"Pokémon Card Game" watermark** to every image.
  Mainland China has **no card database on the web**, only a WeChat mini-program.
- **Limitless TCG** has no KO or Chinese images, but it **does have Japanese images TCGdex lacks** (e.g. `M6a`, `M3`).
  Through the stand-in, that would help JA, KO and zh-tw at once.
- **Recommendation:** add `zh-tw` and `ko` packs that use the JA image wherever the language has none (option B), leave
  `zh-cn` for later. See [Decisions for Fabian](#decisions-for-fabian).

## 1. Sources

### TCGdex (already in use)

| Language | Sets | Sets with cards | Cards | With image | Notes |
| --- | --- | --- | --- | --- | --- |
| `ko` | 95 | 3 (`SV4K`, `SV4M`, `SV5K`) | 239 | 0 | Set list mirrors JA ids (`SM1M` … `SV6`), card lists almost empty |
| `zh-cn` | 57 | 8 (`SV7`–`SV10`, `CSMPiC`) | 877 | 0 | Mainland set ids are listed (`CSV1C` …) but have no cards; the 829 cards in `SV*` are JA cards with Chinese names |
| `zh-tw` | 98 | 83 | 7,436 | 2,146 | Many sets have images for 001–020 only; full images for `SV7`–`SV10`; no M series (`M1S` … `M6a`) |

- API: `https://api.tcgdex.net/v2/zh-tw/cards`, `https://api.tcgdex.net/v2/zh-tw/sets/SV4K`. Image:
  `https://assets.tcgdex.net/zh-tw/SV/SV4K/001/high.webp`.
- **CDN gap probe:** 21 files the API leaves out (`ko` 7, `zh-cn` 5, `zh-tw` 9, e.g. `zh-tw/SV/SV4K/021`,
  `ko/SV/SV4K/001`) → all **404**. Control `ja/SV/SV4K/001` → 200. So `cdn_gap` would add nothing here.
- Known data bugs: KO/zh-tw set names copied across sets (cards-database#2485), Asian promo numbers that collide
  across markets (#1958), JA `M6a` Cardmarket ids pointing at the Simplified Chinese product (#2439).

### Korean — pokemoncard.co.kr (official, Pokémon Korea Inc.)

| | |
| --- | --- |
| Search | [`/cards`](https://pokemoncard.co.kr/cards): HTML list; "load more" posts to `/v2/ajax2_dev2` (`action=get_more_cards`) and gets JSON (`CardNum`, `feature_image`) |
| Detail | [`/cards/detail/BS2026006001`](https://pokemoncard.co.kr/cards/detail/BS2026006001): name, `001/103`, set symbol `M6a`, product name, text |
| Image | `/public-assets/home/data/cards.image_www/data/wmimages/<SERIES>/<SET>/<SET>_<NNN>.webp`, e.g. `wmimages/MEGA/M6a/M6a_001.webp`, `wmimages/SV/SV8a/SV8a_100.webp`. 868×1212 WebP. The same path without `wm` (`images/…`) → 403 |
| Watermark | Every image has a large diagonal "Pokémon Card Game" over the art and text |
| Coverage | ~570 products in the filter list, from DP to `M6a`. A community overview says it lists all Korean cards incl. promos ([DCInside](https://gall.dcinside.com/mgallery/board/view/?id=pokemoncardgame&no=51429)). Total count is not shown without paging (**Annahme:** 15–20k) |

### Traditional Chinese — asia.pokemon-card.com (official, The Pokémon Company)

| | |
| --- | --- |
| Search | [`/tw/card-search/list/`](https://asia.pokemon-card.com/tw/card-search/list/): HTML, **14,602 cards**, 731 pages of 20. Hong Kong: [`/hk/card-search/list/`](https://asia.pokemon-card.com/hk/card-search/list/) **14,604** (own ids, `hk000…`) |
| Detail | [`/tw/card-search/detail/19913/`](https://asia.pokemon-card.com/tw/card-search/detail/19913/): name, `collectorNumber` 001/103, `expansionCodes=M6a` |
| Image | `https://asia.pokemon-card.com/tw/card-img/tw00019913.png`, 868×1212 PNG, **no watermark** |
| Coverage | Oldest entries are Asia-only SM-era decks (`AC1D`, 157 cards) up to `M6a` today |

From the Sword & Shield era on, Taiwan/HK sets mirror Japan (set mark `M6a F`, `SV7 F`). The SM era and some S-era
products were Asia-only (`AC*`; TCGdex `SC1a`, `SC2D`, `SCA` …), with no JA twin.

### Simplified Chinese — mainland China

| Source | What | Format | Cards | Images |
| --- | --- | --- | --- | --- |
| [pokemon.cn](https://www.pokemon.cn/tcg-home) (official, Pokémon Shanghai) | News, products, rules. **No card database** | HTML | — | product shots only |
| "宝可梦卡牌会员" WeChat mini-program ([announcement](https://www.pokemon.cn/tcg/other/17199.html)) | Official card search | inside WeChat, behind membership | all | yes |
| [duanxr/PTCG-CHS-Datasets](https://github.com/duanxr/PTCG-CHS-Datasets) (GitHub, community) | Dump of the mini-program's data: 246 products, 24,544 entries, 21,707 card ids, set code, number, name, text, illustrator, image path | one JSON (28.6 MB) + PNGs in the repo | 21,707 | 24,544 |
| [tcg.mik.moe](https://tcg.mik.moe/cards), [kado.hk](https://www.kado.hk/database/cn), [pikaqian.com](https://pikaqian.com/sets), [pokemonlore.com](https://pokemonlore.com/chinese-tcg-cards/csvl2c) | Community lists | HTML | 15k+ (kado.hk) | re-hosted official art |

The dataset has **no JA set or number per card**, only name, HP, attacks, illustrator and the mainland number
(e.g. `CSV1C` 001/127 榛果球). Mainland sets merge cards from 2–3 Japanese sets (cards-database#958).

### Korean / Chinese community sites (not further checked)

icu.gg and cardmon.com (KO; icu.gg timed out, cardmon did not resolve), [CardTan](https://www.cardtan.tw/),
[Pokeversal](https://sc.pokeversal.com/sc), [Art of Pokémon](https://www.artofpkm.com/cards?lang=zh-Hans). All show
copies of the official images; none is a better source than the official site behind it.

## 2. Terms of use and robots.txt

Retrieved 2026-11-18. Translations are mine, not official.

| Source | robots.txt | Key clause (original) | Translation |
| --- | --- | --- | --- |
| pokemoncard.co.kr — [Pokémon Korea terms](https://www.pokemonkorea.co.kr/terms) (in force since 2026-10-03, cover "포켓몬 카드 관련 사이트") | `User-agent: * Allow: /` | 제12조 ② 이용자는 관계 법령에서 허용하는 경우를 제외하고 회사의 사전 승낙 없이 서비스에서 제공되는 콘텐츠를 복제, 배포, 전송, 출판, 판매, 변형하거나 기타 방법으로 영리 목적으로 이용할 수 없습니다. | §12(2): Except where the law allows, users may not copy, distribute, transmit, publish, sell or alter the content, or otherwise use it for profit, without the company's prior consent. |
| ″ | | 제11조 ① … 회사의 승인 없이 서비스 또는 서비스에서 제공되는 정보를 영리 목적으로 이용하는 행위 | §11(1): forbidden: using the service or its information for profit without approval. |
| ″ | | 제12조 ③ … 영리 목적으로 이용하고자 하는 경우에는 사전에 회사와 이용 범위 및 조건을 협의하여야 합니다. | §12(3): Anyone who wants to use the content for profit must agree on scope and terms with the company first. |
| asia.pokemon-card.com — [訓練家網站規約](https://asia.pokemon-card.com/tw/policy/) (The Pokémon Company; no date shown) | file exists, **empty** (0 bytes, last modified 2021-11-05) | 第7條 本服務所刊載的設計、照片、影像、文章…等所有內容資料（下稱「資料」）之著作權或使用授權之權利，歸屬本公司所有。…嚴格禁止從本服務中拷貝、複製、變更、出版、揭露、電信傳送、或散布資料。且不得於本服務外使用此等資料。 | §7: Copyright in all content data belongs to TPC. Copying, reproducing, altering, publishing, disclosing, transmitting or distributing the Data from the service is **strictly prohibited**, and the Data **may not be used outside the service**. |
| ″ | | 第11條 禁止行為 … 使用本服務之營業活動或以營利為目的之行為。 | §11: forbidden: business or for-profit activity using the service. |
| pokemon.cn — [使用条款](https://www.pokemon.cn/termofuse) (no date shown) | WordPress defaults; `/wp-content/uploads/` disallowed | 1.著作权声明 本网站提供的任何内容（包括但不限于设计、数据、文字、图表、图像…）的著作权…均属于株式会社ポケモン。本网站公开的内容，仅限个人娱乐之用，不得作商业用途。未经本公司事先的书面许可，不得以任何方式擅自复制、改编、传播、出版、转帖本网站的内容。 | §1: All content (designs, data, text, images …) belongs to TPC. Content is for **personal enjoyment only**, not for commercial use. No copying, adapting, spreading, publishing or reposting **in any way** without prior written permission. |
| PTCG-CHS-Datasets — README disclaimer | GitHub | "PTCG-CHS-Datasets shall only be used for non-commercial, academic, or research purposes." … "No Redistribution: The user may not redistribute, sub-license, or make PTCG-CHS-Datasets available to any third parties without the explicit written consent of the official owner" | No licence file. The dataset itself re-hosts official images taken from the mini-program. |
| limitlesstcg.com | `User-agent: * Disallow:` (allow all) | Footer: "The literal and graphical information presented on this website … including card images and text, is copyright The Pokémon Company … This website is not produced by, endorsed by, supported by, or affiliated with Pokémon …" | No terms of use beyond this (same as in `ml/packs.py`). |
| tcg.mik.moe, kado.hk, pikaqian.com, sc.pokeversal.com, artofpkm.com | allow general crawlers (some block single bots or `/api/`) | — | not read further |

**Compared with Japan** ([jp-card-database.md](jp-card-database.md)): the Taiwan and mainland terms copy the JP wording
almost word for word (all content to TPC, no copying, personal use only). The Korean terms are softer on paper: the
ban is tied to "without consent" and "except where the law allows", and the stress is on use "for profit". Whether a
free app with a planned Pro tier (ADR 0007) counts as "for profit" is open (**Annahme:** likely yes). The
same three uses as for Japan apply here: (i) storing facts and URLs is low risk; (ii) the app loading images from these
sites is out; (iii) a one-time fetch to embed is the use the terms forbid most directly.

## 3. Mapping to TCGdex ids

### Korean and Traditional Chinese: same as Japanese

| Check | Korean official | Taiwan official | TCGdex | Match |
| --- | --- | --- | --- | --- |
| Exeggcute | `M6a` 001/103 (`BS2026006001`) | `M6a` 001/103 (`19913`) | `ja` `M6a-001`; not in `zh-tw`/`ko` yet | set + number, same art (looked at) |
| Rhyhorn | `SV7_050` | TCGdex `zh-tw` `SV7-050`, set mark `SV7 F` 050/102 | `ja` `SV7-050` | same art (looked at) |
| Roaring Moon | `SV8a_100` (100/187) | `zh-tw` `SV8a-100` | `ja` `SV8a-100` | same name |
| Pansage | `SV4K_001` | `zh-tw` `SV4K-001` | `ja`, `ko`, `zh-tw` all `SV4K-001` | same id in TCGdex |

- **Rule:** `(set code, number)` on the official page = TCGdex JA id. Korean images even use it in the file name.
- **Exceptions:** Asia-only products without a JA twin (TW `AC*` SM-era decks; TCGdex `zh-tw` `SC*`, `SI`, `SVD` …),
  Asian promos (`S-P`, `SV-P`, numbers collide, #1958), and Korean-only promos (league prizes, events). These need
  their own images or stay unindexed.
- **What a KO or zh-tw pack could use today**, counting a JA image (TCGdex incl. the CDN gap) as stand-in:

| Pack | Cards in TCGdex | Own image | Same id in JA | JA image | Own or JA image |
| --- | --- | --- | --- | --- | --- |
| `zh-tw` | 7,436 | 2,146 | 4,029 | 3,948 | **4,665 (63 %)** |
| `ko` | 239 | 0 | 239 | 239 | 239 (but the card list itself is almost empty) |

  The 2,771 zh-tw cards left are mostly Asia-only `SC*`/`SI` sets and JA sets TCGdex has no image for. A Korean pack
  would need its card list from the JA list (13,156 cards, 7,359 with an image) plus Korean names from somewhere
  (§5, decision 2).

### Simplified Chinese: own sets, matched by artwork

| Mainland set | Cards (dataset) | Numbering | JA twin |
| --- | --- | --- | --- |
| `CSM1aC` 横空出世 赫 (2022-10-28) | 331 entries | 001/151 … | mix of SM cards (**Annahme:** SM1–SM4) |
| `CS1aC` 极巨争锋 雷 | 329 entries | 001/135 … | mix of S cards |
| `CSV1C` 亘古开来 (2025-01-17) | 393 entries, 167 numbers | 001/127 … 167/127 | mix of SV1S/SV1V/SV1a (**Annahme**) |
| `CSV8C` 璀璨诡幻 | 616 entries | 001/207 … | mix of SV cards |

No field links a mainland card to a JA card. Two ways to build that link: (a) name + HP + attacks + illustrator
against the JA list, or (b) embed each mainland image and take its nearest JA card (the test in §4 suggests this
works), then check the number pairs by hand per set. Either way each mainland card gets its **own id** (e.g.
`CSV1C-001`), because prices and collections need the mainland printing, not the JA one.

## 4. Can a Japanese image stand in? Test

Setup: the app's embedder (`mnv3_256_real_w8`, the one in the published packs) and the JA pack's 7,359 embeddings
(`packs-work/ja`). Query = a clean KO or zh-tw reference image of a card that has a JA image. Script:
one-off, not in the repo.

| Query | Rank of the JA card | Cosine | Ranked first instead (if not the right card) |
| --- | --- | --- | --- |
| zh-tw `SV4K-001` | 1 | 0.47 | |
| zh-tw `SV4K-015` | 1 | 0.78 | |
| zh-tw `SV7-050` | 2 | 0.46 | `SM3H-027` Riolu (0.48), a different card |
| zh-tw `SV8-080` | 1 | 0.53 | |
| zh-tw `SV8a-100` | 2 | 0.66 | `SV5K-051` Roaring Moon, **same art** (reprint) |
| zh-tw `SV9-030` | 1 | 0.66 | |
| zh-tw `S12a-010` | 2 | 0.47 | `S10P-005` Kricketune, same name (**Annahme:** same art) |
| zh-tw `SV10-090` | 1 | 0.72 | |
| ko `SV4K-001` (watermark) | 1 | 0.54 | |
| ko `SV7-050` (watermark) | 1 | 0.67 | |
| ko `SV8a-100` (watermark) | 2 | 0.66 | `SV5K-051`, same art |

- **7 of 11 first, 11 of 11 in the top 2.** 3 of the 4 second places lost to a reprint with the same artwork; there
  the collector-number check (OCR rerank) decides, as it already does for JA reprints.
- Cosines are much lower than for the card's own language (a JA image against its own row is ~1.0), because
  the name, attacks and text box differ. **Annahme:** with a real phone photo of a KO/zh-tw card the margin shrinks
  further. This needs a test with real photos (`RealPhotoTest`) before a pack ships.
- The Korean watermark did not stop a match here, but it is one more reason not to use Korean official images
  as references even if allowed: every reference would carry the same overlay.

## 5. Limitless TCG and other permitted sources

| Source | KO | zh-tw | zh-cn | JA (helps all three via stand-in) |
| --- | --- | --- | --- | --- |
| Limitless CDN `tpci/` (in use for `mee`) | no | no | no | no (`MEE_001_R_JP_LG.png` → 403) |
| Limitless CDN `tpc/` (JP cards) | no (`_KO`, `_KR` → 403) | no (`_ZH`, `_TW`, `_CT` → 403) | no (`_CN`, `_CS` → 403) | **yes:** `tpc/SV4K/SV4K_1_R_JP_LG.png`, `tpc/M6a/M6a_1_R_JP_LG.png`, `tpc/M3/M3_1_R_JP_LG.png` → 200 |
| TCGdex CDN gap | none (21/21 404) | none | none | done (#47) |

- Limitless lists Japanese cards at [`limitlesstcg.com/cards/jp/SV4K/1`](https://limitlesstcg.com/cards/jp/SV4K/1);
  number not zero-padded in the file name. It has **M-series images that TCGdex lacks** (TCGdex JA: M series 120 of
  2,149 with an image). How many JA gap cards it covers is not counted yet.
- **Where Limitless gets its JP images is not stated.** The international ones come from the malie.io export of
  Pokémon TCG Live; TCG Live has no Japanese cards, so the JP ones most likely come from the official JP site
  (**Annahme**). Using them would then be the same images #6 decided not to crawl from pokemon-card.com, only
  through someone else's copy. That is a decision, not a technicality (decision 3).
- No other source found that has KO or Chinese images under terms that allow our use.

## 6. Options

| Option | What | KO cards with a reference | zh-tw cards with a reference | Risk | Effort |
| --- | --- | --- | --- | --- | --- |
| **A. Wait** | Keep #18 at "later" | 0 | 0 | none | none |
| **B. Packs with JA stand-in** (recommended) | `zh-tw`: TCGdex card list, own image where TCGdex has one, else the JA image of the same id (new `packs` fallback rule, like `REF_FALLBACK_SETS`). `ko`: same, but card list = JA list + TCGdex `ko` names where present | ≤ 7,359 (only JA cards with an image; names mostly Japanese until a Korean name source exists) | 4,665 | none new (TCGdex only) | ~1 day: fallback rule + tests, `ko`/`zh-tw` in `fetch`/`embed`, real-photo check on a few KO/zh-tw cards |
| **C. B + Limitless JP for the M series** | Add the Limitless `tpc/` JP images as a second fallback for JA cards TCGdex has no image of | + M series (~2,000, **Annahme**) | + M series once TCGdex lists the zh-tw M sets | medium (likely official JP images via a third party, §5) | +0.5 day |
| **D. Korean names from pokemoncard.co.kr** | Crawl only card facts (set, number, name) from the official KO detail pages; no images | names for B | — | low for facts; crawl volume ~15–20k requests (**Annahme**) | ~1 day |
| **E. Official KO / TW images** | One-time fetch from pokemoncard.co.kr or asia.pokemon-card.com, embed, delete | all KO (watermarked) | ~14.6k | medium to high (terms forbid copying; same as JP option D) | ~1–2 days |
| **F. Simplified Chinese** | Mainland set list + numbers from PTCG-CHS-Datasets (or by hand), JA twin per card by artwork matching, own ids `CSV1C-001` | — | — | facts low; dataset terms forbid redistribution and non-research use; images only with Pokémon Shanghai's permission | several days, plus price/collection work for new ids |

## Decisions for Fabian

1. **Approve option B** for `zh-tw` and `ko`: JA image stands in where the language has none, the app shows the
   TCGdex image of that language or a placeholder (never the JA image, ADR 0009). Recommended.
2. **Korean names:** ship the `ko` pack with Japanese names for most cards (from TCGdex), or first crawl only the
   names from pokemoncard.co.kr (option D)? The terms are about "content"; names and numbers are facts, but the crawl
   is still automated use of the site.
3. **Limitless JP images (option C):** fine to use as a second fallback, even though they are most likely copies of the
   official JP database? This also affects #6 (JP). If yes, ask Limitless where the JP images come from first.
4. **Simplified Chinese:** keep `zh-cn` out for now (no official web database; the only full dataset forbids
   redistribution), or send Pokémon Shanghai a permission request like the JP draft? Recommended: keep it out and
   ask TCGdex (cards-database#958) to add the mainland sets.
5. **Official KO/TW images (option E):** same question as JP option D. Recommended: no, as long as B covers most
   cards; reconsider together with the TPC answer for Japan.
