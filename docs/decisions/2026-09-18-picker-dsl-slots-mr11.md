---
title: "ListPickerDsl gains header/footer slots; T bound relaxed to Any?"
date: 2026-09-18
tags: [dsl, ui-components]
---

## Context

`ListPickerSheet` needed to support multi-select confirmation (footer with "OK" button) and nullable sentinel keys (e.g. "None" option in parent/tag pickers). The existing API had no slot for these.

## Idea

1. **Full overload** — add `nullLabel`, `header`, `footer`, `selectedKeys`, `onItemsConfirmed`, `dismissOnConfirm` to `ListPickerSheet` data-class overload. Solves all cases but creates a mega-API.
2. **DSL-only slots** — add `header()`/`footer()` builders only to `ListPickerScope`. Data-class callers use a separate function. Caller implements multi-select with `remember { mutableStateOf(selected) }` + `footer { TextButton("OK") }`.
3. **Minimal extension** — add only `header` and `footer` as `ColumnScope` slots to both overloads. Change `T : Any` to `T : Any?`. Multi-select solved by caller-side `remember` + `footer` button.

## Decision

Option 3: minimal extension — `header`/`footer` as `ColumnScope` slots on both overloads, `T : Any?` bound.

## Rationale

The caller-side `remember { mutableStateOf(selectedKeys) }` approach for multi-select is idiomatic Compose and keeps the sheet stateless. A 6-parameter API overload is over-engineering for 3 callers. The nullable bound enables sentinel patterns (explicit `null` key for "None" options).

## Consequences

- `ListPickerScope<T>.header { }` and `footer { }` are the canonical way to add custom content above/below the item list.
- `T : Any?` means callers can use `null` as a key — filter at call site if needed.
- `ListPickerItem<T>.leading` slot already covers the `RowScope` customization need; no `trailing` slot added (not needed yet).
- **Never** add `dismissOnConfirm` or `onItemsConfirmed` parameters — multi-select batch-confirm is the caller's responsibility.
