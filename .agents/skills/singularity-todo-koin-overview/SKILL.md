---
name: singularity-todo-koin-overview
description: Router skill — index to all Koin DI skills. Use when registering bindings, debugging DI graph, or choosing the right DSL pattern.
---

# Koin DI Overview

This is a **router skill** — it points to the right skill for your task. No direct actions.

## See also: all Koin skills

| Skill | When to use |
|---|---|
| `singularity-todo-koin-dsl` | **Start here.** Canonical Koin 4.x DSL: `viewModelOf` vs `viewModel {}`, `koinViewModel` vs `koinInject`, 7-constructor limit |
| `singularity-todo-di-graph-testing` | Testing the DI graph — verifying bindings, resolving dependencies |
| `singularity-todo-clean-architecture-audit` | Verifying DI bindings follow layer rules |

## Quick decision tree

```
Registering a new dependency?
  → singularity-todo-koin-dsl

ViewModel not resolving / wrong scope?
  → `AGENTS.md` DI table

Koin runtime error (missing binding, etc)?
  → singularity-todo-di-graph-testing (run DiGraphTest)

DI bindings violating architecture?
  → singularity-todo-clean-architecture-audit (grep checks)
```

## Key rules (from `singularity-todo-koin-dsl`)

- **`viewModelOf(::Vm)`** — all no-param VMs (PREFERRED)
- **`viewModel { (p) -> Vm(get(), p) }`** — VMs with runtime parameters
- **`factory {}`** — NEVER for ViewModels (memory leak)
- **`koinViewModel()`** — Compose injection for no-param VMs
- **`koinViewModel { parametersOf(p) }`** — Compose injection with params
- **`koinInject()`** — non-ViewModel dependencies only
- **Constructor limit: 7 params** — use explicit `single { Vm(get(), ..., get()) }` for 8+
