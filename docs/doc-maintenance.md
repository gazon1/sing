# Documentation Maintenance Policy

This file defines when and how to write and update documentation for the Singularity Todo project.

---

## ADR Policy

### When to write an ADR

Write an ADR when:
- A new architectural decision is made (new port, new pattern, new platform)
- An existing pattern is changed or deprecated
- A technical approach was tried and rejected (record the rejection reason)
- A cross-cutting concern is introduced (DI, serialization, sync, etc.)

### When NOT to write an ADR

- Routine refactoring that doesn't change APIs or contracts
- Bug fixes with no architectural implication
- Pure code formatting or style changes
- Adding new features that follow existing patterns

### ADR template

```yaml
---
title: "<short descriptive title>"
date: 2026-MM-DD
status: accepted  # or: deferred | superseded | open
tags: [tag1, tag2]
supersedes: YYYY-MM-DD-slug  # optional
superseded-by: YYYY-MM-DD-slug  # optional
---
## Context

## Idea

## Decision

## Rationale

## Consequences

## Links
```

### Status values

| Status | Meaning |
|---|---|
| `accepted` | Decision is implemented and current |
| `deferred` | Decision is postponed; revisit by date or trigger in Consequences |
| `superseded` | Replaced by another ADR; must have `superseded-by` |
| `open` | Under active discussion or implementation — avoid new `open` ADRs |

### Rules

1. **Single source of truth**: DIGEST.md is auto-generated. Never edit it by hand — run `scripts/refresh-decisions-digest.py`
2. **No orphaned `superseded-by`**: If an ADR has `superseded-by`, the target ADR must exist
3. **No `open` ADRs older than 30 days**: If `open`, either resolve or defer it
4. **All ADRs must have `status:`**: Run `scripts/normalize-adr-frontmatter.sh --dry-run` to check

---

## KDoc Policy

### Minimum KDoc requirements by category

| Category | Class-level KDoc | Method-level KDoc |
|---|---|---|
| `expect/actual` declarations | Required | Required |
| Public repository interfaces | Required | Required |
| Public port interfaces | Required | All methods |
| ViewModels | Required | No |
| Use cases with business rules | Required | For public methods |
| Data class / sealed class state | No | No |

### KDoc style

- **"Why, not what"**: describe the purpose and semantics, not the implementation
- **No `@author` / `@version`**: legacy Javadoc baggage
- **Use `@see`** for cross-references to related types
- **Inline code** in backticks: `ClassName.methodName()`
- **No restating the obvious**: `/** Returns the list size. */` is noise

### Good example

```kotlin
/**
 * Port for filesystem operations — platform boundary.
 *
 * JVM: backed by `java.io.File`. Android: backed by `context.filesDir`.
 * All operations are best-effort atomic — see `writeBytes` for crash-safety notes.
 */
interface FileSystem {
    /**
     * Reads entire file into memory. Throws `NoSuchFileException` if not found.
     */
    suspend fun readBytes(path: String): ByteArray
}
```

---

## Source tree documentation

`ARCHITECTURE.md` §1 (package maps) must be kept in sync with actual code. Run:

```bash
python3 scripts/print-source-tree.py > /tmp/source-tree.md
# diff /tmp/source-tree.md against ARCHITECTURE.md §1
```

This is automated by `just docs-audit`.

---

## OpenSpec Change Policy

Behavioural changes follow the OpenSpec artifact lifecycle (`proposal → specs → design → tasks →
apply → verify → archive`). The `openspec-workflow` skill (`singularity-todo-openspec-workflow`)
governs the full process. This section resolves conflicts between OpenSpec and other policies.

### Resolution rules

| Conflict | Resolution |
|---|---|
| ADR vs spec | Both are written: ADR for the architectural decision, spec for the behavioural surface. Never duplicate ADR reasoning into a spec. |
| `skip_specs` vs `doc-maintenance.md` | `skip_specs` in `openspec/config.yaml` takes precedence for OpenSpec changes. Bug-fix PRs, dependency bumps, doc-only changes, and use cases that follow an existing pattern are exempt from OpenSpec even if they touch behaviour. |
| OpenSpec `verify` gate vs `just docs-audit` | Both must pass. `openspec validate --all --json --strict` is the verify gate; `just docs-audit` runs the doc hygiene checks. Neither waives the other. |
| Skill reference vs spec | Specs must not describe Kotlin/Koin/Compose/ViewModel/DAO names. Skills may describe them. The `check-doc-dead-refs.py` detector 8 (`skill-dangling-symbol`) guards skills, not specs. |
| `design.md` requirement | Required only for cross-cutting changes, new architecture, new data models, or security-sensitive surfaces. Not required for straightforward feature specs. |

### When a spec is required vs a skip

Use the `skip_specs` table in `openspec/config.yaml`. A change that modifies observable behaviour
requires a spec; a change that doesn't (bug fix, test-only, rename, dependency update, doc-only)
does not. Architecture changes always require both an ADR and a spec.

### Spec naming

- `proposal.md` — one coherent change, no duplication of existing ADR content
- `spec.md` — observable behaviour only; SHALL/MUST requirements; every requirement has ≥1 scenario
- `design.md` — architecture and risk only; references ADR by slug, never copies it
- `tasks.md` — one task per module; every behavioural change has a test; no speculative refactoring

```bash
# Full audit (dry-run)
just docs-audit

# Normalize frontmatter (apply changes)
./scripts/normalize-adr-frontmatter.sh --apply

# Refresh DIGEST
./scripts/refresh-decisions-digest.sh

# Check for stale open/deferred ADRs
grep -r "status: open\|status: deferred" docs/decisions/
```

---

## Top-level docs

| File | Purpose | Who maintains |
|---|---|---|
| `AGENTS.md` | Agent cheatsheet (auto-loaded) | All contributors |
| `ARCHITECTURE.md` | Full design doc | All contributors |
| `README.md` | Project intro + quickstart | All contributors |
| `docs/decisions/DIGEST.md` | Auto-generated ADR index | `refresh-decisions-digest.sh` |

Update these when:
- New feature adds a new package or expect/actual declaration
- DI pattern changes
- Build commands change
