-- False positives for training (#244). `kind` tells a card crop ('card', labeled with card_id/lang) from a scan the
-- user marked as not a card ('not_card': card_id and lang are '', only from debug builds with consent 'debug-1').
-- `predicted_*` is the scanner's best candidate when it was wrong: the card a not_card scan was taken for, or the
-- top match of a card scan the user corrected. NULL when the scanner was right or the client didn't say.
ALTER TABLE contributions ADD COLUMN kind TEXT NOT NULL DEFAULT 'card' CHECK (kind IN ('card', 'not_card'));
ALTER TABLE contributions ADD COLUMN predicted_card_id TEXT;
ALTER TABLE contributions ADD COLUMN predicted_lang TEXT;
