-- Opt-in scan contributions (#14, ADR 0008). The crop itself lives in R2 `CONTRIBUTIONS` at `object_key`.
-- Deliberately no IP address, user agent, device or account data: only what the dataset release needs.
CREATE TABLE contributions (
  id              TEXT PRIMARY KEY,        -- random UUID, returned to the app
  token_hash      TEXT NOT NULL,           -- SHA-256 (hex) of the withdraw token; the token itself is never stored
  card_id         TEXT NOT NULL,           -- confirmed TCGdex card id, e.g. 'sv03.5-025'
  lang            TEXT NOT NULL,           -- language of the scanned print (TCGdex code), e.g. 'de'
  consent_version TEXT NOT NULL,           -- consent text the contributor accepted, e.g. 'cc0-1'
  object_key      TEXT NOT NULL,           -- R2 key, e.g. 'scans/<id>.jpg'
  content_type    TEXT NOT NULL,           -- 'image/jpeg' or 'image/webp'
  size            INTEGER NOT NULL,        -- bytes after metadata stripping
  width           INTEGER NOT NULL,
  height          INTEGER NOT NULL,
  sha256          TEXT NOT NULL,           -- of the stored bytes
  created_at      TEXT NOT NULL,           -- ISO 8601 UTC
  withdrawn_at    TEXT                     -- set on withdrawal; the R2 object is deleted then and releases skip the row
);
CREATE INDEX contributions_for_release ON contributions (withdrawn_at, created_at);
