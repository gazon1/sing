---
title: Selection mode publishes selectionTitle/onExitSelection instead of override slots
date: 2026-10-31
---

# Selection mode publishes selectionTitle/onExitSelection instead of override slots

## Context

The Android shell owns the single TopAppBar. Selection-mode screens (Agenda, Notes)
previously overrode the shell bar's title/back/colors/actions via `overrideShell` slots
on `ScreenActionsHolder`.

## Decision

Drop the override slots (`title`, `navigationIcon`, `colors`, `override`) and
`overrideShell` param. `ScreenActionsHolder` now carries `actions`, `selectionTitle`,
and `onExitSelection`. `TopAppBar` publishes these to the holder on Android. When
`selectionTitle` is set, the shell renders "N selected" with an exit-selection back
button and hides the search button.

## Consequences

- Simpler holder and TopAppBar; one mechanism for selection mode.
- Selection-mode screens pass `selectionTitle`/`onExitSelection` to `TopAppBar`.
- Desktop still renders its own bar from the `title`/`navigationIcon` params.