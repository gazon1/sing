---
name: singularity-todo-compose-overview
description: Router skill — index to all Compose UI skills. Use when building screens, shared components, navigation, or UI patterns.
---

# Compose UI Overview

This is a **router skill** — it points to the right skill for your task. No direct actions.

## See also: all Compose UI skills

| Skill | When to use |
|---|---|
| `singularity-todo-shared-ui-components` | `SettingsSection`, `ResultDialog`, `Slot` API, `TaskEditorContent` |
| `singularity-todo-pure-formatters` | Pure formatting helpers (dates, numbers, text) — no Compose dependency |
| `singularity-todo-ui-event-vs-state` | One-shot events vs continuous state in ViewModels |
| `singularity-todo-cross-feature-navigation` | Nav3 nested graphs, entry providers, route patterns |
| `singularity-todo-task-callback-groups` | Swipe actions, multi-select, task group interactions |
| `singularity-todo-notes-ux-patterns` | Rich text editor, note-specific patterns |
| `singularity-todo-multi-select` | Multi-select pattern in lists |
| `singularity-todo-swipe-actions` | Swipe-to-complete/delete patterns |
| `singularity-todo-preview-with-koin` | `@Preview` without Koin — VM-as-parameter pattern |
| `singularity-todo-nav3-nested-graphs` | Nested navigation graphs and route management |
| `singularity-todo-nav3-savedstate` | Android vs JVM back stack handling |

## Quick decision tree

```
Building a new screen?
  → singularity-todo-feature-scaffold (screen + VM together)

Need shared UI component?
  → singularity-todo-shared-ui-components

Formatting without Compose?
  → singularity-todo-pure-formatters

Navigation / routing?
  → singularity-todo-cross-feature-navigation + singularity-todo-nav3-nested-graphs

Events vs state confusion?
  → singularity-todo-ui-event-vs-state

Swipe or task interactions?
  → singularity-todo-task-callback-groups + singularity-todo-swipe-actions

Preview not working with Koin?
  → singularity-todo-preview-with-koin
```
