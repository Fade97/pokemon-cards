# 0010. UI direction: Index, with a value header

Status: accepted (2026-11-16)

## Context

Four clickable mockup directions were compared (`design/mockups/`): A Binder, B Slab, C Index and D Booster.

## Decision

Direction **C, Index**:

- The collector number (e.g. `006/165`) is the main type on the scan, review and detail screens.
- Each set is a grid with one slot per card, filled with the card's energy color when owned, so missing cards show at a glance.
- The palette is cool and light with ink-blue text; energy colors are used only for data. The typeface is Schibsted Grotesk.

From direction **B, Slab**: the collections screen opens with the collection's total value and its weekly change, and each set shows its value. The weekly change is **free**: it uses the daily price snapshots that are stored anyway, while charts and full history stay Pro (ADR 0007).

## Alternatives considered

- Binder: sets as 3×3 sleeve pages. A 165-card set takes 19 pages.
- Slab: cards in graded-card cases, value-first.
- Booster: playful pack-opening with stamp badges.
