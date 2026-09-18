---
title: "SettingsViewModel processIntent uses with(intent) stdlib receiver"
date: 2026-09-18
tags: [kotlin, viewmodel, settings]
status: accepted
---

## Context

`SettingsViewModel.processIntent` had 16 branches each accessing `intent.X` multiple times (`intent.value`, `intent.minutes`, `intent.hour`, `intent.viewId`). The repetition was noise without adding clarity.

## Idea

1. Apply `with(intent) { }` to all 16 branches — receiver changes from `SettingsUiState.Content` to the specific `SettingsIntent` subtype, making `intent.X` → `.X`.
2. Leave the 6 AI branches (`Ai.UpdateProvider`, `UpdateBaseUrl`, `UpdateModel`, `UpdateSystemPrompt`, `TestConnection`, `FetchModels`) unchanged — they use `apply(intent)` which is incompatible with `with(intent)`.
3. Leave `Ai.UpdateApiKey` unchanged — single property, no repetition.

## Decision

Apply `with(intent) { }` to 16 of 22 branches in `SettingsViewModel.processIntent`. The AI branches remain on `apply(intent)` semantics.

## Rationale

`with(intent)` eliminates `intent.` prefix noise in the most repetitive branches. The 6 AI branches are structurally different — they delegate to `SettingsContributor` via `apply`, not `updateState`. The risk of shadowing (`value`, `minutes`, etc. clashing with `Content` fields) was verified absent by grep.

## Consequences

- `with(intent) { }` is the canonical pattern for sealed-interface dispatch when branches share multi-property access patterns.
- `apply(intent)` branches must stay separate — they rely on receiver being the contributor, not the intent.
- SettingsViewModel is the last VM in the codebase with enough multi-property intents to benefit; other 7 VMs have single-property intents where the pattern yields no gain.
