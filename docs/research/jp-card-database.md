# Research: JP card images from the official database (pokemon-card.com)

Part of #6. Research date: 2026-11-17. **No card images were downloaded**; only the policy page, `robots.txt`,
one search-API response, one detail page, and `HEAD` requests to TCGdex's CDN were fetched. Not legal advice.
Items marked **Annahme** are unverified.

## Summary

- **The terms forbid what the crawl would do.** The site policy licenses all content "for personal enjoyment
  only" and prohibits copying, reproducing, publishing and use on other public networks. There is no `robots.txt`
  (404) and no API/scraping clause either way.
- **A cheaper first step exists:** TCGdex's CDN already holds image files for ~3.4k JP cards that its API leaves
  out (tcgdex/cards-database#2477). That alone could lift JP coverage from ~3.9k to ~7.3k with no new legal exposure.
- **The crawl is technically easy:** 604 search-result pages list every card with its image path; ~23.5k detail
  pages add collector numbers. Polite rate (1 req/s): ~10 min for the list, ~6.5 h for all details.
- **Decision for Fabian:** see [Decision needed](#decision-needed).

## 1. Terms of use and robots

Source: [サイトのご利用](https://www.pokemon-card.com/policy.html), operated by 株式会社ポケモン (read 2026-11-17).

| Clause (JP, abridged) | Translation / meaning |
| --- | --- |
| 著作権、または使用を許諾する権利は、株式会社ポケモンに帰属します。データは、個人的に楽しむ場合に限って使用を許諾される | Copyright (or the right to license use) belongs to The Pokémon Company. Data is licensed **for personal enjoyment only**; no copyright is transferred. |
| データのコピー、複製、改変、出版、掲示、電送、配布することは固くお断りします。またこれらのデータを、他のインターネットなどの公衆ネットワーク上で利用することはできません | Copying, reproduction, alteration, publication, posting, transmission, distribution are firmly refused; the data may not be used on other public networks such as the internet. |
| 著作物を複製、使用してのリンク設定はご遠慮いただきます。…必ずテキストリンクで | No links built from copied/used works (e.g. image links); text links only. |
| このサイトは日本国内専用サイトです | The site is for use within Japan only. (Not geo-blocked: requests from outside Japan returned 200.) |
| フッター: 掲載された画像その他の内容の無断転載はお断りします | Unauthorised reproduction of images and other content is refused. |

- **robots.txt:** `https://www.pokemon-card.com/robots.txt` → HTTP 404; `sitemap.xml` → 404. No machine-readable
  crawl rules or opt-out. The search page itself is served normally to a plain HTTP client.
- **No API/scraping statement** on the policy page. The JSON endpoint below is the site's own front-end endpoint,
  undocumented and unofficial.
- Not read: [pokemon.co.jp/rules](https://www.pokemon.co.jp/rules/) (group-wide rules) and the
  [Players Club terms](https://www.pokemon-card.com/event/players_policy/) — **Annahme:** same copyright wording.

## 2. How the card search exposes cards

| What | Finding |
| --- | --- |
| List endpoint | `GET /card-search/resultAPI.php?keyword=&regulation_sidebar_form=all&page=N` returns JSON: `hitCnt` **23530**, `maxPage` **604**, 39 cards/page, each `{cardID, cardThumbFile, cardNameAltText}`. Last page (604) returned 13 cards. |
| Image URL | In the list: `/assets/images/card_images/large/<SET>/<6-digit id>_<P/T/E>_<ROMAJI>.jpg`. Same file as the detail page's main image (the "large" variant; resolution not measured). |
| Set code | Present as the folder in the image path (`MG`, `M6a`, `ENE`). Page 1 mixes `MG` and `M6a`. **Annahme:** folder codes match TCGdex set IDs for modern sets, but old/promo folders will need a mapping table. |
| Collector number | **Not in the list.** Only in the detail page: `&nbsp;001&nbsp;/&nbsp;038` next to the regulation logo. Needs one request per card. |
| Detail page | `/card-search/details.php/card/<cardID>/regu/all` — HTML with name, number/printed total, illustrator, regulation mark, attacks, product link (`/products/mega/mg.html`). |

Examples:

- List: <https://www.pokemon-card.com/card-search/resultAPI.php?keyword=&regulation_sidebar_form=all&page=1>
- Detail: <https://www.pokemon-card.com/card-search/details.php/card/50813/regu/all> (ワニノコ, `MG`, 001/038)
- Image path from it: `/assets/images/card_images/large/MG/050813_P_WANINOKO.jpg`

**Request estimate (one-time, 1 req/s, own User-Agent, resumable):**

| Scope | Requests | Time at 1 req/s |
| --- | --- | --- |
| List only: IDs, names, set code, image URL | 604 | ~10 min |
| + detail pages for all cards (numbers, printed totals) | ~24,100 | ~6.7 h |
| + detail pages only for cards TCGdex lacks (~9k–13k) | ~10–14k | ~3–4 h |
| + image bytes (for local embedding only) | one per card | adds ~23.5k requests |

Note: the site lists **23.5k** entries vs ~13k JP cards in TCGdex (API: 13,156 cards, 3,882 with an image).
**Annahme:** the site counts reprints/variants as separate card IDs, so matching to TCGdex IDs by set + number is needed
and will leave unmatched rows.

## 3. Alternatives

| Source | What it gives | License / terms | Verdict |
| --- | --- | --- | --- |
| **TCGdex CDN gap** ([#2477](https://github.com/tcgdex/cards-database/issues/2477), open) | Files exist at `assets.tcgdex.net/ja/<serie>/<set>/<no>/high.webp` for 3,329 cards in 37 sets (SM, M4, S5I…SV5M) + 67 in partial sets, but `datas.json` has no entry. Verified: `ja/SM/SM12a/001` and `ja/M/M4/001` → HTTP 200; `datas.json` `.ja` has no `SM`/`M` series. M1S excluded (wrong cards from #57). | Code MIT; [FAQ](https://tcgdex.dev/faq) says nothing on image licensing or origin. Same copyright question as everywhere, but ADR 0009 already accepts TCGdex as source. | **Best first step.** Probe the CDN directly by path (HEAD) — lower bound ~3.4k. |
| TCGdex contributions | Images are added via Discord maintainers ([FAQ](https://tcgdex.dev/faq)); the proper fix for #2477 is regenerating `datas.json`. | — | Offer the HEAD-probe list upstream. |
| [type-null/PTCG-database](https://github.com/type-null/PTCG-database) | JP/EN card data (MIT) and a download script. | MIT covers the repo; **Annahme:** images it fetches are the same TPC images. | Metadata cross-check, not an image licence. |
| Limitless, pokemontcg.io, Scrydex | Mostly EN; Limitless has some JP images (per [tcgdex#2516](https://github.com/tcgdex/cards-database/issues/2516)). | Not read — **Annahme:** all re-host TPC art without a clear licence; Scrydex is a paid API. | Not pursued. |
| Other projects' practice | tierbinder removed its pokemon-card.com/Limitless scraping ([PR #34](https://github.com/alexhutton28/tierbinder/pull/34)); cardscanr-data plans a crawl as a *local archive only* ([#27](https://github.com/Motiv8ed/cardscanr-data/issues/27)). | — | Shows the same trade-off; local-only is the common compromise. |
| Opt-in user scans (ADR 0003/0008) | CC0 crops of real cards. | Explicit consent. | Long-term fix; slow start for rare JP cards. |
| Ask The Pokémon Company | Written permission for one-time embedding use. | Contact via the site's お問い合わせ form. | Cheap to try; **Annahme:** slow/unlikely, but removes the doubt. |

## 4. Legal framing (not legal advice)

| Question | Considerations |
| --- | --- |
| **Compute embeddings locally, don't publish images** | Japan: [Copyright Act Art. 30-4](https://www.bunka.go.jp/seisaku/bunkashingikai/chosakuken/pdf/94037901_01.pdf) (information analysis, not for "enjoyment") is the permissive basis; the Agency for Cultural Affairs paper (2024-03-15) says it fails if the use also has an enjoyment purpose and the proviso applies if it unreasonably harms the owner. An embedding for recognising a card is plausibly non-enjoyment — **Annahme**, untested in court. Separate from copyright, the site's terms are a *contract* offer ("personal enjoyment only"); whether a browse-wrap binds a foreign crawler is **Annahme**/unclear. |
| **EU/Germany (if the crawl runs from there)** | **Annahme:** EU TDM exception (DSM Art. 4, UrhG §44b) allows commercial text-and-data-mining unless the owner reserves rights in machine-readable form; here there is no robots.txt, but a natural-language "no copying" clause exists. Whether that is a valid reservation is disputed (LG Hamburg, LAION case, 2024 — not read). Needs a lawyer if the crawl goes ahead. |
| **Publish images** | Not allowed by the terms and out of scope (ADR 0008/0009). |
| **"Publish URLs, not images" (ADR 0008)** | Fits the *manifest* itself: URLs are facts, nothing is re-hosted, and a contributor runs the download script themselves. It does **not** make the crawl itself clean, and it needs care in two places: (1) the app must not hot-link pokemon-card.com images (the policy bars use "on other public networks" and image-based links) — keep showing TCGdex images (ADR 0009) or the user's own scan; (2) a published download script invites others to do the same crawl against the terms. |
| **Published embeddings/index** | ADR 0008 publishes the reference index. **Annahme:** vectors are not reproductions of the artwork, but this is the part most worth a lawyer's opinion because it is the artefact that leaves our machine. |

## Decision needed

1. **Crawl yes/no?** Recommended order: (a) recover the TCGdex CDN gap first (no new source, ~3.4k cards);
   (b) ask TPC for permission in parallel; (c) only then decide on the pokemon-card.com crawl.
2. **Scope, if yes:** *list-only* (604 requests: IDs, set code, image URL for the manifest, no image bytes)
   vs *gap-only images* fetched to a local, unpublished cache for embedding vs *everything* (~24k details + ~23.5k images).
   Recommended: list-only plus gap-only, images never published, never shown in the app.
3. **Rate, if yes:** ≤1 request/s, single connection, identifying but generic User-Agent, resumable with
   checkpoints, stop on any 403/429/5xx burst, one pass only (cache the JSON).
4. **Manifest:** record `source_url` per card but publish it only after (1)–(2) are settled; do not ship a crawler
   script in the repo until then.
