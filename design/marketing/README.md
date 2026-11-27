# Tester flyer (German)

M2 from [tester-recruiting.md](../../docs/release/tester-recruiting.md) (#261): the flyer for the store or league
visit and the join card for WhatsApp/Signal. German, because both go to German collectors.

| File | Use |
|---|---|
| `out/flyer-a6-bleed.pdf` | Print shop: A6 with 3 mm bleed (page 111 × 154 mm, trim to 105 × 148 mm). |
| `out/flyer-a4-4up.pdf` | Four flyers (94 %) on one A4 sheet with a hairline to cut along, for printing at home at 100 %. |
| `out/flyer-a6-preview.png` | Preview of the trimmed A6. |
| `out/join-card-1080x1920.png` | Join card to send in chats. |

The QR code points to `https://foilark.app/test`, a page that comes with #260. **Don't hand out the flyer until
that page is live** and the day-0 gates in the plan are met.

## Content

- Photo: generated with Codex (gpt-image through the Codex CLI, 2026-11-27): a hand holding an unbranded phone
  over blank holographic cards, phone screen in chroma green. Source `assets/source/hero-phone-green.png`, prompt
  below. No Pokémon characters, card designs, Poké Balls, birds or text (ADR 0009). The other candidates (Codex
  and Antigravity/Gemini) are on Vulcan in `~/data/pokemon-cards-data/marketing/flyer-gen-2026-11-27/`.
- App screen: the real set 151 screen from `design/screenshots/pearl-97/light-sets.png` (debug build on talos),
  `assets/ui-screen-set.png`, put into the phone with `compose_screen.py` → `assets/hero-phone.png`. Swap it for a
  release-build screenshot once one exists.
- Pearl night tokens and Schibsted Grotesk from the app (ADR 0014), the night logo from `design/logo/`.
- Disclaimer: not affiliated with Nintendo or The Pokémon Company.

## Rebuild

```bash
# only when the photo or the app screen changes (needs Pillow and numpy)
python design/marketing/compose_screen.py design/marketing/assets/source/hero-phone-green.png \
  design/marketing/assets/ui-screen-set.png design/marketing/assets/hero-phone.png
node design/marketing/build.mjs
```

Edit `flyer.html` (text) and `flyer.css` (layout; sizes are in `cqw`, so one layout serves every format).
The script takes playwright-core and Chromium from the `marketing-images` skill (`MARKETING_IMAGES=<dir>` and
`CHROME_PATH` override them). Check a new QR code with a phone before printing.

QR code: `segno` (`pip install segno`), error level M, ink `#26353C`:

```bash
segno --error m --border 0 --dark '#26353C' --light transparent \
  -o design/marketing/assets/qr-foilark-test.svg https://foilark.app/test
```

## Photo prompt

> Portrait 2:3 PNG, at least 1536 pixels on the long side. Photorealistic studio photograph for a premium Android
> app print flyer. A hand naturally grips a slim, unbranded dark Android phone above three unprinted trading cards
> on a dark matte table. The phone faces the camera at a slight angle and all four display corners are visible. The
> active display is uniformly flat pure chroma green #00FF00, from edge to edge, with no reflections, texture,
> camera cutout, gradient, interface elements, or glow. Soft directional studio lighting and restrained iridescent
> foil reflections spill across the fingers and the dark metal frame. Blank rounded-corner cards have uninterrupted
> edge-to-edge abstract sea-glass foil with fine prismatic highlights. Place the phone, hand, and cards below the
> upper third, leaving that entire third as calm deep ink #111718 for white typography to be added later.
> Supporting colors #26353C, #356C6C and #8CCBC2. No visible lettering, numbers, symbols, logos, watermarks, birds,
> creatures, franchise imagery, ball emblems, yellow borders, or printed card layouts. Clean background and natural
> anatomy.
