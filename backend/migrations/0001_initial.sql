-- Current prices and the TCGdex -> marketplace mapping (ADR 0004).
-- History lives in R2 as daily snapshots, not here: D1 only keeps the latest price per product.
-- Dates are ISO 8601 text (UTC).
-- Edited in place until the first remote deploy; after that, changes go into new migrations.

-- TCGdex sets the import has synced, per TCGdex language. International sets are read in English only: their
-- DE/FR/... prints share the English IDs. Other languages add only the sets English lacks (JP, DE-only sets).
CREATE TABLE tcgdex_sets (
  lang         TEXT NOT NULL,           -- TCGdex language the set was read in, e.g. 'en', 'ja'
  set_id       TEXT NOT NULL,           -- e.g. 'sv03.5', 'SV2a'
  card_count   INTEGER NOT NULL,        -- cardCount.total at the last sync; a change triggers a resync
  abbreviation TEXT,                    -- official set code, e.g. 'MEW'; matched against TCGCSV group abbreviations
  synced_at    TEXT NOT NULL,
  PRIMARY KEY (lang, set_id)
);
CREATE INDEX tcgdex_sets_by_abbreviation ON tcgdex_sets (abbreviation);

-- Cards the import knows about, seeded from TCGdex. One row per card, not per language:
-- EN/DE/FR/... prints of an international card share the TCGdex ID, JP cards have their own.
-- Rows are never deleted, so IDs the app has stored keep resolving.
CREATE TABLE cards (
  tcgdex_id  TEXT PRIMARY KEY,          -- e.g. 'sv03.5-025'
  lang       TEXT NOT NULL,             -- TCGdex language the card was read in (see tcgdex_sets)
  set_id     TEXT NOT NULL,             -- e.g. 'sv03.5'
  local_id   TEXT NOT NULL,             -- collector number as TCGdex writes it, e.g. '025'
  name       TEXT NOT NULL,             -- for debugging and matching only
  updated_at TEXT NOT NULL
);
CREATE INDEX cards_by_set ON cards (lang, set_id, local_id);

-- Result of the nightly mapping. A NULL ID means no product was found on that market.
-- Each market records where its ID came from: TCGdex's thirdParty IDs (the seed), set + number matching, or a
-- manual override. Overrides win; a match is kept while TCGdex has no ID of its own.
CREATE TABLE card_mapping (
  tcgdex_id         TEXT PRIMARY KEY REFERENCES cards (tcgdex_id),
  cardmarket_id     INTEGER,            -- Cardmarket idProduct
  tcgplayer_id      INTEGER,            -- TCGplayer productId (EN cards only)
  cardmarket_source TEXT CHECK (cardmarket_source IN ('tcgdex', 'match', 'override')),
  tcgplayer_source  TEXT CHECK (tcgplayer_source IN ('tcgdex', 'match', 'override')),
  updated_at        TEXT NOT NULL
);
CREATE INDEX card_mapping_by_cardmarket ON card_mapping (cardmarket_id);
CREATE INDEX card_mapping_by_tcgplayer ON card_mapping (tcgplayer_id);

-- Manual fixes for wrong or missing matches. They win over TCGdex and set+number matching.
-- A NULL column leaves that market's mapping to the import. Applied every night and after each card sync.
CREATE TABLE mapping_overrides (
  tcgdex_id     TEXT PRIMARY KEY,
  cardmarket_id INTEGER,
  tcgplayer_id  INTEGER,
  note          TEXT,
  created_at    TEXT NOT NULL
);

-- Latest Cardmarket price guide row per product, in EUR. The guide mixes all card languages.
-- The holo_* columns are the guide's '-holo' fields (the foil price, reverse holo for most Pokémon cards).
-- Only products some card maps to are stored.
CREATE TABLE cardmarket_prices (
  id_product  INTEGER PRIMARY KEY,
  low         REAL,
  trend       REAL,
  avg         REAL,
  avg1        REAL,
  avg7        REAL,
  avg30       REAL,
  holo_low    REAL,
  holo_trend  REAL,
  holo_avg    REAL,
  holo_avg1   REAL,
  holo_avg7   REAL,
  holo_avg30  REAL,
  price_date  TEXT NOT NULL             -- 'createdAt' of the price guide this row came from
);

-- Latest TCGCSV (TCGplayer) price per product and printing, in USD. Only mapped products are stored.
CREATE TABLE tcgplayer_prices (
  product_id  INTEGER NOT NULL,
  sub_type    TEXT NOT NULL,            -- TCGplayer subTypeName: 'Normal', 'Holofoil', 'Reverse Holofoil', ...
  low         REAL,
  mid         REAL,
  high        REAL,
  market      REAL,
  direct_low  REAL,
  price_date  TEXT NOT NULL,            -- TCGCSV's last-updated time of the dump this row came from
  PRIMARY KEY (product_id, sub_type)
);

-- One row per import job, for monitoring. Fanned-out child jobs (one TCGCSV group, one chunk of TCGdex cards)
-- only get a row when they fail for good, so a night has a handful of rows, not hundreds.
CREATE TABLE import_runs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  job         TEXT NOT NULL,            -- 'nightly', 'cardmarket', 'tcgcsv', 'tcgdex', 'snapshot' or a child job
  started_at  TEXT NOT NULL,
  finished_at TEXT,
  status      TEXT NOT NULL CHECK (status IN ('running', 'ok', 'failed', 'skipped')),
  detail      TEXT
);
CREATE INDEX import_runs_by_job ON import_runs (job, started_at);
