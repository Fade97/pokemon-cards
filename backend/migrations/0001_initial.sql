-- Current prices and the TCGdex -> marketplace mapping (ADR 0004).
-- History lives in R2 as daily snapshots, not here: D1 only keeps the latest price per product.
-- Dates are ISO 8601 text (UTC).

-- Cards the import knows about, seeded from TCGdex. One row per card, not per language:
-- EN/DE/FR/... prints of an international card share the TCGdex ID, JP cards have their own.
CREATE TABLE cards (
  tcgdex_id  TEXT PRIMARY KEY,          -- e.g. 'sv03.5-025'
  set_id     TEXT NOT NULL,             -- e.g. 'sv03.5'
  local_id   TEXT NOT NULL,             -- collector number as TCGdex writes it, e.g. '025'
  name       TEXT NOT NULL,             -- for debugging and matching only
  updated_at TEXT NOT NULL
);
CREATE INDEX cards_by_set ON cards (set_id, local_id);

-- Result of the nightly mapping. A NULL ID means no product was found on that market.
CREATE TABLE card_mapping (
  tcgdex_id     TEXT PRIMARY KEY REFERENCES cards (tcgdex_id),
  cardmarket_id INTEGER,                -- Cardmarket idProduct
  tcgplayer_id  INTEGER,                -- TCGplayer productId (EN cards only)
  source        TEXT NOT NULL CHECK (source IN ('tcgdex', 'match', 'override')),
  updated_at    TEXT NOT NULL
);
CREATE INDEX card_mapping_by_cardmarket ON card_mapping (cardmarket_id);
CREATE INDEX card_mapping_by_tcgplayer ON card_mapping (tcgplayer_id);

-- Manual fixes for wrong or missing matches. They win over TCGdex and set+number matching.
-- A NULL column leaves that market's mapping to the import.
CREATE TABLE mapping_overrides (
  tcgdex_id     TEXT PRIMARY KEY,
  cardmarket_id INTEGER,
  tcgplayer_id  INTEGER,
  note          TEXT,
  created_at    TEXT NOT NULL
);

-- Latest Cardmarket price guide row per product, in EUR. The guide mixes all card languages.
-- The holo_* columns are the guide's '-holo' fields (the foil price, reverse holo for most Pokémon cards).
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

-- Latest TCGCSV (TCGplayer) price per product and printing, in USD.
CREATE TABLE tcgplayer_prices (
  product_id  INTEGER NOT NULL,
  sub_type    TEXT NOT NULL,            -- TCGplayer subTypeName: 'Normal', 'Holofoil', 'Reverse Holofoil', ...
  low         REAL,
  mid         REAL,
  high        REAL,
  market      REAL,
  direct_low  REAL,
  price_date  TEXT NOT NULL,
  PRIMARY KEY (product_id, sub_type)
);

-- One row per nightly run, for monitoring and the "prices as of" date.
CREATE TABLE import_runs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  started_at  TEXT NOT NULL,
  finished_at TEXT,
  status      TEXT NOT NULL CHECK (status IN ('running', 'ok', 'failed', 'skipped')),
  detail      TEXT
);
