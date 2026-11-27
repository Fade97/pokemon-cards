# Tester flyer (German)

M2 from [tester-recruiting.md](../../docs/release/tester-recruiting.md) (#261): the flyer for the store or league
visit and the join card for WhatsApp/Signal. German, because both go to German collectors.

| File | Use |
|---|---|
| `out/flyer-a6.pdf` | One A6 flyer (105 × 148 mm) for a print shop. White background, nothing runs to the edge, so no bleed is needed. |
| `out/flyer-a4-4up.pdf` | Four A6 flyers on one A4 sheet with thin cut lines, for printing at home. Print at 100 % ("actual size"). |
| `out/flyer-a6-preview.png` | Preview of the A6 flyer. |
| `out/join-card-1080x1920.png` | Join card to send in chats. |

The QR code points to `https://foilark.app/test`, a page that comes with #260. **Don't hand out the flyer until
that page is live** and the day-0 gates in the plan are met.

## Content

- Real app screens without card art (ADR 0009): the set 151 grid and the price block, cropped from
  `design/screenshots/pearl-97/light-sets.png` and `pearl-99/light-detail-top.png` (debug build on talos). Crops in
  `assets/`. Swap them for release-build screenshots once they exist.
- Pearl tokens and Schibsted Grotesk from the app (ADR 0014), the logo from `design/logo/`.
- Disclaimer: not affiliated with Nintendo or The Pokémon Company.

## Rebuild

```bash
node design/marketing/build.mjs
```

Edit `flyer.html` (text) and `flyer.css` (layout; sizes are in `cqw`, so one layout serves A6 and the join card).
The script takes playwright-core and Chromium from the `marketing-images` skill
(`MARKETING_IMAGES=<dir>` and `CHROME_PATH` override them). Check a new QR code with a phone before printing.

QR code: `segno` (`pip install segno`), error level M, ink `#26353C`:

```bash
segno --error m --border 0 --dark '#26353C' --light transparent \
  -o design/marketing/assets/qr-foilark-test.svg https://foilark.app/test
```
