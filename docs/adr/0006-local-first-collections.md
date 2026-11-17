# 0006. Local-first collections

Status: accepted (2026-11-15)

## Context

Users need no login for the core app, and personal data stays on the device.

## Decision

Room DB holds All Cards, automatic set collections (completion + language filter; JP sets kept separate) and custom collections. Each copy has quantity, variant, condition/grade and purchase price/date. JSON/CSV export is free. Sync arrives with Pro.

Schema rules (#29, before the first release):

- Copies and collections have a random **uuid** and **updatedAt** next to the local row id. Export/import and sync
  match rows by uuid; the row id never leaves the device.
- A copy's print is **finish** (normal/holo/reverse holo/other) plus **edition** (unlimited/1st edition/shadowless).
- Cached prices are kept per card, source and printing. `PriceVariant.candidates` is the one mapping from a copy's
  finish and edition to the prices that value it; editions a market doesn't price get no price, not a wrong one.
- **Catalog rows are never deleted.** A card or set that drops out of a language pack is marked `retired`, so owned
  copies always keep their card. The foreign keys have no ON DELETE action, so a delete fails instead.
- Deletes of copies are hard deletes for now. Sync (#16) adds tombstones if it needs them; that's an additive
  migration.
- Schema changes after the first release bump the version and add a migration; `SchemaMigrationTest` runs every
  exported schema through all migrations.

No app server stores collections. The collection DB is included in the user's own Android backup and device transfer (`data_extraction_rules.xml`), so changing phones does not lose it.

## Alternatives considered

Accounts with sync in v1; local only forever.
