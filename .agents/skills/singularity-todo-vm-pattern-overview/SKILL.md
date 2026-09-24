---
name: singularity-todo-vm-pattern-overview
description: Router skill — index to all ViewModel-related skills. Use when you need to understand the VM architecture, migration, testing, or lifecycle patterns.
---

# VM Pattern Overview

This is a **router skill** — it points to the right skill for your task. No direct actions.

## See also: all VM skills

| Skill | When to use |
|---|---|
| `singularity-todo-testable-vm` | **Start here.** Canonical VM pattern: 4-arg constructor, MutableStateFlow, AutoCloseableCoroutineScope |
| `singularity-todo-vm-migration-playbook` | Migrating an existing VM to canonical pattern (scopeOverride removal, factory param addition) |
| `singularity-todo-vm-lifecycle-addcloseable` | Making a Tier-1 VM auto-cancellable via `addCloseable(scope)` |
| `singularity-todo-vm-koin-scoping` | Koin scope decisions: `viewModelOf` vs `viewModel {}`, `koinViewModel` vs `koinInject` |
| `singularity-todo-vm-intent-pattern` | `onIntent` sealed interface, state-only state, one-shot events via Channel |
| `singularity-todo-koin-dsl` | Canonical Koin 4.x DSL for domain module registration |
| `singularity-todo-clean-architecture-audit` | Verifying layer boundaries and architecture compliance |
| `docs/decisions/2026-09-24-combine-statein-policy.md` | combine+stateIn policy: when allowed, when forbidden |

## Quick decision tree

```
Need to create a new VM?
  → singularity-todo-feature-scaffold

Need to test a VM?
  → singularity-todo-testable-vm + singularity-todo-test-helpers

VM has scopeOverride or old patterns?
  → singularity-todo-vm-migration-playbook

VM leaks coroutines / doesn't cancel on screen leave?
  → singularity-todo-vm-lifecycle-addcloseable

DI bindings wrong / Koin errors?
  → singularity-todo-vm-koin-scoping + singularity-todo-koin-dsl

VM has complex intents / state management?
  → singularity-todo-vm-intent-pattern

Need to audit a feature's architecture?
  → singularity-todo-clean-architecture-audit
```
