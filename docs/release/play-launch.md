# Play launch: name, package, listing, closed test

Issue #15, ADR 0009. Research done on 2026-11-18. **This is not legal advice.** The register searches below are a
first screen to drop obvious conflicts. They are not a clearance search. Before filing a trademark or spending
money on the brand, get a professional search.

Decisions for Fabian are marked **Decision**. Anything not verified is marked **Assumption**.

## 1. Name

### Status quo: "Card Index" has a conflict

The current working name and `app_name` is **Card Index**. It collides in the same field:

- USPTO application **CARD INDEX**, class 9, filed by Consultech Ventures LLC
  ([TMview US500000050041255](https://www.tmdn.org/tmview/#/tmview/detail/US500000050041255)).
- Google Play app with the package `com.consultech.cardindex`, the same applicant.

So the name has to change before the listing, and the package must not contain `cardindex` (section 2).

### How the names were checked

| Source | What was searched | How |
|---|---|---|
| TMview (covers EUIPO `EM`, DPMA `DE`, WIPO `WO`, USPTO `US`) | Each name, mode "contains" and mode "fuzzy". Only filed and registered marks. Hits outside classes 9, 16, 28, 35, 41, 42 were ignored. | [tmdn.org/tmview](https://www.tmdn.org/tmview/), search API, 2026-11-18 |
| Google Play | Search `q=<name>`, apps, `hl=en`, `gl=DE`, first 8 results | `https://play.google.com/store/search?q=<name>&c=apps` |
| Web | Exact-phrase web search for the leading candidates | Web search, 2026-11-18 |
| Domains | `.com` (Verisign RDAP), `.app` (Google Registry RDAP), `.de` (DENIC whois) | 200 / `connect` = registered, 404 / `free` = available |

**Assumptions:** DPMA and USPTO were searched through TMview, not through DPMAregister or the USPTO search
directly. TMview data can lag the offices by days to weeks, so very recent filings may be missing. Common-law use
(unregistered names) was only screened by web and Play search. Apple's App Store was not searched systematically.

### Shortlist (no clear conflict found)

| Name | Trademarks (classes 9/41/42) | Google Play | Web | .com / .app / .de | DE + EN |
|---|---|---|---|---|---|
| **Binderkeep** | No exact hit. Fuzzy: KINDERKEEP (US, 9/42, filed), not close in meaning. | No app with this name | Nothing found | taken / taken / **free** | "Binder" is used in both languages; "keep" is plain English |
| **Lumifoil** | No hit in the relevant classes (2 "contains" hits in other classes) | No app with this name | Only a 2016 architecture project | taken / **free** / **free** | Coined; "Folie" makes "foil" clear in DE |
| **Prismbook** | No hit, exact or fuzzy | Only "BOOK PRISM" and "PRISM – Book Summaries" (books) | A small book publisher ("Prism Book Group") | taken / **free** / **free** | Coined, easy in both |
| Kartothek | No exact hit. Fuzzy: KARTOTEK (EM, classes 16/18/24, no overlap). | No app with this name | Two unrelated GitHub projects | **free** / **free** / taken | German word for "card index"; reads as foreign in EN |
| Sleevebox | No exact hit. Fuzzy: SLEEVELOX (EM/US, 17/35/39/42). | No app with this name, but "Sleeve – TCG Card Tracker" exists | **SLEEVE** is a mark of Metaforge Apps Inc. for trading-card apps ([Justia](https://trademarks.justia.com/owners/metaforge-apps-inc-3825569)) | taken / taken / free | Good, but see the risk |

Notes:

- **Kartothek** is the descriptive German word for a card index, so it is weak as a trademark in DE (likely
  refused as descriptive for this kind of software). **Assumption.** It is also close in meaning to
  **KARDEX** (Kardex Holding AG, registered in classes 9/42 in EM, DE, WO and US), and several TCG apps on Play
  already call themselves "Kardex". The real advantage is that `kartothek.com` and `.app` are free.
- **Sleevebox** sits next to the SLEEVE mark and the "Sleeve" TCG app in exactly this market. Keep it only as a
  fallback.
- **Prismbook**: "Prism Star" is a Pokémon TCG card mechanic. "Prism" is a common word, but some players will read it
  as a nod to Pokémon. Low risk, worth knowing.
- Registered `.com` domains with no site may be parked and could be for sale. Not checked.

### Dropped (conflict found)

| Name | Conflict |
|---|---|
| Card Index | US CARD INDEX, class 9 (filed) + same-name Play app `com.consultech.cardindex` |
| Foilfolio | foilfolio.com is a Disney Lorcana collection tracker (same field) |
| Holobinder | holobinder.com is a Pokémon card collection app |
| Mintbinder | mintbinder.co.uk is a Pokémon card collection app |
| Holofolio | US HOLOFOLIO, class 9 (filed); Play has "Holofoil", "HOLO", "HoloDex" TCG scanners |
| Cardfolio | US CARD-FOLIO, classes 9/42 (filed) |
| Cardkeep | CARDKEEP EM/WO, class 9 (registered); several "CardKeep" apps on Play |
| Cardnest | Play: "CardNest: CardScan & Organizer" and more |
| Nine Pocket | Play: "Nine Pocket MTG Binder & Decks", "9Pocket TCG" |
| Binderly | Play: "Binderlyst: MTG Card Scanner"; US/WO BINDERY, class 42 |
| Binderbook | US IBINDERBOOK, class 42 |
| Mintfolio | Play: "The Mint Folio"; EM MINDFOLIO, classes 9/42 |
| Setmark | Play: "Setmark", "SetMark"; US SETMARK |
| Setkeeper | WO SETKEEPER, classes 9/41/42 |
| Scanfolio | Two iOS scanner apps named "ScanFolio" |
| Pagefold | Play: "Pagefold: PDF Scanner & Maker" |
| Pagekeep | Play: "Pagekeep: PDF Scanner & OCR" |
| Pocketkeep | Play: "Pocket Keep", "PocketKeep" |
| Foliant | Play: "Foliant: PDF Editor & Scanner"; WO FOLIANT |
| Foilwise | Play: "Foilwise" (another app with the same name) |
| Stacklet | US STACKLET, classes 9/42 (registered) |
| Prismfolio | US PRISMFOLIO, classes 9/36/42 (filed) |
| Sleevio, Sleevekeep | Close to SLEEPIO/SLEVIO (class 9) or the SLEEVE mark |
| Kardex | Registered mark of Kardex Holding AG |

Avoided on purpose: anything with "Poké", "-mon", "-dex" (reads as Pokédex), "Pika", Pokéball shapes, red/white
split circles.

### Recommendation: top 3

1. **Binderkeep**: clean in every check, says what the app does in both languages, `.de` free.
2. **Lumifoil**: clean, distinctive coined name, `.app` and `.de` free.
3. **Prismbook**: clean, `.app` and `.de` free; mind the "Prism Star" association.

Kartothek is the alternative if the `.com` matters more than trademark strength.

**Decided (2026-11-18): Lumifoil.** After that, before the listing: (a) register the domain(s), (b) optionally file a
DE or EU trademark in classes 9 and 42 (DPMA online filing for up to three classes costs about EUR 290;
**Assumption**, check the current fee list), (c) apply section 2.

## 2. applicationId

**Recommendation:** `de.fabianduerkop.<name>` (lowercase, no dashes), for example `de.fabianduerkop.binderkeep`.

- It follows the existing convention (Meterkat is `de.fabianduerkop.meterkat`) and relies only on a namespace
  Fabian already controls, not on buying a product domain.
- It cannot change after the first upload to Play, and Play Console asks for it when the app is created, before
  any upload (Meterkat experience, 2026-11-07). So it has to be fixed before step 1 of section 4.
- The Kotlin `namespace` stays `de.fabianduerkop.cardindex`, as the council review said: no package renaming,
  no churn in imports, tests or `BuildConfig`. Debug builds keep the `.debug` suffix.
- No code hard-codes the package (checked: no `FileProvider` authorities, no literal package strings outside
  `build.gradle.kts`).

**Decision:** confirm `de.fabianduerkop.<name>` or pick a domain-based id (`app.<name>`, only if that domain is
registered first).

Diff to apply once the name is chosen (replace `binderkeep` / `Binderkeep`):

```diff
--- a/android/app/build.gradle.kts
+++ b/android/app/build.gradle.kts
@@ -8,11 +8,13 @@ plugins {
 
 android {
+    // Kotlin package. Deliberately separate from the applicationId and never renamed (#15).
     namespace = "de.fabianduerkop.cardindex"
     // Platform android-37.0: AGP 9 addresses Android 17 by release and minor API level.
     compileSdk { version = release(37) { minorApiLevel = 0 } }
 
     defaultConfig {
-        applicationId = "de.fabianduerkop.cardindex"
+        // Play package (#15). It can never change after the first upload to Play.
+        applicationId = "de.fabianduerkop.binderkeep"
         minSdk = 26
         targetSdk = 36
         versionCode = 1
--- a/android/app/src/main/res/values/strings.xml
+++ b/android/app/src/main/res/values/strings.xml
@@ -1,6 +1,6 @@
 <?xml version="1.0" encoding="utf-8"?>
 <resources>
-    <string name="app_name">Card Index</string>
+    <string name="app_name">Binderkeep</string>
 
     <string name="nav_scan">Scan</string>
     <string name="nav_collections">Collections</string>
```

Follow-ups in the same PR:

- `android/README.md`: the `adb` commands use the installed package (lines 3–4, 32, 41, 134, 157). Replace
  `de.fabianduerkop.cardindex.debug/` and `de.fabianduerkop.cardindex/` in front of the activity with the new id;
  the class part after `/` (`de.fabianduerkop.cardindex.MainActivity`, the test runner class) stays.
- `README.md` title and `docs/DESIGN.md` ("working title").
- Leave internal identifiers alone: the backup format `cardindex-backup`, the Gradle property
  `cardindex.apiBaseUrl`, `CARDINDEX_DATA`, the Cloudflare resource names. Renaming them gains nothing and the
  backup format is user data.
- The API stays on `cards-api.fdu.gg` until the product domain exists; moving it is a separate change (app
  `API_BASE_URL` + Worker route), not tied to the package.

## 3. Play listing draft

Placeholders: `<Name>` is the chosen name. The app UI is English only for now (`res/values` has no `values-de`),
so the DE listing describes an English app. **Decision:** ship DE store text now, or only once the app has a
German translation.

### Short description (max. 80 characters)

| | Text | Length |
|---|---|---|
| EN | Scan Pokémon TCG cards offline, track your collection and Cardmarket prices. | 76 |
| DE | Pokémon-TCG-Karten offline scannen, Sammlung und Cardmarket-Preise im Blick. | 76 |

Fallback without the trademark (if a reviewer objects): "Scan TCG cards offline, sort them into binders and see
Cardmarket prices." (73) / "Sammelkarten offline scannen, in Ordner sortieren, Cardmarket-Preise sehen." (75).

### Full description (EN)

```
<Name> is a free, open-source collection app for Pokémon TCG cards.

SCAN ON YOUR PHONE
Point the camera at a card. <Name> recognizes it on the phone itself, with no photo sent anywhere. It reads the
collector number to tell similar cards apart, and works offline once your language packs are installed.

MANY LANGUAGES
English, German, French, Italian, Spanish, Portuguese, Dutch, Polish and Japanese cards. Download only the
languages you collect; new sets arrive as small updates.

YOUR COLLECTION
• All your cards in one place, with quantity, finish, edition, condition or grade, and purchase price
• Set collections are created automatically and show how complete each set is
• Your own collections for binders, trade piles and wishlists
• Unsure scans wait in a review queue with the top three matches

PRICES
Current Cardmarket (EUR) and TCGplayer (USD) prices, updated daily, with a link to the card on the marketplace.
Cardmarket prices mix all card languages, and the app says so.

PRIVATE BY DESIGN
No account, no ads, no tracking. Your collection stays on your phone and in your own Android backup. Free
JSON/CSV export. If you want to help improve recognition, you can choose to share cropped card scans as public
domain (CC0); that is off by default and can be withdrawn at any time.

OPEN SOURCE
The app is licensed under GPLv3, the server under AGPLv3. Source code: https://github.com/Fade97/pokemon-cards

<Name> is an unofficial fan-made app. It is not affiliated with, endorsed, sponsored or approved by Nintendo,
Creatures Inc., GAME FREAK inc. or The Pokémon Company. Pokémon and all related names are trademarks of their
respective owners. Card images are provided by TCGdex. Cardmarket and TCGplayer are trademarks of their owners;
<Name> is not affiliated with them.
```

### Full description (DE)

```
<Name> ist eine kostenlose Open-Source-App für deine Pokémon-TCG-Sammlung.

SCANNEN AUF DEM HANDY
Halte die Kamera auf eine Karte. <Name> erkennt sie direkt auf dem Handy, ohne dass ein Foto das Gerät verlässt.
Die App liest die Kartennummer, um ähnliche Karten zu unterscheiden, und funktioniert offline, sobald die
Sprachpakete installiert sind.

VIELE SPRACHEN
Englische, deutsche, französische, italienische, spanische, portugiesische, niederländische, polnische und
japanische Karten. Lade nur die Sprachen, die du sammelst; neue Sets kommen als kleine Updates.

DEINE SAMMLUNG
• Alle Karten an einem Ort, mit Anzahl, Ausführung, Edition, Zustand oder Grading und Kaufpreis
• Set-Sammlungen entstehen automatisch und zeigen, wie vollständig jedes Set ist
• Eigene Sammlungen für Ordner, Tauschstapel und Wunschlisten
• Unsichere Scans landen in einer Prüfliste mit den drei besten Treffern

PREISE
Aktuelle Cardmarket- (EUR) und TCGplayer-Preise (USD), täglich aktualisiert, mit Link zur Karte auf dem Marktplatz.
Cardmarket-Preise gelten für alle Kartensprachen zusammen, und die App weist darauf hin.

PRIVATSPHÄRE
Kein Konto, keine Werbung, kein Tracking. Deine Sammlung bleibt auf deinem Handy und in deinem eigenen
Android-Backup. Kostenloser Export als JSON/CSV. Wer die Erkennung verbessern möchte, kann zugeschnittene
Kartenscans als gemeinfrei (CC0) teilen; das ist standardmäßig aus und jederzeit widerrufbar.

OPEN SOURCE
Die App steht unter GPLv3, der Server unter AGPLv3. Quellcode: https://github.com/Fade97/pokemon-cards

<Name> ist eine inoffizielle Fan-App. Sie steht in keiner Verbindung zu Nintendo, Creatures Inc., GAME FREAK inc.
oder The Pokémon Company und wird von diesen weder unterstützt noch gesponsert oder genehmigt. Pokémon und alle
zugehörigen Namen sind Marken ihrer jeweiligen Inhaber. Kartenbilder stammen von TCGdex. Cardmarket und TCGplayer
sind Marken ihrer Inhaber; <Name> steht in keiner Verbindung zu ihnen.
```

Keep the in-app "About" screen in line with this disclaimer (README already carries it).

### Category and tags

- **Category: Tools.** Pokellector, HoloDex and Shiny (Pokémon card scanners) are all listed under Tools
  (Play pages, 2026-11-18). BinderWiz uses Entertainment.
- Tags are picked from Play's fixed list in the Console; choose collection/organizer-like tags if offered.

### Content rating (IARC questionnaire)

Category: **All other app types** (a utility, not a game, not social).

| Question | Answer |
|---|---|
| Violence, blood, fear | No |
| Sexuality, nudity | No |
| Crude language | No |
| Controlled substances (drugs, alcohol, tobacco) | No |
| Gambling, simulated gambling | No. Showing card prices is not gambling; no loot boxes, no paid packs. |
| Users interact or exchange content with each other | No. Contributed scans go to a public dataset, not to other users in the app. |
| Shares the user's location with others | No |
| Digital purchases | No for the MVP. Yes once Pro (#16) ships; update the questionnaire then. |
| Unrestricted internet / web browser | No (links open in the external browser) |

Expected result: PEGI 3, USK 0, ESRB Everyone. **Assumption** until the questionnaire returns.

### Target audience

**Decision:** Pokémon cards appeal to children. Choosing an age group under 13 brings the app under the Families
policy (SDK and data rules). Recommendation: target **13+** (or 16+) and answer the "appeals to children" question
honestly; Play may still ask for changes. **Assumption:** whether ML Kit and LiteRT meet the Families SDK
requirements was not checked.

### Data safety form

Based on the code on `main` (2026-11-18): `AndroidManifest.xml` asks only for `CAMERA` and `INTERNET`. The app
talks to the own backend (`cards-api.fdu.gg`: prices, language packs, contributions) and to the TCGdex CDN
(`assets.tcgdex.net`, card images). There is no analytics, crash reporting, ads SDK, Firebase or account.

| Data | Collected? | Why |
|---|---|---|
| **Photos** (Photos and videos) | **Yes, optional** | Only with the opt-in contribution: the rectified card crop of a confirmed scan, metadata stripped on the phone and again on the server (`ContributionApi.kt`, `backend/src/contributions.ts`). Sent with the card id, language and consent version. |
| **Device or other IDs** | **Yes** (by the ML Kit SDK) | ML Kit text recognition (bundled) collects per-installation identifiers for diagnostics and usage analytics ([ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)). |
| **App info and performance → Diagnostics** | **Yes** (by the ML Kit SDK) | Same source: latency, API configuration, error codes. |
| Collection, prices fetched | No | The collection stays on the phone. Price requests send a list of card ids, processed in memory and not stored (ephemeral). |
| Card images from TCGdex | No | The app loads public image URLs; no user data in the request. |
| IP address | No data type | Play has none for IP. Mention Cloudflare (`observability` is on in `wrangler.jsonc`) and TCGdex in the privacy policy. |
| Android Backup | No | Google's backup, not the app's. |
| Android vitals | No | Collected by Google Play, not by the app. |

Per data type:

| Field | Photos | Device or other IDs | Diagnostics |
|---|---|---|---|
| Purpose | App functionality (improving recognition) | Analytics | Analytics |
| Required or optional | Optional | Required | Required |
| Ephemeral | No | No | No |
| Shared with third parties | **Decision** (see below) | No | No |

General answers:

- **Encrypted in transit:** Yes (all endpoints are HTTPS; ML Kit uses HTTPS).
- **Users can request deletion:** Yes. Contributions can be withdrawn one by one or all at once in the app
  (*My contributions*), which deletes them from the server.
- **Account creation:** None, so no account-deletion URL yet. Pro (#16) adds Google Sign-In and changes this.
- **Independent security review:** No.

**Decision (Photos, "shared"):** contributed crops are published in the open CC0 dataset (#20, ADR 0008). Play
exempts transfers "based on a specific user-initiated action where the user reasonably expects the data to be
shared"; the explicit CC0 consent screen arguably fits that. The conservative choice is to declare Photos as
**shared** (purpose App functionality). Recommendation: declare it as shared; it costs nothing in the listing and
removes the argument.

**Gaps before the form can be submitted:**

- A **privacy policy URL** is required. PR #75 (#70) adds it at `/privacy` on the Worker (DE/EN); its placeholders
  must be filled before it is deployed and entered in the Console. It must cover: no account, local-only collection,
  the opt-in contribution (what, CC0, withdrawal, retention), ML Kit telemetry, Cloudflare (IP in request logs,
  `observability` retention) and TCGdex image requests. An Impressum (§ 5 DDG) is needed at the latest when Pro
  makes it a commercial offer. Host both on the product domain or GitHub Pages.
- Store **contact email** (public).

### Graphics and screenshots

Required by Play: app icon 512 × 512 PNG; feature graphic 1024 × 500; at least 2 phone screenshots (up to 8,
9:16, 1080 × 1920 or more is the practical size). Make each set in EN and DE.

1. Live scan: a card in the frame with the corner overlay and the recognized card.
2. Review queue: top-3 picker with variant chips.
3. Set collection: completion (e.g. 87/165) with the language filter.
4. Card detail: Cardmarket EUR and TCGplayer USD prices, mixed-language label.
5. Custom collections (binder, trade pile, wishlist).
6. Language packs: first-run picker, offline after download.
7. Privacy: export/backup and the contribution switch (off by default).
8. Optional: dark mode.

**Decision:** card art in screenshots is TPC's copyright, and the store listing is marketing, not app use.
Recommendation: show the UI with small thumbnails, no full-screen card art, no Pokémon logo; for the feature
graphic use own artwork (no card art). No Pokéball shapes in the icon (ADR 0009).

## 4. Closed test plan

Rules for personal accounts created after 2023-11-13 ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465),
read 2026-11-18): at least **12 testers opted in continuously for the 14 days** right before applying for
production. Testers who leave and rejoin start over. After the test: Dashboard → *Apply for production*
(questions about recruiting, feedback, changes, readiness); review takes up to about 7 days.

### Only Fabian can do this

- [ ] Create the personal developer account: legal name, address, phone, email; pay the USD 25 fee.
- [ ] Identity verification (government ID) and phone/email verification; verify an Android device with the
      Play Console app. Allow a few days.
- [ ] Decide the EU **trader status** (DSA). Free app without Pro: usually "not a trader". With Pro (#16) it
      becomes a trader, and address and phone become public in the listing.
- [ ] Payments profile, only when Pro / Play Billing comes.
- [x] Choose the name (section 1): Lumifoil. applicationId `de.fabianduerkop.lumifoil` (section 2) is applied.
- [ ] Register `lumifoil.de` / `lumifoil.app`.
- [ ] Recruit testers and approve the Google Group.
- [ ] Sign the declarations in the Console (content rating, data safety, target audience, ads: no ads).

### Prepared by agents (follow-up issues)

- [x] Apply the section 2 diff.
- [ ] signed release AAB with Play App Signing (upload key outside the repo,
      signing config only from env or a git-ignored file). Install the AAB via bundletool and smoke-test R8 builds on the S25.
- [ ] Privacy policy (EN/DE, PR #75) deployed with the placeholders filled; Impressum if needed.
- [ ] In-app "About" with the disclaimer, licenses and the privacy link.
- [ ] Screenshots and feature graphic (section 3).
- [ ] A feedback channel for testers: a GitHub issue template or a short form, linked from the app.
- [ ] Optional: CI upload to the internal/closed track with a service account limited to testing tracks.

### Timeline

| Day | Step |
|---|---|
| −7 … −1 | Account created and verified. Name, package, privacy policy, listing, content rating, data safety, target audience done. Build uploaded to **internal testing** first (fast, no 12-tester rule) to catch R8/install problems. |
| −3 … 0 | Recruit **15–20** testers (buffer for dropouts) and add them to a Google Group. Set the closed track's **countries** to cover every tester (otherwise "not available in your country", and they never count). |
| 0 | Closed track released (review can take hours to days). Send the opt-in link `https://play.google.com/apps/testing/<applicationId>`, which works only after the release. The 14 days count from each tester's opt-in. |
| 1–14 | Testers use scan, review, collections, prices, export. Ship at least one update (the production form asks about changes). Reminder mid-way so nobody opts out. Track opted-in count in the Console. |
| 15+ | When the Console shows ≥ 12 testers with 14 days: *Apply for production*, answer from the collected feedback. |
| ~15–22 | Production review (≤ 7 days, sometimes longer). Then production and open testing unlock. |

About 4 weeks from account creation to production, if nothing is rejected.

### Recruiting testers

- Friends and family with Android phones (best engagement; they must keep the app installed and opted in).
- German TCG players (local game store, club, Discord) who collect anyway.
- Communities that trade closed-test participation (for example on Reddit) fill numbers quickly, but Google asks
  about engagement, and pure exchange testers rarely give feedback. Use them only as a top-up. **Assumption**:
  how strictly Google rates engagement is not documented.
- Testers need a Google account on Android. Ask them to open the app a few times during the 14 days.

### Decisions summary

| # | Decision | Recommendation |
|---|---|---|
| 1 | App name | **Lumifoil** (Fabian, 2026-11-18; recommendation was Binderkeep, then Lumifoil, Prismbook) |
| 2 | applicationId | `de.fabianduerkop.lumifoil` (applied), namespace stays `de.fabianduerkop.cardindex` |
| 3 | Domain | Register `.de` (and `.app` if free) for the chosen name |
| 4 | Contributed photos as "shared" in Data safety | Yes, declare as shared |
| 5 | Target audience | 13+ |
| 6 | Card art in store screenshots | Small UI thumbnails only, own art for the feature graphic |
| 7 | DE store text with an English-only app | Ship it, add a German UI later |
| 8 | Trademark filing | Optional; DE/EU classes 9 + 42 after the name is fixed |
