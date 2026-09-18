---
title: "SettingsContributor.apply renamed to process — clarity win"
date: 2026-09-22
tags: [settings, naming, kotlin-idioms]
---

## Context

`SettingsContributor` interface declared `suspend fun apply(intent: I)`. At call sites:

```kotlin
(aiContributor as? SettingsContributor<...>)?.apply(intent)
```

Reading `?.apply(intent)` looks like stdlib `apply { }` extension — a lambda-based scope function — which is confusing because `intent` is an argument, not a receiver.

## Decision

Rename `apply` → `process` in `SettingsContributor`, `AiSettingsContributor`, and `AiSettingsStore`. Update call sites in `SettingsViewModel` to `?.process(intent)`.

## Rationale

`process(intent)` reads unambiguously as "process this intent". It matches the existing `SettingsViewModel.processIntent(intent: SettingsIntent)` naming convention. The `apply` rename was cosmetic but eliminates a recurring reader-confusion point.

## Consequences

- `process(intent)` is the canonical name for contributor intent dispatch.
- `SurfaceController.apply(event)` is **not** changed — separate scope, separate task.
