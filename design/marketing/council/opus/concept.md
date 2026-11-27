# Opus seat: „Dieses Set ist noch leer.“

The flyer treats the closed test as a set to complete: a huge `0/12` collector number with an empty progress bar,
set up like the app's own set screen, and below it a binder sheet of 12 empty pockets. Pocket 01 is lit with the foil
band and asks „Du?“. Collectors know that pull instantly: an incomplete set with your slot still open. The real
Google rule (12 testers, 14 days) becomes the reason for the set, so nothing has to be explained. Visually it is a
black binder page with one sleeve reflection across it. It uses only type, empty sleeves and the QR code: no
screenshots, no photos, no card art.

**Why it stops a collector:** `0/12`, the empty pockets and „Dieses Set ist noch leer“ make the same gap feeling a
missing card does, readable from a metre away. The QR sits right next to the open pocket, so the next step is obvious.

## Build

```sh
node design/marketing/council/opus/build.mjs
```

Source: `flyer.html` (one HTML in mm units; `body.card` switches to the taller phone layout). The script uses
playwright-core and Chromium from `~/.claude/skills/marketing-images/` and writes:

- `out/flyer-a6-bleed.pdf`: 111 × 154 mm page, trim 105 × 148 mm at 3 mm inset, text ≥ 5 mm inside the trim
- `out/flyer-a6.png`: trimmed A6, 1188 × 1677 px
- `out/join-card-1080x1920.png`: WhatsApp/Signal version

Assets in `assets/`: Schibsted Grotesk (app font), the white Foilark lockup from `design/logo/`, and `qr.svg` (the
shared QR with a 4-module white quiet zone, 26 mm wide on A6). The QR decodes to `https://foilark.app/test` in both PNGs.

## Image prompts

None. No AI images were generated; the concept is pure type and graphic shapes.
