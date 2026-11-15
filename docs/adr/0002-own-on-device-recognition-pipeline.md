# 0002. Own on-device recognition pipeline

Status: accepted (2026-11-15)

## Context

Recognition must be local and free. CardCorpus reaches 87–97 % but weighs ~335 MB, its DINOv3 license is unclear for redistribution, and it has no KO. OCR alone fails on worn cards, promos and foreign scripts.

## Decision

YOLO-n detector + small fine-tuned embedding model (LiteRT) + kNN index + collector-number OCR to rerank. Training runs on the RTX 3080. OCR uses ML Kit first and an own open model later (needed for F-Droid). Unsure matches go to a review queue with a top-3 picker; the user always picks the variant.

## Alternatives considered

Adopt CardCorpus; OCR-only.
