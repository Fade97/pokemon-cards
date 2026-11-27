# Tester flyer (German)

M2 from [tester-recruiting.md](../../docs/release/tester-recruiting.md) (#261): the flyer for the store or league
visit and the join card for WhatsApp/Signal. German, because both go to German collectors. Four styles to compare;
pick one (or test two at different stores). Three more, designed independently by the council, are in
[council/](council/README.md).

| Variant | Style | Hook |
|---|---|---|
| `a-phone` | Dark photo, porcelain join panel | „Deine Karten. Offline erkannt.“ |
| `b-swiss` | Light page, oversized type, pill photo window, sea-glass colour block | „Scannen ohne Netz.“ |
| `c-holo` | Holo foil full bleed, tilted headline with outline echo, stickers, QR on a white card | „Werde Tester.“ |
| `d-card` | The flyer is a collector card of our own design (holo frame, abilities, „01/12“) | „Nur 12 Tester-Karten.“ |

Each variant has four files in `out/<variant>/`:

| File | Use |
|---|---|
| `flyer-a6-bleed.pdf` | Print shop: A6 with 3 mm bleed (page 111 × 154 mm, trim to 105 × 148 mm). |
| `flyer-a4-4up.pdf` | Four flyers (94 %) on one A4 sheet with a hairline to cut along, for printing at home at 100 %. |
| `flyer-a6-preview.png` | Preview of the trimmed A6. |
| `join-card-1080x1920.png` | Join card to send in chats. |

The QR code points to `https://foilark.app/test`, a page that comes with #260. **Don't hand out a flyer until
that page is live** and the day-0 gates in the plan are met.

## Images

Generated on 2026-11-27, no Pokémon characters, card designs, Poké Balls, birds or text (ADR 0009):

| File in `assets/source/` | Made with | Used in |
|---|---|---|
| `hero-phone-green.png` | Codex (gpt-image via the Codex CLI) | A |
| `scan-green.png` | Codex | B |
| `foil.png` (brightened copy `assets/foil.jpg`) | Codex | C |
| `tilt-green.png` | Antigravity (Gemini), 848 × 1264 upscaled 2× | D |

The other candidates are on Vulcan in `~/data/pokemon-cards-data/marketing/flyer-gen-2026-11-27/`, prompts in
the `prompts.md` there.

The phones show real app screens without card art (debug build on talos, `design/screenshots/`), put into the
green screen with `compose_screen.py`: A the set 151 grid (`ui-screen-set.png`), B the copy sheet
(`ui-screen-copy.png`), D the trade binder (`ui-screen-binder.png`). Swap them for release-build screenshots once
they exist. Type is Schibsted Grotesk from the app, colours are the Pearl tokens (ADR 0014).

## Rebuild

```bash
# only when a photo or an app screen changes (needs Pillow and numpy)
cd design/marketing
python compose_screen.py assets/source/hero-phone-green.png assets/ui-screen-set.png assets/hero-phone.png
python compose_screen.py assets/source/scan-green.png assets/ui-screen-copy.png assets/scan.png
python compose_screen.py assets/source/tilt-green.png assets/ui-screen-binder.png assets/tilt.png

node build.mjs            # all variants
node build.mjs c-holo     # one variant
```

A variant is `variants/<name>.html` (text) plus `variants/<name>.css` (layout) on top of `base.css` (font, tokens,
bleed page, A4 sheet). Sizes are in `cqw`, so one layout serves every format; `.story` styles the join card and
`var(--bleed)` lets backgrounds reach into the print bleed. The script takes playwright-core and Chromium from the
`marketing-images` skill (`MARKETING_IMAGES=<dir>` and `CHROME_PATH` override them). Check a new QR code with a
phone before printing.

QR code: `segno` (`pip install segno`), error level M, ink `#26353C`:

```bash
segno --error m --border 0 --dark '#26353C' --light transparent \
  -o design/marketing/assets/qr-foilark-test.svg https://foilark.app/test
```
