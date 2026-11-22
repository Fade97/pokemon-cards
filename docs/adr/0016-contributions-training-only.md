# 0016. User scans train the models but are not published

Status: accepted (2026-11-22). Replaces the "CC0 user scans" part of ADR 0008; open models and the reference manifest
stay as decided there.

## Context

ADR 0008 let contributors release their card crops under CC0 for a public dataset. CC0 only covers the contributor's
own rights; for a photo of a flat card that is at most the photo right (§ 72 UrhG). The artwork in every crop stays
© The Pokémon Company, so a public release of the crops would make TPC's artwork available to everyone, which ADR
0008 and ADR 0009 wanted to avoid. The TDM exception (§ 44b UrhG) covers copies for training, not public
redistribution. Contributions are consent-based (Art. 6(1)(a) GDPR), and consent under 16 needs a parent (Art. 8
GDPR, 16 in Germany).

## Decision

- Contributed crops are used only to train and test Foilark's recognition, on our own machines. They are never part
  of a public dataset release. Model weights, the reference index and training code stay open (ADR 0008).
- The consent text is version `train-1`. The backend still accepts `cc0-1` from older debug builds; those pictures are
  used the same narrower way. `ml/dataset/export_scans.py` releases only `cc0-1` rows, so `train-1` scans can't reach
  a release; #20 drops the scan part of the release builder.
- The consent screen has a required checkbox: "I am 16 or older, or a parent or guardian agreed."
- Withdrawal deletes the picture and keeps it out of later training; models trained before keep what they learned.
- Play Data safety: Photos collected (optional), not shared.

## Alternatives considered

Keep the public CC0 dataset with a note that the artwork is © TPC (the redistribution risk stays); turn
contributions off in release builds until the rights question is settled.
