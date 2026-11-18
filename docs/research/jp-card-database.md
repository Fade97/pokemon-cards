# Research: JP card images from the official database (pokemon-card.com)

Part of #6. First pass 2026-11-17 (PR #38), second pass 2026-11-18 (this revision: steps (b) and (c)).
**No card images were downloaded.** Requests to pokemon-card.com in the second pass: the policy page, `robots.txt`,
`/contact`, 12 search-API pages and 4 detail pages, ≥2 s apart, generic browser User-Agent. Plus pokemon.co.jp
`/rules/` and `robots.txt` and the corporate licensing page. Not legal advice. Items marked **Annahme** are unverified.

## Summary

- **Terms:** both official JP sites (pokemon-card.com and pokemon.co.jp) license their content "for personal
  enjoyment only" and forbid copying, reproduction, publishing, transmission and use "on other public networks
  such as the internet". No `robots.txt` on pokemon-card.com, no API, no clause on automated access.
- **Coverage:** the official DB lists **23,530 card IDs** (DP era to today, with deck reprints and promos as separate
  IDs). TCGdex lists 13,156 JP cards, **7,359 with an image** since #47. The biggest gap that matters to users is
  the **current Mega (M) series: ~2,000 cards, only M4 has images**, plus 288 SV promos.
- **Mapping:** for main sets the image folder in the official DB equals the TCGdex set id (`SV6a`, `S6H`, `SM9`, `CP4`,
  `M6a`, `M1L`), and the number on the detail page equals TCGdex `localId` (3 of 3 spot checks matched by name).
  Promos, deck sets and the DP era need a mapping table or are not in TCGdex at all.
