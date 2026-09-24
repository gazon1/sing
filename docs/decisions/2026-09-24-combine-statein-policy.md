---
status: accepted
date: 2026-09-24
tags: [vm, architecture, epic2, policy]
epic: refactor/techdebt-epic2
---

# combine+stateIn Policy

## Context

Two competing conventions for `StateFlow` in ViewModels existed in the codebase:

**Convention A (`testable-vm` skill):** Plain `MutableStateFlow` only. No `combine`, no `stateIn`. ViewModels own mutation directly.

**Convention B (`vm-intent-pattern` skill):** `combine + stateIn(WhileSubscribed(5_000))` for "pure read-through" VMs — those that only transform upstream flows without own intents or init-mutations.

Additionally, `scopeOverride` was recommended in `clean-architecture-audit` as a required pattern, but the canonical VM pattern explicitly forbids it.

## Decision

**Default:** Plain `MutableStateFlow`. No `combine`, no `stateIn` in ViewModels.

**Exception:** ONLY `combine + stateIn` is permitted for pure read-through VMs that:
1. Have **no intents** (sealed `*Intent` interface is empty or absent)
2. Have **no init-mutations** (no `scope.launch {}` in `init` that modifies state)
3. Use `stateIn(scope, sharingStarted, initialValue)` directly

**Canonical pure read-through VMs** (examples, current):
- `AgendaViewModel` — pure aggregation, no own intents
- `SavedAgendaListViewModel` — pure read-through from repository
- `ProjectsViewModel` — list-only, no own mutations

**Hardcoded `WhileSubscribed(5000)` is forbidden** as a hardcoded value. All VMs with `stateIn` MUST accept `sharingStarted: () -> SharingStarted` as a factory parameter (default `{ WhileSubscribed(5000) }`).

**`scopeOverride` must be ABSENT** from all ViewModels. Use the canonical 4-arg constructor pattern instead.

## Tests

VMs with `stateIn` receive `sharingStarted = { SharingStarted.Eagerly }` in tests and use `runCurrent()` (not `advanceUntilIdle()`) to process.

## Skills to Update

- `singularity-todo-testable-vm`: Add this ADR as reference
- `singularity-todo-vm-intent-pattern`: Add canonical exception for pure read-through VMs
- `singularity-todo-clean-architecture-audit`: Remove `scopeOverride` verification, replace with ABSENCE check

## Consequences

- `AgendaViewModel`, `SavedAgendaListViewModel`, `ProjectsViewModel`, `CalendarViewModel` remain as pure read-through with `combine+stateIn` — this is intentional and permitted
- All other VMs use plain `MutableStateFlow`
- `scopeOverride` usage anywhere in a ViewModel signals an audit is needed

## Links

- `singularity-todo-testable-vm`
- `singularity-todo-vm-intent-pattern`
- `singularity-todo-clean-architecture-audit`
- `docs/decisions/2026-09-24-pr1-tech-debt-audit-resolution.md`
