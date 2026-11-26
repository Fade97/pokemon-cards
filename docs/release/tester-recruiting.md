# Tester recruiting for the closed test

Issue #259. Builds on [play-launch.md](play-launch.md) §4 (rules, timeline, setup). Drafted by a council
(Claude Opus, GPT Sol, Gemini 4 Neon) on 2026-11-27. **Assumption** marks anything not verified.

**Goal:** at least 12 testers opted in **continuously for 14 days**, with real use and feedback, because the
production form asks about engagement. Target **18–20 opted in by day 2**, so dropouts never take the count
below 12. This plan is not the public launch campaign.

**Short version:** about three quarters of the testers should come from Fabian's own network and one local game
store or league. Reddit only tops up, through the channels that allow it. Two short "missions" and a real update
on day 7 keep testers active, and they give Fabian answers for the production form.

## 1. Roadmap

Day 0 = closed track released and the opt-in link works.

| Phase | Days | Target | Exit criterion |
|---|---|---|---|
| Prep | −7 … −4 | — | `foilark.app/test` live (M1). Google Group created (§4). Closed track set to **all countries**. Tracking sheet ready. Modmail to r/PokemonTCG_DE sent. Shop or league organizer asked. |
| Warm pool | −5 … −1 | 15 in the Group | 15 people (personal DMs plus the shop) have joined the Group **with the Google account their Play Store uses** |
| Activation | 0 … 2 | 18–20 opted in, ≥ 16 installed | Day 0 message sent. Each tester has replied "installed". The Console count is ≥ 16. If it is below 16 on day 2: Self Promo Sunday post, then at most 3–5 people from tester-exchange channels. |
| Retention | 3 … 13 | ≥ 14 still in | Update shipped on day 6–7 with fixes from tester reports. Day 7 message sent. ≥ 8 testers have replied with feedback. |
| Apply | 14 + latest opt-in | ≥ 12 with 14 days | The Console confirms eligibility, then *Apply for production*, using the feedback log (§4). **Testers stay opted in until production is approved.** |

Every tester's 14 days start at their own opt-in, and leaving and rejoining starts them over. Someone who opts in
on day 3 counts from day 17, so put the effort into days 0–2.

## 2. Channels

