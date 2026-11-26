# Tester recruiting for the closed test

Issue #259. Builds on [play-launch.md](play-launch.md) §4 (rules, timeline, setup). Drafted by a council
(Claude Opus, GPT Sol, Gemini 4 Neon) on 2026-11-27. **Assumption** marks anything not verified.

**Goal:** at least 12 testers opted in **continuously for 14 days**, with real use and feedback, because the
production form asks about engagement. Target **18–20 opted in by day 2**, so dropouts never take the count
below 12. This plan is not the public launch campaign.

**Short version:** about three quarters of the testers should come from Fabian's own network and one local game
store or league. Before the test opens, collect promises; once the opt-in link works, everyone joins in one
sitting. Reddit gives a few extra collectors. Two short "missions" and a real update around day 7 keep testers
active, and they give Fabian answers for the production form.

## 1. Roadmap

Day 0 = closed track released and the opt-in link works for a non-manager account.

| Phase | Days | Target | Exit criterion |
|---|---|---|---|
| Prep | −7 … −4 | — | Closed-track release **submitted for review** (it can take hours to days). `foilark.app/test` live (M1). Google Group created and set up (§4). Closed track on **all countries**. Tracking sheet ready (M6). Shop or league organizer asked (M5.3). |
| Promises | −5 … −1 | 18 yeses + 3 named reserves | DMs (M5.2) ask for a yes, **not** for the link yet: Android 8+, and they know which Google account their Play Store uses. The r/PokemonTCG Self Promo Sunday comment goes up on the last Sunday before day 0 and asks people to reply "interested". |
| Activation | 0 … 2 | 18–20 opted in, ≥ 16 installed | Fabian has run the whole flow once with a second, non-manager Google account. Day-0 message (M5.5) sent to every yes and every "interested" reply. Store or league visit with the flyer (M2), so people join on the spot. Each tester has replied "installed". If fewer than 16 by day 2: message the reserves, then at most 3–5 people from r/AndroidClosedTesting. |
| Retention | 3 … 13 | ≥ 14 still in | Day-7 message (M5.6) sent, with Mission 2, whether or not an update is out. If an update ships, it fixes tester reports. ≥ 8 testers have sent feedback. |
| Apply | the day the 12th tester reaches 14 days | ≥ 12 with 14 days | The Console confirms eligibility, then *Apply for production*, using the feedback log (§4). **Testers stay in until production is approved** (M5.7). |

Every tester's 14 days start at their own opt-in, and leaving and rejoining starts them over. Someone who opts in
on day 3 counts from day 17, so put the effort into days 0–2.

## 2. Channels

