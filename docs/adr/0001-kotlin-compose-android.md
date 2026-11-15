# 0001. Kotlin + Jetpack Compose, Android only

Status: accepted (2026-11-15)

## Context

Camera frame processing and on-device ML are the core of the app. Native CameraX and LiteRT/ML Kit give the most direct control and the best performance.

## Decision

minSdk 26. No iOS for now.

## Alternatives considered

Expo/React Native (camera and ML would need native modules anyway), Flutter (a new stack).
