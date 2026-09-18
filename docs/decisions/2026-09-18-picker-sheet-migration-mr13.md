---
title: "TaskAiBottomSheet migrates to ListPickerSheet; KindSheet and BacklinksSheet deferred"
date: 2026-09-18
tags: [ui-components, picker, migration]
status: accepted
---

## Context

MR13 aimed to migrate 4 simple picker sheets (`TaskAiBottomSheet`, `KindSheet`, `BacklinksSheet`, `ChildProjectsSheet`) to `ListPickerSheet`. `TaskAiBottomSheet` was straightforward. The other 3 required closer inspection.

## Decision

**Migrated:**
- `TaskAiBottomSheet`: Rewrite as `ListPickerSheet<TaskAiAction>`. Removed manual `sheetState`, `LaunchedEffect`, `rememberCoroutineScope`, `scope.launch { sheetState.hide() }`. Net -25 lines.

**Deferred (not migrated):**
- `KindSheet`: Uses a horizontal 2-FilterChip row with `Arrangement.spacedBy`. `ListPickerSheet` renders a vertical list with checkmark. The visual layout change is a regression — unacceptable without a row-variant of `ListPickerSheet`.
- `BacklinksSheet`: Uses `ListItem` with `headlineContent` + `supportingContent` (rich snippet rendering, `TextOverflow.Ellipsis`). `ListPickerSheet` supports only `label: String` + `subtitle: String?`. The content richness is lost.

## Rationale

`ListPickerSheet` is designed for label/subtitle item lists. Any sheet that needs custom item layout (chips, rich text, custom leading) is a regression when migrated. The rule: migrate only when the target UI is strictly simpler than the source, or identical.

## Consequences

- `ListPickerSheet` is appropriate for: enum pickers, ID/name pairs, flat lists with optional subtitle.
- **Never** migrate a sheet to `ListPickerSheet` if it uses `FilterChip`, `ListItem` with rich content, or custom item layouts.
- `KindSheet` can be migrated once a row-variant or chip-variant of `ListPickerSheet` exists.
- `BacklinksSheet` needs richer item rendering support (custom item composable slot) before migration is viable.
