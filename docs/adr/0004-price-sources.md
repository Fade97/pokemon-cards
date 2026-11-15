# 0004. Cardmarket price guide + TCGCSV, own mapping

Status: accepted (2026-11-15)

## Context

The Cardmarket API is closed to new apps, the TCGplayer API is closed, eBay offers active listings only and sold data is closed, and TCGdex has no JP prices.

## Decision

A nightly Cloudflare cron imports the Cardmarket price guide + product list and the TCGCSV dumps, then maps them to TCGdex IDs (TCGdex as seed, set + number matching, override table). The Cardmarket price mixes all languages and is labeled as such, with a deep link filtered by language. Currency follows the region and can be switched. Daily snapshots are stored from day one.

## Alternatives considered

The TCGdex pricing field; eBay Browse; scraping per-language Cardmarket prices.