Yields are **Assumptions** for planning (engaged testers per hour of Fabian's time, follow-up included). By
day −2, count the real yeses before relying on the split.

| # | Channel | Yield/h | Rules | Ask |
|---|---|---|---|---|
| 1 | Personal DMs: friends, family, colleagues with Android. Ask each yes for one more Android-owning collector. | 4–6 | none | M5.2 |
| 2 | One local game store, league or trade night (DE). [Event finder](https://play.pokemon.com/de-de/local-tournaments/tcg/) | 2–4 | Ask the owner or organizer first (M5.3). Visit **after day 0**, so the QR code works end to end. | Live demo of an offline scan, then the flyer (M2). These collectors bring real DE/EN cards, the best scan feedback. |
| 3 | Own channels: a "Become a tester" line in the README, Fabian's socials | 1–2 | own | Link to `/test` (after day 0) |
| 4 | r/PokemonTCG, weekly **Self Promo Sunday** thread only | 1–3 | Apps only in that thread ([rules wiki](https://www.reddit.com/r/PokemonTCG/wiki/rules/)) | M5.1 as a comment, ask for Android collectors with German or Japanese cards |
| 5 | r/AndroidClosedTesting | many, low quality | Reciprocal: you test their apps. Quick-bounce testers risk the "testers not engaged" rejection. | **Top-up only, at most 3–5 people**, only if below 16 on day 2 after the reserves |

**Skip:** r/PokemonTCG_DE (promotion needs mod approval, and its app rule asks for support of common mobile
platforms, which an Android-only app does not meet; **Assumption**, the delegates read it, Reddit was not
fetchable here), r/pkmntcgcollections (self-promotion banned), the #PKDE Discord (bans all self-promotion and
unsolicited invites, even in DMs, [rules](https://pokemon-discord.de/regeln), checked 2026-11-27), BisaBoard and
Pokéfans (advertising removed), PokéCardex (a competitor's community), r/fossdroid (the app uses ML Kit for the
number OCR, DESIGN.md).

**Never:** paid tester services, fake or second accounts, rewards for ratings or reviews.

Read each sidebar again before posting.

## 3. Marketing material

| ID | Piece | Format | Content | Who | Used in |
|---|---|---|---|---|---|
| M1 | Tester page `foilark.app/test` (EN/DE) | New text-only route in `backend/src/site.ts`, like `/imprint` (no images, no JS) | One sentence on Foilark. "Why 14 days". Three steps: 1. join the Google Group, 2. open the opt-in link and tap **Become a tester**, 3. install **from Play**, all with **the same Google account as the Play Store** (at the top). "Stay in the Group and the test until Fabian gives the all-clear." The two missions. FAQ: no iPhone; Android 8+; "not available" (wrong account or country); English UI. Feedback: in-app (Settings → About), the private email link, the GitHub form (**public**). Disclaimer: not affiliated with Nintendo/TPC. | Agent builds. Inputs from Fabian: Group URL. Opt-in link: `https://play.google.com/apps/testing/de.fabianduerkop.foilark`. | Every channel after day 0 |
| M2 | Join card and flyer | 1080 × 1920 PNG for WhatsApp/Signal; A6 PDF with a QR code to `/test`. Source and output in `design/marketing/`, rendered HTML → PNG/PDF (the `marketing-images` skill). | The M1 steps, logo, disclaimer, two UI crops **without card art** (e.g. the price sheet, a set list) | Agent renders, Fabian prints | Store, DMs |
| M3 | Post image | 1080 × 1350 PNG, same build as M2 | Pearl porcelain background, `foilark-horizontal.svg`, a phone frame with art-free UI, headline "Scan cards offline. No ads. No account in the app.", badge "Android testers wanted". No card art, no Pokéball, no bird, no Pokémon logo. | Agent | Reddit, socials |
| M5 | Copy kit | Text, below | Recruiting post, DMs, organizer message, funnel messages | Agent drafts (done here), Fabian sends | All |
| M6 | Tracking sheet | Private spreadsheet, not in the repo | Columns in §4 | Fabian | Funnel |

Screenshots for M2 and M3 come from the release build. A demo video and an OG image were left out on purpose:
they do not move the tester count.

### M5.1 Recruiting post (EN)

> **Looking for ~15 Android testers: free scanner for Pokémon TCG cards that works offline (open source, no ads)**
>
> I'm a solo developer in Germany. Foilark recognises cards on the phone itself: after a one-time language pack
> download, scanning works without internet and has no scan limit. It sorts cards into sets, binders, a trade
> pile and a wishlist, and shows Cardmarket (EUR) prices and, for English cards, TCGplayer (USD) prices when
> you're online. No account in the app. The app is in English and recognises cards in many languages.
>
> Google needs 12 testers who stay in a closed test for 14 days before I can publish. **You'd:** join a Google
> Group, become a tester on Play and install the app (about five minutes, same Google account as your Play
> Store), scan a few of your cards twice in two weeks, and tell me what went wrong. Android 8+.
> Reply "interested" and I'll send the steps as soon as the test opens. Code: github.com/Fade97/pokemon-cards
>
> Unofficial fan project, not affiliated with or endorsed by Nintendo, The Pokémon Company, Cardmarket or TCGplayer.

After day 0, replace the "Reply interested" sentence with "All steps: foilark.app/test".

### M5.2 Friends message (before day 0)

> **DE:** Hey! Ich bringe meine App Foilark in den Play Store: Pokémon-Karten scannen und sammeln, auch offline,
> kostenlos. Google verlangt vorher 12 Tester, die 14 Tage am Stück dabeibleiben. Wärst du dabei? Du brauchst ein
> Android-Handy und etwa 5 Minuten. Zweimal ein paar Karten scannen und mir sagen, was hakt, reicht völlig. Wenn
> ja, schicke ich dir den Link, sobald der Test offen ist.

> **EN:** Hey! I'm getting my app Foilark into the Play Store: scan and collect Pokémon cards, offline too, free.
> Google first needs 12 testers who stay in for 14 days straight. Would you join? You need an Android phone and
> about five minutes. Scanning a few cards twice and telling me what's awkward is plenty. If you're in, I'll send
> the link as soon as the test opens.

Friends without cards can still test onboarding, browsing sets and prices. Scanning card pictures on another screen
is **untested**; check it on the S25 before suggesting it.

### M5.3 Store or league organizer (DE)

> Hallo! Ich entwickle Foilark, eine kostenlose Open-Source-App, die Pokémon-Karten offline scannt und Cardmarket-
> Preise zeigt. Für die Veröffentlichung im Play Store brauche ich ein paar Android-Sammler, die zwei Wochen testen.
> Darf ich beim nächsten Liga- oder Tauschabend kurz zeigen, wie sie funktioniert, und einen Flyer mit QR-Code
> hinlegen? Es gibt nichts zu kaufen, keine Werbung, kein Konto in der App.

### Funnel messages (M5.5–M5.7)

Send each as a Group email **and** as a personal DM to anyone who did not react (Group mail can land in spam).
Replies go to Fabian privately, never to the Group.

**M5.5 Day 0**

> **DE:** Der Test ist offen! 1. Tritt der Gruppe bei: <Group-Link>. 2. Öffne
> https://play.google.com/apps/testing/de.fabianduerkop.foilark und tippe auf „Tester werden". 3. Installiere
> Foilark aus dem Play Store. Immer mit demselben Google-Konto wie im Play Store.
> **Mission 1:** Sprachpaket laden, Flugmodus an, 5 Karten scannen, eine unsichere Karte unter „Review"
> bestätigen (falls eine dort landet), eine Karte in einen Ordner legen, Flugmodus wieder aus. Schreib mir kurz
> „installiert". Bitte bis zu meiner Entwarnung in der Gruppe und im Test bleiben und die App installiert lassen.

> **EN:** The test is open! 1. Join the group: <Group link>. 2. Open
> https://play.google.com/apps/testing/de.fabianduerkop.foilark and tap "Become a tester". 3. Install Foilark from
> the Play Store. Always with the same Google account as your Play Store.
> **Mission 1:** download your language pack, turn on airplane mode, scan 5 cards, confirm an unsure one under
> "Review" (if one lands there), put one card in a binder, turn airplane mode off again. Send me a quick
> "installed". Please stay in the group and the test, with the app installed, until I give the all-clear.

**M5.6 Day 7**

> **DE:** Halbzeit, danke! [Falls ein Update draußen ist: Version <x> ist im Play Store, mit <tatsächlicher Fix>.]
> **Mission 2:** Ordner wieder öffnen, einen Preis ansehen, eine Holo- oder Reverse-Holo-Karte scannen. Dann
> antworte mir mit drei Dingen: Was ging schief? Was fehlt dir? Welches Handy hast du?

> **EN:** Halfway, thank you! [If an update is out: version <x> is in the Play Store, with <actual fix>.]
> **Mission 2:** reopen your binder, look at a price, scan a holo or reverse holo. Then reply with three things:
> what went wrong, what's missing, which phone you have.

**M5.7 Day 12 and after approval**

> **DE (Tag 12):** Fast geschafft. Bitte bleib in der Gruppe und im Test, bis ich mich wieder melde.
> **DE (nach Freigabe):** Geschafft, Foilark ist freigegeben. Danke! Du kannst den Test jetzt verlassen oder
> dabeibleiben.

> **EN (day 12):** Almost there. Please stay in the group and the test until I write again.
> **EN (after approval):** Done, Foilark is approved. Thank you! You can leave the test now or stay in.

## 4. Funnel and retention

**Google Group settings** (**Assumption:** check the exact names in the Groups UI):

- Who can join: anyone (no approval per person).
- Who can see members and member emails: managers only.
- Who can post: managers only; replies go to the author, not the Group.
- Default delivery for new members: each email.

**Order:** promise (DM or "interested") → day 0: Group → **Become a tester** → install from Play → "installed" →
Mission 1 → day 7: Mission 2 and feedback → day 12 reminder → apply → all-clear after approval.

**Tracking sheet (M6):** name · channel · reserve yes/no · Group joined · opt-in date (self-reported) · installed
✓ · Mission 2 reply ✓ · feedback items · status. No email addresses or Google account names in the sheet. The
Console shows only the opted-in **count**, not names, so compare it with the sheet every two days. If the count
falls to 14, message the reserves at once.

**Feedback log for the production form:** for each report, note the date, area (scan, prices, collections),
the fix and the version that shipped it. The in-app feedback (Settings → About) pre-fills device and version.

## 5. Risks

| Risk | Fix |
|---|---|
| Count drops below 12 | 18 yeses + 3 reserves, sheet vs. Console every two days |
| Closed-track review delays day 0, promises cool off | Submit on day −7; send the day-0 message within hours of release |
| "Not available in your country" | Closed track on all countries before day 0 |
| Group joined with one Google account, Play Store on another | Warning at the top of M1 and in M5.5, the test run with a second account |
| Rejected for low engagement | Missions, Mission-2 replies, the feedback log, no more than 5 exchange testers |
| Post removed or ban | Only the channels in §2, one comment per week, no DM spam |
| Card art or trademark issues in images | Art-free UI crops, disclaimer on every piece |
| Testers see each other's emails or reply to everyone | Group settings above |

## 6. Only 3 evenings?

Cut Reddit and M3. Spend the evenings on:

1. M1 (agent-built), Google Group, sheet, and 20–25 personal DMs (M5.2).
2. One store or league evening with M2, after day 0.
3. The day-0 send-out and helping the first testers through to "installed".

## 7. Follow-up work

| What | Who | Issue |
|---|---|---|
| M1 `/test` route (EN/DE) with tests | Agent | to file |
| M2 and M3 images | Agent | to file |
| Group, sheet, DMs, store, posts, M5.5–M5.7 | Fabian | #15 |
