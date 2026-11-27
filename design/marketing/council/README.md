# Council flyers (German)

Three tester flyers (M2, #261), each designed alone by one council seat on 2026-11-27 from the same brief: goal,
required content and the legal rules, no examples and no style. Each folder has its own `concept.md`, sources, a
`build.mjs` and `out/` with `flyer-a6-bleed.pdf` (A6 with 3 mm bleed), `flyer-a6.png` and
`join-card-1080x1920.png`.

| Folder | Seat | Idea | Image |
|---|---|---|---|
| `opus/` | Claude Opus | „0/12 · Dieses Set ist noch leer.“ The test is a set to complete: 12 empty binder pockets, pocket 01 asks „Du?“. | None, type and shapes only |
| `sol/` | GPT Sol | „KARTE VOR DIE LINSE.“ A hand lifts a blank foil card into a camera finder, printed as a coarse halftone. | Codex (`codex-image.mjs`) |
| `gemini/` | Gemini 4 Neon | „Pokémon-Karten offline scannen.“ A specimen sheet: a holo card in a toploader, a 01–12 slot strip. | Antigravity image generator |

Rebuild one: `node design/marketing/council/<seat>/build.mjs`. The QR code (`https://foilark.app/test`) decodes in
every PNG. Don't hand a flyer out before #260 (the `/test` page) is live.

Changes after delivery: Gemini's strip showed slots 01–04 as taken, which claimed testers that don't exist yet; now
only 01 is marked as „yours“. Opus and Gemini use the app's Schibsted Grotesk from `android/` instead of a copy.

Fonts in `gemini/assets/fonts/`: Bricolage Grotesque and JetBrains Mono, both SIL Open Font License 1.1 (texts next
to them).