- **Recommendation:** send the inquiry to The Pokémon Company now (draft below), crawl nothing until it answers,
  and meanwhile stay with TCGdex + user scans. See [Decision needed](#decision-needed).

## 1. Terms of use, robots.txt, copyright notices

Retrieved 2026-11-18. Translations are mine, not official.

### pokemon-card.com — [サイトのご利用](https://www.pokemon-card.com/policy.html)

Operated by 株式会社ポケモン (The Pokémon Company, "TPC").

| # | Original (JP) | Translation |
| --- | --- | --- |
| P1 | このサイトに掲載されているデザイン、写真、映像、文章、音楽、音声など、すべてのコンテンツデータ（以下「データ」といいます）の著作権、または使用を許諾する権利は、株式会社ポケモン(以下「当社」といいます)に帰属します。 | Copyright in all content data on this site — designs, photos, video, text, music, audio etc. ("Data") — or the right to license its use belongs to The Pokémon Company ("we"). |
| P2 | データは、個人的に楽しむ場合に限って使用を許諾されるものであり、著作権が譲渡されるものではありません。 | Use of the Data is licensed **only for personal enjoyment**; no copyright is transferred. |
| P3 | このサイトからは、データのコピー、複製、改変、出版、掲示、電送、配布することは固くお断りします。またこれらのデータを、他のインターネットなどの公衆ネットワーク上で利用することはできません。 | Copying, reproducing, altering, publishing, posting, transmitting or distributing the Data from this site is **firmly refused**. The Data **may not be used on other public networks such as the internet**. |
| P4 | 著作権を侵害する内容やキャラクターのイメージを損なうサイトからのリンクは、お断りさせていただいています。当社サイトに掲載されている著作物を複製、使用してのリンク設定はご遠慮いただきます。リンク素材などの提供も行っておりませんので、必ずテキストリンクでお願いいたします。 | Links from infringing sites are refused. Please refrain from links that reproduce or use works on this site. No link material is provided; **text links only**. |
| P5 | 当社は現在、キャラクター、商品などに関する企画・アイデアなどについて、一切公募をしておりません。…万一、企画・アイデア・資料などをご提出いただいた場合は、それらに対する権利…は当社に譲渡されたものとみなされます | TPC does not accept ideas or proposals. **Anything submitted anyway is deemed assigned to TPC.** (Relevant for the inquiry: ask for permission, don't pitch the product.) |
| P6 | このサイトは日本国内専用サイトです。海外からアクセスする場合はご了承の上ご利用ください。 | The site is for Japan only; access from abroad is at your own discretion. (Not geo-blocked: all requests from outside Japan returned 200.) |
| P7 | Footer: このホームページに掲載されている内容の著作権は(株)クリーチャーズ、(株)ポケモンに帰属します。このホームページに掲載された画像その他の内容の無断転載はお断りします。 | Copyright in the content belongs to Creatures Inc. and TPC. **Unauthorised reproduction of the images** and other content is refused. |

- **robots.txt:** `https://www.pokemon-card.com/robots.txt` → HTTP 404 (also 404 on 2026-11-17). No crawl rules,
  no machine-readable opt-out, no sitemap.
- **No API or scraping clause.** `resultAPI.php` is the site's own undocumented front-end endpoint.
- **Contact:** `https://www.pokemon-card.com/contact` redirects to the card-game help centre
  `https://pcg-cs.pokemon-support.com/hc/ja` (Zendesk; answers 403 to scripts, so the form was not read).

### pokemon.co.jp — [ご利用について](https://www.pokemon.co.jp/rules/)

Covers the official Pokémon site and the corporate site. §1 著作権について repeats P1–P3 word for word
(「利用」 instead of 「使用」). §3 repeats P4. New compared with pokemon-card.com:

| # | Original (JP, abridged) | Translation |
| --- | --- | --- |
| R1 | ポケモン財産に基づく二次的な創作物（以下「二次コンテンツ」といいます）の創作者及び利用者…は、当社及び関係者に対して、二次コンテンツ…を、あらゆる目的及び形式で利用（…インデックス作成…を含みますがこれに限られません）するための、非独占的、サブライセンス可能、無償、無期限かつ取消不能な全世界を対象とするライセンスを許諾するものとします。 | Creators and users of **derivative content** based on Pokémon property grant TPC and partners a non-exclusive, sublicensable, free, perpetual, irrevocable worldwide licence to use it for any purpose (including **indexing**). |
| R2 | なお、当社及び関係者による二次コンテンツのいかなる利用も、お客様に対し、個人的な利用を超えたポケモン財産又は二次コンテンツの利用を許諾するものではありません。本規定については日本法を準拠法とし…東京地方裁判所を第一審の専属的合意管轄裁判所とします。 | TPC's use of derivative content **never grants you use of Pokémon property beyond personal use**. Japanese law; Tokyo District Court has exclusive first-instance jurisdiction. |

- **robots.txt** (`https://www.pokemon.co.jp/robots.txt`): disallows `/images/`, `/img/`, `/common/`, `/corporate/`
  and some API paths for all agents. Not relevant to pokemon-card.com, but it shows TPC does use robots.txt on
  other sites — the absence on pokemon-card.com is an omission, not an invitation (**Annahme**).
- **Contact:** form `https://support.pokemon.jp/form_app/inquiry/pokemon/`; the corporate
  [licensing page](https://corporate.pokemon.co.jp/business/licences/) has no form of its own and links to the
  general support centre `https://pokemon-support-top.pokemon-support.com/hc/ja`.

### What the terms mean for each use (not legal advice)

| Use | Terms | Copyright (JP Art. 30-4 / DE §44b) | Assessment |
| --- | --- | --- | --- |
| **(i) Store URLs + card ids/numbers in a manifest** | URLs, ids, set codes and numbers are facts, not "Data" in the sense of P1. Getting them needs 604 list requests (plus ~24k detail requests for numbers). P4 bars image-based links, not a URL in a data file. | Facts are not protected. | **Low risk** for the manifest itself. The crawl to build it is automated use of the site beyond "personal enjoyment" (P2) — **Annahme:** a browse-wrap clause is hard to enforce against a crawler that never accepted it, but it is still against the stated wish. |
| **(ii) Each user's app fetches images from pokemon-card.com** | Conflicts with P3 ("may not be used on other public networks") and P4 (no image-based links); the app would hot-link TPC's server. Also the source's paths are undocumented and can change. | Each user makes a private copy; JP Art. 30 (private use) may cover the copy, **Annahme**. | **High risk, low value.** Visible to TPC in their server logs, against two clauses, brittle. Not recommended in any option. |
| **(iii) Fetch once on Vulcan, compute embeddings, ship only embeddings** | Downloading is "copying/reproducing" (P3). The images never leave Vulcan, nothing is published. | JP: Art. 30-4 allows reproduction for information analysis that is not for "enjoyment", unless it unreasonably harms the owner ([Agency for Cultural Affairs, 2024-03-15](https://www.bunka.go.jp/seisaku/bunkashingikai/chosakuken/pdf/94037901_01.pdf)). Embedding for card recognition is plausibly non-enjoyment — **Annahme**. Whether a contract (P2/P3) can override Art. 30-4 is debated in Japan — **Annahme**. DE/EU: §44b UrhG allows TDM unless the rightholder reserved it "in machine-readable form"; whether a natural-language clause like P3 counts is disputed (LG Hamburg, LAION, 2024-09-27 leaned towards yes) — **Annahme**, not read in full. | **Medium risk.** Best legal footing of the crawl options, but it is the use the terms forbid most directly (P3). Shipped embeddings are not reproductions of the art (**Annahme**), yet they are the artefact that leaves our machine (ADR 0008). Worth a lawyer's view before doing it without permission. |
| **(iv) Re-host images (R2, dataset dump, APK)** | Prohibited (P3, P7). | No exception applies. | **Out.** Already excluded by ADR 0008/0009. |

Same caveat as everywhere: TCGdex images are the same TPC artwork; ADR 0009 accepts TCGdex as source, so the
crawl adds a *contractual* risk (TPC's own terms), not a new kind of copyright risk.

## 2. Coverage and mapping

### Official DB

`GET /card-search/resultAPI.php?keyword=&regulation_sidebar_form=all&page=N` → JSON with `hitCnt` **23,530**,
`maxPage` **604**, 39 cards per page, each `{cardID, cardThumbFile, cardNameAltText, cardNameViewText}`.
Unchanged since 2026-11-17. Set codes seen on 12 probed pages (page → image folders):

| Page | Folders | Era |
| --- | --- | --- |
| 1 | MG, M6a | Mega (current) |
| 50 | M1L, MBG, MBD, ENE | Mega + decks |
| 100 | SV6a | Scarlet & Violet |
| 175 | S12a | Sword & Shield |
| 250 | S6H | Sword & Shield |
| 325 | SM9, SMJ | Sun & Moon |
| 400 | CP4 (+1 `legend/` folder) | XY |
| 475, 525 | BW7-B, DPt1-B, L2-B, WCP, SMP, SV-P, XYP, M-P … | decks, promos, mixed |
| 550 | DP1–DP5 | Diamond & Pearl |
| 590, 604 | SMP, SV-P, XYP, S-P, ENE | promos, basic energy |

So the DB covers **DP (2006) to today**. Pre-DP cards (PMCG, neo, e, ADV, PCG — in TCGdex as card lists) are not in it.
The 23.5k includes every deck reprint (`*-B`, `MBG`, `SMJ`) as its own ID; **Annahme:** ~15–17k distinct printings.

### TCGdex JP (local pack-build cache, 2026-11-18, incl. the CDN gap from #47)

| Series | Sets | Cards listed | With image | Official DB |
| --- | --- | --- | --- | --- |
| PMCG, neo, VS, web, e, ADV, PCG | 37 | 2,184 | 0 | not covered (pre-DP) |
| DP / DPt | — | not in TCGdex | — | covered |
| L (LEGEND) | 5 | 0 | 0 | covered |
| BW | 17 | 58 | 0 | covered |
| XY, XYb | 21 | 61 | 0 | covered (e.g. CP4: TCGdex has the set, 0 of 131 cards) |
| SM | 42 | 3,139 | 2,396 | covered |
| S | 45 | 1,887 | 1,801 | covered |
| SV (incl. SV-P 288/0) | 30 | 3,678 | 3,042 | covered |
| **M (current)** | 12 | 2,149 | **120** (M4 only) | covered |
| **Total** | 204 | **13,156** | **7,359** | 23,530 IDs |

What a crawl could add:

- **Images for cards TCGdex already lists:** 13,156 − 7,359 − 2,184 (pre-DP) ≈ **3,600 cards**: M ~2,030, SM ~740,
  SV ~640 (288 promos), S ~90, BW/XY ~120. → JP with an image from ~7.4k to **~11k**.
- **Whole eras TCGdex has no card list for** (DP, DPt, LEGEND, BW, XY): **Annahme** ~5–6k more printings. These need
  metadata from the detail pages as well (name, number), i.e. new catalogue entries, not just images.

### Mapping DB entries to TCGdex ids

| Check | Official DB | TCGdex | Match |
| --- | --- | --- | --- |
| card 45903 | folder `SV6a`, detail `028 / 064`, ゴルバット | `SV6a` `028` ゴルバット (has image) | yes |
| card 39396 | `S6H`, `036 / 070`, カゲボウズ | `S6H` `036` カゲボウズ (no image) | yes — fills a gap |
| card 31958 | `CP4`, `093 / 131`, ガルーラEX | `CP4` exists, card list empty | set only |
| card 17127 | `DP5`, no number on the page | no `DP5` | no |

- **Main sets: stable.** Folder code = TCGdex set id, detail number = `localId` (zero-padded 3 digits). Join on
  `(set, localId)` and check the name.
- **Needs a table:** promos (`SMP` ↔ TCGdex `SMP2`, `S-P` missing in TCGdex, `XYP` missing), deck reprints
  (`*-B`, `MBG`, `MBD`, `SMJ` → map to the original set card by name + artwork, or treat as separate printings),
  `ENE` basic energy, `legend/` folder.
- **No numbers** for DP-era cards on the detail page (JP cards then had no collector number printed) → name +
  set only, which is ambiguous for alternate arts.
- **Number is only on the detail page:** a full mapping costs one request per card (~24k, ~6.7 h at 1 req/s);
  for the ~3.6k gap cards in sets TCGdex lists, ~4k requests (~1.1 h).

## 3. Inquiry to The Pokémon Company (draft — not sent)

**Where:** card-game help centre via `https://www.pokemon-card.com/contact` (→ `pcg-cs.pokemon-support.com`), or the
general form `https://support.pokemon.jp/form_app/inquiry/pokemon/`. Send the JP version; add the EN version below
it. Keep it to a permission request — per P5, product ideas sent to TPC count as assigned to them.
Placeholders in `[…]`; fill in name and contact address before sending.

### 日本語

> 件名：カード検索の画像を画像認識の学習・照合に利用することについてのお問い合わせ
>
> 株式会社ポケモン ご担当者様
>
> はじめてご連絡いたします。ドイツで個人開発をしております[氏名]と申します。
>
> 現在、ポケモンカードゲームのコレクションを管理するための非公式・非営利の Android アプリを開発しております。
> スマートフォンのカメラでカードを撮影すると、端末内だけで（画像を外部に送信せずに）カードを判別する仕組みです。
> アプリは株式会社ポケモンとは関係がない旨を明記し、「ポケモン」の名称やロゴは使用しておりません。
>
> 日本語版カードの判別精度を上げるため、ポケモンカードゲーム公式ホームページ「カード検索」に掲載されている
> カード画像を、以下の条件で利用させていただくことは可能でしょうか。
>
> 1. カード画像を一度だけ取得し（1秒に1件以下）、カードを判別するための数値データ（特徴量ベクトル）を計算する目的にのみ使用します。
> 2. 画像そのものは公開・再配布・アプリへの同梱・アプリでの表示をいたしません。計算後は削除いたします。
> 3. 公開するのは、画像から復元できない数値データと、カード番号・カード名などの情報のみです。
>
> サイトのご利用規約で「個人的に楽しむ場合に限って」と定められていることは承知しております。
> そのため、ご許可をいただけない場合は利用いたしません。
> また、上記とは別の方法（例えば公式に提供されているデータの利用条件など）がございましたら、ご教示いただけますと幸いです。
>
> お忙しいところ恐れ入りますが、ご確認のほどよろしくお願い申し上げます。
>
> [氏名]
> [メールアドレス]

### English

> Subject: Permission request — using card-search images for on-device card recognition
>
> Dear Sir or Madam,
>
> I am an independent developer based in Germany. I am building an unofficial, non-commercial Android app for
> managing Pokémon Trading Card Game collections. The app recognises a card from a phone-camera photo entirely on the
> device; no image leaves the phone. The app states that it is not affiliated with The Pokémon Company and does not
> use the Pokémon name or logo.
>
> To recognise Japanese cards reliably, may I use the card images in the card search of the official Pokémon Card
> Game website under the following conditions?
>
> 1. The images are retrieved once (at most one request per second) and used only to compute numerical feature
>    vectors for recognising cards.
> 2. The images themselves are not published, redistributed, bundled with or shown in the app, and are deleted
>    after processing.
> 3. Only the numerical data, from which the images cannot be reconstructed, and card facts such as set code,
>    number and name are published.
>
> I understand that the site's terms allow use "for personal enjoyment only", so I will not use the images without
> your permission. If there is another way, for example officially provided data with terms of use, I would be
> grateful for a pointer.
>
> Thank you for your time.
>
> [Name]
> [E-mail]

Two claims in the draft need to hold before sending: "non-commercial" (ADR 0007 plans a Pro tier — reword to
"free app" if Pro is still planned) and "cannot be reconstructed" (true for a 256-d retrieval embedding in
practice, **Annahme** — soften to "not intended to reconstruct the images" if unsure).

Earlier alternatives (TCGdex CDN gap — done in #47 —, type-null/PTCG-database, other projects' practice) are in the
first revision of this file (PR #38).

## 4. Options and their effect on JP recognition

Baseline today: 7,359 JP cards with a reference image (56 % of TCGdex's JP list; ~6 % of the current M series).

| Option | What happens | JP cards with reference image | Risk | Effort |
| --- | --- | --- | --- | --- |
| **A. Stay with TCGdex + user scans** | No change. Ask TCGdex upstream for M-series images; route opt-in scans to JP gap cards first. | 7,359 now; grows with TCGdex and contributions (**Annahme:** slow for M) | none new | low |
| **B. Ask TPC first** (recommended) | Send the inquiry; nothing else touches pokemon-card.com until it answers. A + B run together. | as A; if TPC says yes → as D without the contractual risk | none | one e-mail; wait (**Annahme:** weeks, likely no answer or a no) |
| **C. Crawl URLs only** | 604 list requests (+ ~4k detail requests for numbers of gap cards); manifest gets `source_url`, ids, set, number. No image bytes. | **no change** — only prepares D and shows the exact gap | low (i) | ~1–2 h incl. mapping |
| **D. One-time gap crawl, embeddings only** | C + download ~3.6k gap images once on Vulcan (~1 h at 1 req/s, deleted after embedding), ship only embeddings. | **~11k** (+~3.6k, mostly current M series) | medium (iii) | ~1 day incl. mapping table |
| **E. Full crawl incl. DP–XY eras** | D + new catalogue entries from ~24k detail pages | ~16–17k (**Annahme**) | medium (iii), larger | several days, needs catalogue work (prices, sets) |
| F. App fetches images itself | Hot-links pokemon-card.com per user | as D | high (ii) | — not recommended |

## Decision needed

**Approve option B (+A meanwhile): send the inquiry in §3 to TPC, crawl nothing until TPC answers.**
If TPC says yes → run D. If no answer in ~6 weeks or a no → stay with A; decide then whether D is worth a lawyer's
check.

Two small choices for the inquiry: sender name/address, and "non-commercial" vs "free" (depends on the Pro tier).
