# 0008. Open models; dataset as manifest; CC0 user scans

Status: accepted (2026-11-15)

## Context

Recognition should be reproducible without re-hosting TPC card art or exposing user data.

## Decision

Weights, index and training code are published. The dataset is a manifest + download script. User scans are opt-in and contain only the card crop with no metadata; contributors explicitly consent to CC0, and a withdrawn scan is dropped from future releases.

## Alternatives considered

Full image dump; closed models; not redistributing user scans.
