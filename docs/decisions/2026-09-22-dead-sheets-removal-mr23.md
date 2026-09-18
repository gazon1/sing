---
title: "Delete orphaned sheets and picker VMs — 700 lines dead code removed"
date: 2026-09-22
tags: [cleanup, dead-code]
---

## Context

grep audit revealed 6 sheets and 2 picker VMs with zero callers in the codebase:

- `TaskDetailSheets.kt` (4 sheets: ConfirmArchive, ConfirmDelete, ReminderPicker, Kind) — dead since TaskDetailScreen only wires `TaskEditorSheet` sealed interface.
- `AttachmentSheet.kt` — replaced by `AttachmentPlaceholderSheet` in ProjectDetailScreen.
- `ChecklistEditorSheet.kt` — no callers anywhere.
- `ProjectPickerSheet` + `ProjectPickerViewModel` — registered in DI, never injected.
- `TagPickerSheet` + `TagPickerViewModel` — registered in DI, never injected.
- Corresponding jvmTests for the orphaned VMs.

## Decision

Delete all orphaned files and their DI registrations (`Modules.kt`, `ProjectsDiModule.kt`). ~700 lines removed, 2 test files removed, 2 DI registrations trimmed.

## Rationale

Pre-built components without callers are premature abstractions. `ProjectPickerSheet`/`TagPickerSheet` were built "for future use" but created maintenance burden with no benefit. DI registrations for VMs that are never injected still consume Koin's registry and cognitive overhead.

## Consequences

- When a real use case appears (e.g. TaskDetailViewModel needs a project picker), implement it from scratch using `ListPickerSheet` + `DialogState` + caller-side state hoisting — not by resurrecting the deleted code.
- **Never** register a VM in DI without at least one concrete consumer.
- **Never** pre-build UI components without a known caller.
