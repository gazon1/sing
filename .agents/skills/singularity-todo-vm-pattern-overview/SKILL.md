---
name: singularity-todo-vm-pattern-overview
description: Router skill — index to all ViewModel-related skills. Use when you need to understand the VM architecture, migration, testing, or lifecycle patterns.
---

# VM Pattern Overview

This is a **router skill** — it points to the right skill for your task. No direct actions.

## See also: all VM skills

| Skill | When to use |
|---|---|
| `singularity-todo-mvi-framework` | **Start here.** MviViewModel base class, EventBus, IntentActions, DraftState |
| `singularity-todo-testable-vm` | Canonical VM pattern with MviViewModel: scope injection, IntentActions, testable tests |
| `singularity-todo-vm-migration-playbook` | Migrating an existing VM to MviViewModel (hand-rolled → framework) |
| `singularity-todo-vm-lifecycle-addcloseable` | Making a Tier-1 VM auto-cancellable via `addCloseable(scope)` |
| `singularity-todo-vm-intent-pattern` | `onIntent` sealed interface, routing vs domain intent separation, `_latestTask` cache |
| `singularity-todo-koin-dsl` | Canonical Koin 4.x DSL for domain module registration |
| `singularity-todo-clean-architecture-audit` | Verifying layer boundaries and architecture compliance |
| `docs/decisions/2026-09-24-combine-statein-policy.md` | combine+stateIn policy: when allowed, when forbidden |

## AI Action Patterns

| Skill | When to use |
|---|---|
| `singularity-todo-ai-action-registry-pattern` | Adding a new AI action slot (nullable lambda + controller + wiring checklist) |
| `singularity-todo-note-ai-multi-op` | Note AI multi-op: 4 tools, 4 use cases, NoteAiController, end-to-end wiring |
| `singularity-todo-cycle-detector-pattern` | Adding cycle detection to any graph-like relation (tasks, subtasks, projects, folders) |

## Detekt Custom Rules

| Skill | When to use |
|---|---|
| `singularity-todo-detekt-rules-authoring` | Writing and registering custom detekt rules (inner-class provider pattern, AST visitors) |
| `singularity-todo-detekt-workflow` | Running detekt, auto-fix, baseline management |

## Sheet + UI Patterns

| Skill | When to use |
|---|---|
| `singularity-todo-sheet-extraction` | Extracting inline sheets to separate files; routing intents for navigation from sheets |
| `singularity-todo-test-helpers` | FakeRepositories, FakeTextGen, awaitState, testVm factory |

## Quick decision tree

```
Need to create a new VM?
  → singularity-todo-mvi-framework  ← READ FIRST
  → singularity-todo-feature-scaffold

Need to test a VM?
  → singularity-todo-testable-vm + singularity-todo-test-helpers

VM has hand-rolled MVI (mutable state + Channel)?
  → singularity-todo-vm-migration-playbook  ← migrate to MviViewModel

VM leaks coroutines / doesn't cancel on screen leave?
  → singularity-todo-vm-lifecycle-addcloseable

DI bindings wrong / Koin errors?
  → `AGENTS.md` DI table + singularity-todo-koin-dsl

VM has complex intents / state management?
  → singularity-todo-vm-intent-pattern

Adding AI action to a feature?
  → singularity-todo-ai-action-registry-pattern

Note AI actions not working / debugging wiring?
  → singularity-todo-note-ai-multi-op

Adding cycle detection to a domain relation?
  → singularity-todo-cycle-detector-pattern

Inline sheet needs to be extracted?
  → singularity-todo-sheet-extraction

Need to audit a feature's architecture?
  → singularity-todo-clean-architecture-audit
```
