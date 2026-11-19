-- Quarantine for contributions (#135). Every upload starts as 'pending' and only an admin's approval
-- ('approved', via /v1/admin/contributions) lets a dataset release take it; 'rejected' deletes the image and keeps
-- the row, so a rejected scan is never released. Rows from before this migration were never looked at, so they
-- start as 'pending' too. Dataset releases take only
-- `stored_at IS NOT NULL AND withdrawn_at IS NULL AND review = 'approved'`.
ALTER TABLE contributions ADD COLUMN review TEXT NOT NULL DEFAULT 'pending'
  CHECK (review IN ('pending', 'approved', 'rejected'));
ALTER TABLE contributions ADD COLUMN reviewed_at TEXT;
-- The daily upload quota counts today's rows; the review list reads pending rows oldest first.
CREATE INDEX contributions_created ON contributions (created_at);
CREATE INDEX contributions_review ON contributions (review, created_at);
