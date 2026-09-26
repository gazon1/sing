---
status: accepted
date: 2026-09-26
---

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
| `Inbox` / `TaskFilter.Inbox` | `Inbox` | The default task collection |
| `scope` / `CoroutineScope` | `CoroutineScope` | Always spell out in code; `scope` is only for lambdas |

## Consequences

- `CONTEXT.md` must be updated whenever a new domain term is introduced in a feature ADR
- `singularity-todo-domain-glossary` skill provides a decision tree for "should this be a term?"
- Aliases reduce confusion when older code uses the old term — AGENTS.md will reference canonical names
