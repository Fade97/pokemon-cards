# 0010. UI direction: Index, with a value header

Status: accepted (2026-11-16). Palette and chrome replaced by [ADR 0014](0014-ui-direction-pearl.md) (Pearl);
the collector-number hierarchy, the slot grid and the value header stay. Weekly change split into a free direction and
Pro amounts on 2026-11-19 (#186, amendment below).

## Context

Four clickable mockup directions were compared (`design/mockups/`): A Binder, B Slab, C Index and D Booster.

## Decision

Direction **C, Index**:

- The collector number (e.g. `006/165`) is the main type on the scan, review and detail screens.
- Each set is a grid with one slot per card, filled with the card's energy color when owned, so missing cards show at a glance.
- Set detail switches between **Compact** (the numbered slot grid) and **Cards** (small card images; missing cards stay in place in black and white). Images come from the TCGdex CDN (low-res thumbnails, cached, ADR 0009); cards without an image, for example many JP cards, get a placeholder showing their number.
- The palette is cool and light with ink-blue text; energy colors are used only for data. The typeface is Schibsted Grotesk.

From direction **B, Slab**: the collections screen opens with the collection's total value and its weekly change, and each set shows its value. The weekly change is **free**: it uses the daily price snapshots that are stored anyway, while charts and full history stay Pro (ADR 0007).

## Amendment (2026-11-19, #186): free direction, Pro amounts

#173 first shipped the weekly change as Pro only, because the app got history only from the Pro history API (ADR 0012). Fabian decided on 2026-11-19 to split it:

- **Free:** the **direction** of the last 7 days on set rows and copy lists: an arrow plus "Up / Down / No change in 7 days", no amount, no percentage. The nightly import writes each printing's price of a week ago into the free price files (`weekAgo`, from the snapshots, no series), and the app compares it with today's price.
- **Pro:** the amount and percentage of the change (`+1,20 € (+12 %) in 7 days`), the portfolio chart, card price charts and full history, as before.
- Both use the same rules (`WeeklyPriceChange`): one market per list, so EUR and USD never mix; each copy followed in the printing its current price comes from; no change when the copies with a week-old price hold less than half of the list's value, or when the prices are more than 3 days old.
- The value header shows no weekly change for free users yet; the direction is on the set rows. A free week-ago value of the whole collection would be possible with the same data if wanted later.

## Alternatives considered

- Binder: sets as 3×3 sleeve pages. A 165-card set takes 19 pages.
- Slab: cards in graded-card cases, value-first.
- Booster: playful pack-opening with stamp badges.
