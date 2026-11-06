---
title: Domain Glossary Policy
date: 2026-09-26
status: accepted
---

> **Superseded in part (2026-11-04):** the `TaskFilter.Inbox` filter and the AI tool's
> `inbox` selection are now `TaskFilter.NotDeferred` and `active_not_deferred`. The tab
> partition and the Inbox tab described here are unchanged. See
> [2026-11-04-inbox-names-the-unscheduled-tab.md](2026-11-04-inbox-names-the-unscheduled-tab.md).
>
> **Superseded in part (2026-11-06):** the `inbox` alias is gone — the wire no longer
> answers to that word, and a caller that sends it is refused rather than given a
> wider answer than the one it asked for. See
> [2026-11-06-one-name-one-question.md](2026-11-06-one-name-one-question.md).

# Domain Glossary Policy

## Context

The codebase uses domain terms inconsistently across features. For example, "agenda view" vs "saved agenda view" vs "agenda definition", "profile" vs "user", "tag group" vs "tag category". This creates confusion for both human readers and AI agents operating on the codebase.

## Decision

### Where definitions live

Domain term definitions live in `docs/CONTEXT.md`. This file is the **single source of truth** for domain vocabulary.

### Which terms to define

A term belongs in `CONTEXT.md` when it:

1. Appears in two or more separate features (cross-feature)
2. Has a specific meaning that differs from common English
3. Has aliases or synonyms that could cause confusion
4. Is referenced in an ADR and not self-evident from the ADR context

### Definition format

```markdown
## Term Name

**Also known as:** alias1, alias2

Brief definition (1-3 sentences). Explain what it is, not how it's implemented.

**Used in:** `FeatureName` (`path/to/file.kt`), `AnotherFeature`
```

### Process for adding terms

1. Author identifies a domain term during feature work
2. Author adds it to `docs/CONTEXT.md` with the above format
3. If the term has an alias, add "Also known as" with the old name
4. Author creates a PR referencing the term's first appearance

### Existing terms to canonicalize

| Old/Confusing | Canonical | Notes |
|---|---|---|
| `SavedAgendaView` | `SavedAgendaView` (keep) | Refers to the Room entity; stored user layout |
| `AgendaDefinition` | `AgendaDefinition` (keep) | The selector+evaluator config for an agenda |
| `Profile` / `User` | `Profile` | Multi-profile isolation; `User` is the Room entity |
| `TagGroup` / `TagCategory` | `TagGroup` | The grouping entity; Category is informal |
| `Inbox` / the old "inbox filter" | `Inbox` (the agenda tab, undated tasks only) and `TaskFilter.NotDeferred` (the code-level filter, live tasks that are not deferred) | The tab and the filter answer different questions; neither is "the default task collection" |
| `scope` / `CoroutineScope` | `CoroutineScope` | Always spell out in code; `scope` is only for lambdas |

## Consequences

- `CONTEXT.md` must be updated whenever a new domain term is introduced in a feature ADR
- `singularity-todo-domain-glossary` skill provides a decision tree for "should this be a term?"
- Aliases reduce confusion when older code uses the old term — AGENTS.md will reference canonical names
