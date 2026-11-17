-- When the image of a contribution was stored (#14). Until then the row is only a claim on its id: a retry of the
-- same upload is told to come back later instead of being told it succeeded. Dataset releases take only rows with
-- `stored_at IS NOT NULL AND withdrawn_at IS NULL`.
ALTER TABLE contributions ADD COLUMN stored_at TEXT;
UPDATE contributions SET stored_at = created_at WHERE withdrawn_at IS NULL AND object_key <> '';
