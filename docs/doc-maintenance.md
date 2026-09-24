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

1. **Single source of truth for auto-content**: DIGEST.md is auto-generated between `<!-- AUTO-GENERATED-START -->` and `<!-- AUTO-GENERATED-END -->` markers. Never edit content between markers — run `scripts/refresh-decisions-digest.py`. Hand-written narratives may be placed after `<!-- AUTO-GENERATED-END -->` and are preserved.
2. **No orphaned `superseded-by`**: If an ADR has `superseded-by`, the target ADR must exist
3. **No `open` ADRs older than 30 days**: If `open`, either resolve or defer it
4. **All ADRs must have `title` and `status:`**: Run `scripts/backfill-adr-title.py --dry-run` and `scripts/normalize-adr-frontmatter.sh --dry-run` to check

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

## Running maintenance checks

```bash
# Full audit
just docs-audit

# Normalize frontmatter (status, tags, title)
./scripts/normalize-adr-frontmatter.sh --apply
./scripts/backfill-adr-title.py --apply   # add title to files missing it

# Refresh DIGEST (preserves content outside <!-- AUTO-GENERATED-START -->...<!-- AUTO-GENERATED-END -->)
./scripts/refresh-decisions-digest.sh

# Check for stale open/deferred ADRs
grep -r "status: open\|status: deferred" docs/decisions/

# KDoc coverage (warning, not gate)
./scripts/check-kdoc-coverage.sh

# Source-tree drift
./scripts/check-source-tree-drift.sh

# Broken links
./scripts/check-broken-links.sh
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
