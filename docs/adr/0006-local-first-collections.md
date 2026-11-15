# 0006. Local-first collections

Status: accepted (2026-11-15)

## Context

Users need no login for the core app, and personal data stays on the device.

## Decision

Room DB holds All Cards, automatic set collections (completion + language filter; JP sets kept separate) and custom collections. Each copy has quantity, variant, condition/grade and purchase price/date. JSON/CSV export is free. Sync arrives with Pro.

## Alternatives considered

Accounts with sync in v1; local only forever.
