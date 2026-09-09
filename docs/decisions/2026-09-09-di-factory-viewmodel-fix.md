---
title: "DI bugfix: factory → viewModel for 4 VM registrations with runtime parameters"
date: 2026-09-09
tags: [koin, di, bugfix]
---

## Context

Post-audit of the `factory`-vs-`viewModel` registrations in `TasksDiModule.kt` and `ProjectsDiModule.kt` revealed that 4 ViewModel registrations with runtime parameters were still using `factory { (p) -> VM(...) }` — the pattern that existed before the 2026-09-06 koin-vm-scoping decision.

The affected registrations:
- `TasksDiModule.kt:97` — `TaskEditorViewModel { (initialDueDate) → ... }`
- `TasksDiModule.kt:123` — `TasksByProjectViewModel { (projectId) → ... }`
- `ProjectsDiModule.kt:38` — `ProjectEditorViewModel { (projectId) → ... }`
- `ProjectsDiModule.kt:49` — `ProjectDetailViewModel { (id) → ... }`

`factory {}` creates a **new instance on every `get()`**. On Android, a screen rotation triggers `get()` again via `koinViewModel { parametersOf(p) }`, which with `factory` produces a fresh VM — losing all UI state. The 2026-09-06 decision explicitly calls for `viewModel { (p) → ... }` for VMs with runtime parameters, but these 4 registrations were missed during the migration.

## Idea

1. **Leave `factory {}` as-is** — works for use cases and non-VM dependencies; only VMs need `viewModel`
2. **Replace all 4 `factory` → `viewModel`** — correct lifecycle scoping, no API change at call sites
3. **Defer to Phase 3 (TaskDetailDeps)** — refactor the constructor signature separately

## Decision

Replace `factory { (p) → VM(...) }` with `viewModel { (p) → VM(...) }` in all 4 registrations. No call-site changes required — `koinViewModel { parametersOf(p) }` works identically for both.

## Rationale

`factory` is appropriate for stateless, cheap-to-create objects (repositories, use cases). For ViewModels, `viewModel` binds the instance to `ViewModelStoreOwner`, preserving state across configuration changes (rotation, keyboard open/close). This is the documented Koin 4.x pattern per the 2026-09-06 decision.

The call site pattern `koinViewModel { parametersOf(p) }` is identical for both `factory` and `viewModel` registration forms — so the migration is a pure internal change with zero API surface impact.

## Consequences

- **4 VM registrations** (`TaskEditorViewModel`, `TasksByProjectViewModel`, `ProjectEditorViewModel`, `ProjectDetailViewModel`) now use `viewModel { (p) → ... }` instead of `factory { (p) → ... }`
- **State survives configuration change** on Android — rotation no longer resets these screens
- **No call-site changes** — `koinViewModel { parametersOf(...) }` works with both forms
- **Test impact** — tests that relied on a fresh VM instance per `get()` may need updating; prefer stateful testing over instance-fresh guarantees

## Links

- TasksDiModule.kt: lines 97, 123
- ProjectsDiModule.kt: lines 38, 49
- Previous decision: `2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`