Yields are **Assumptions** for planning (engaged testers per hour of Fabian's time, follow-up included).

| # | Channel | Yield/h | Rules | Ask |
|---|---|---|---|---|
| 1 | Personal DMs: friends, family, colleagues with Android. Ask each yes for one more Android-owning collector. | 4–6 | none | Message M5.2 |
| 2 | One local game store, league or trade night (DE). [Event finder](https://play.pokemon.com/de-de/local-tournaments/tcg/) | 2–4 | Ask the owner or organizer first. Then either leave the flyer (M2) on the counter or have the organizer share it in the store chat. | Live demo of an offline scan, then the QR code. These collectors bring real DE/EN cards, the best scan feedback. |
| 3 | Own channels: README line "Become a tester", Fabian's socials | 1–2 | own | Link to `/test` |
| 4 | r/PokemonTCG, weekly **Self Promo Sunday** thread only | 1–3 | Apps only in that thread ([mod post](https://www.reddit.com/r/PokemonTCG/comments/lrnn1s/changes_to_the_rules_regarding_self_promotion_and/)) | Post M5.1, ask for Android collectors with German or Japanese cards |
| 5 | r/PokemonTCG_DE | 1–3 | Promotion only with explicit mod approval ([rules update](https://www.reddit.com/r/PokemonTCG_DE/comments/1r4txjx/community_update_werberegeln_mehr_mitspracherecht/)). Send modmail M5.4 on day −4. | Post M5.1 in German, only after a yes |
| 6 | r/AndroidClosedTesting, r/GooglePlayDeveloper tester thread | many, low quality | Reciprocal: you test their apps daily. Quick-bounce testers risk the "testers not engaged" rejection. | **Top-up only, at most 3–5 people**, and only if below 16 on day 2 |

**Skip:** r/pkmntcgcollections (self-promotion banned), the #PKDE Discord (bans all self-promotion and unsolicited
invites, even in DMs, [rules](https://pokemon-discord.de/regeln), checked 2026-11-27), BisaBoard and Pokéfans
(advertising removed), PokéCardex (a competitor's community), r/fossdroid (the app ships ML Kit, DESIGN.md §2).

**Never:** paid tester services, fake or second accounts, rewards for ratings or reviews.

Reddit could not be fetched directly while drafting, so the Reddit rules come from the delegates' sources. Read
each sidebar before posting.

## 3. Marketing material

| ID | Piece | Format | Content | Who | Used in |
|---|---|---|---|---|---|
| M1 | Tester page `foilark.app/test` (EN/DE) | New route in `backend/src/site.ts`, like `/imprint`, no JS | One sentence on Foilark. "Why 14 days". Three steps: join Group → open opt-in link → install **from Play**. The two missions. FAQ: no iPhone; **same Google account as the Play Store** (at the top); "not available" fix; Android 8+. Feedback links (in-app, GitHub form, email). Disclaimer: not affiliated with Nintendo/TPC. | Agent builds. Fabian fills in the Group URL and deploys. | Every channel, the one link |
| M2 | Join card and flyer | 1080 × 1920 PNG for WhatsApp/Signal; A6 PDF with a QR code to `/test` | The steps from M1 with two small UI crops, logo, disclaimer | Agent renders (`marketing-images`), Fabian prints | DMs, store |
| M3 | Post image | 1080 × 1350 PNG; also 1200 × 630 as the OG image of `/test` | Pearl porcelain background, `foilark-horizontal.svg`, a phone frame with the scan or binder UI (small thumbnails only), headline "Scan cards offline. No account. No ads.", badge "Android testers wanted". No card art as the main visual, no Pokéball, no bird, no Pokémon logo. | Agent | Reddit, socials, store chat |
| M4 | Demo clip (optional) | 15–20 s, 9:16 MP4 ≤ 8 MB, burned-in captions | Airplane mode on → scan → card found → price → add to binder | Fabian records on the S25, agent cuts and captions | Reddit, socials |
| M5 | Copy kit | Text, below | Recruiting post, DMs, modmail, funnel messages | Agent drafts (done here), Fabian sends | All |
| M6 | Tracking sheet | Private spreadsheet, not in the repo | Columns in §4 | Fabian | Funnel |

M3 can share its layout with the Play feature graphic (1024 × 500, play-launch.md §3), but the feature graphic stays
a separate file. Screenshots in M1–M3 come from the release build.

### M5.1 Recruiting post (EN)

> **Looking for ~15 Android testers: free, offline scanner for Pokémon TCG cards (open source, no ads, no account)**
>
> I'm a solo developer in Germany. Foilark recognises cards on the phone itself, so scanning works without
> internet and has no scan limit. It sorts cards into sets, binders, a trade pile and a wishlist and shows
> Cardmarket (EUR) and TCGplayer (USD) prices. The app is in English; it recognises cards in many languages.
>
> Google needs 12 testers who stay in a closed test for 14 days before I can publish. **You'd:** join a Google
> Group, install the app from Play, scan a few of your cards twice in two weeks, and tell me what broke.
> Android 8+ and a Google account. All steps: **foilark.app/test**. Code: github.com/Fade97/pokemon-cards
>
> Unofficial fan project, not affiliated with or endorsed by Nintendo, The Pokémon Company, Cardmarket or TCGplayer.

DE version for r/PokemonTCG_DE: translate one to one and add "Die App selbst ist auf Englisch."

### M5.2 Friends message

> **DE:** Hey! Ich bringe meine App Foilark in den Play Store: Pokémon-Karten offline scannen und sammeln,
> kostenlos. Google verlangt vorher 12 Tester, die 14 Tage am Stück dabeibleiben. Hilfst du mir? Du brauchst ein
> Android-Handy, es dauert 2 Minuten: foilark.app/test. Wichtig: dasselbe Google-Konto wie im Play Store, und bitte
> nicht vor meiner Entwarnung deinstallieren, sonst zählt es nicht. Zweimal ein paar Karten scannen und mir sagen,
> was hakt, reicht völlig.

> **EN:** Hey! I'm getting my app Foilark into the Play Store: scan and collect Pokémon cards offline, free.
> Google first needs 12 testers who stay in for 14 days straight. Could you help? You need an Android phone and two
> minutes: foilark.app/test. Please use the same Google account as your Play Store, and don't uninstall until I
> give the all-clear, or it won't count. Scanning a few cards twice and telling me what's awkward is plenty.

Friends without cards: say they can still test onboarding, browsing sets and prices. Scanning card pictures shown on
another screen is **untested**; check it on the S25 before suggesting it.

### M5.3 Store or league organizer (DE)

> Hallo! Ich entwickle Foilark, eine kostenlose Open-Source-App, die Pokémon-Karten offline scannt und Cardmarket-
> Preise zeigt. Für die Veröffentlichung im Play Store brauche ich ein paar Android-Sammler, die zwei Wochen testen.
> Darf ich beim nächsten Liga- oder Tauschabend kurz zeigen, wie sie funktioniert, und einen Flyer mit QR-Code
> hinlegen? Es gibt nichts zu kaufen, keine Werbung, keine Anmeldung.

### M5.4 Modmail (r/PokemonTCG_DE; same idea for any other sub)

> Hallo Mod-Team, ich bin Hobby-Entwickler aus Deutschland. Meine App Foilark (kostenlos, Open Source unter GPLv3,
> keine Werbung, kein Konto) scannt Pokémon-Karten offline und zeigt Cardmarket-Preise. Für die Freigabe im Play
> Store brauche ich 12 Tester für 14 Tage. Dürfte ich dazu einmalig einen Beitrag posten? Den Entwurf hänge ich an.
> Wenn nicht, kein Problem, danke fürs Lesen.

## 4. Funnel and retention

**Google Group:** "Anyone can join" (no approval per person). Member list visible to **managers only**, posting
by **managers only**, so testers don't see each other's addresses. **Assumption:** check the setting names in the
Groups UI.

| When | Send | Content |
|---|---|---|
| On signup | — | Tester joins the Group from `/test` and tells Fabian (DM or the email link) under which name. Fabian adds them to the sheet. |
| Day 0 | Group email + personal DMs | Opt-in link `https://play.google.com/apps/testing/de.fabianduerkop.foilark`. **Mission 1:** download your language pack, switch to airplane mode, scan 5 cards, fix one with the review picker, save one to a binder. Reply "installed" (a screenshot of "You're a tester" helps). |
| Day 2–3 | Personal DM | Only to people without "installed": help with the account or country problem. |
| Day 6–7 | Group email, after the update is live | "Version 1.0.x is out, thanks to everyone who reported X." **Mission 2:** update, reopen your binder, check a price, try a holo or reverse holo. Reply with three answers: what broke, what's missing, phone model. |
| Day 12 | Group email | "Almost there. Please stay in until I write again." |
| After approval | Group email | Thanks and the all-clear. Testers may leave, or stay for open testing. |

**Tracking sheet (M6):** name · channel · Group date · opt-in date (self-reported) · installed ✓ · day-7 reply ✓
· feedback items · status. No email addresses or Google account names in the sheet. The Console shows only the
opted-in **count**, not names, so compare it with the sheet every two days. If the count falls to 14, recruit 3
more at once.

**Feedback log for the production form:** for each report, note the date, area (scan, prices, collections),
the fix and the version that shipped it. The in-app feedback (Settings → About) pre-fills device and version.

## 5. Risks

| Risk | Fix |
|---|---|
| Count drops below 12 | 18–20 by day 2, sheet vs. Console every two days, backups ready |
| "Not available in your country" | Closed track on all countries before day 0 |
| Group joined with one Google account, Play Store on another | Warning at the top of `/test`, the "installed" screenshot |
| Rejected for low engagement | Missions, the day-7 update, the feedback log, no more than 5 exchange testers |
| Post removed or ban | Only the channels in §2, modmail first, one post per sub, no DM spam |
| Card art or trademark issues in images | UI thumbnails only, disclaimer on every piece |
| Testers see each other's emails | Group member list managers-only |

## 6. Only 3 evenings?

Cut all cold community posts (Reddit, modmails), M3 and M4. Spend the evenings on:

1. M1 (agent-built), Google Group, sheet, and 20–25 personal DMs.
2. One store or league evening with M2.
3. The day-0 send-out and helping the first testers through to "installed".

The day-7 update still has to happen; it is a development task, not marketing.

## 7. Follow-up work

| What | Who | Issue |
|---|---|---|
| M1 `/test` route (EN/DE) with tests | Agent | to file |
| M2 and M3 images | Agent | to file |
| Group, sheet, DMs, store, posts, day-0…day-12 messages | Fabian | #15 |
