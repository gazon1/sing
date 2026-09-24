# Documentation Process

This document describes how documentation is created, maintained, and reviewed in the Singularity Todo project.

---

## Decision Log (ADRs)

All significant architectural decisions are recorded as **Architecture Decision Records (ADRs)** in `docs/decisions/`.

**When to write an ADR:** See `singularity-todo-decisions-workflow` skill §"When to write a decision".

**Format:** `docs/decisions/YYYY-MM-DD-<slug>.md` with frontmatter (`title`, `date`, `tags`, `supersedes`). Blank template at `docs/templates/adr-template.md`.

**Maintainers:**
- Run `./scripts/refresh-decisions-digest.sh` after adding/changing ADRs
- Run `./scripts/check-broken-links.sh` after adding cross-ADR links
- Run `./scripts/check-kdoc-coverage.sh` periodically to monitor KDoc coverage

---

## KDoc Policy

`expect/actual`, `Repository` interfaces, and `ViewModel` classes must have KDoc. Enforced by `KDocOnContractRule` (detekt).

| Category | Required | Severity |
|---|---|---|
| `expect`/`actual` declarations | Yes | Warning |
| `*Repository` interfaces | Yes | Warning |
| `*ViewModel` classes | Yes | Warning |

KDoc must appear **immediately before** the declaration (no blank line).

---

## Documentation Review

Run `just docs-audit` (or `./scripts/refresh-decisions-digest.sh`) to check:

1. ADR frontmatter normalization (`title`, `date`, `status`, `tags`)
2. DIGEST.md freshness (auto-regenerated from ADR frontmatter)
3. Broken markdown links
4. Source tree drift (ARCHITECTURE.md §1 vs `print-source-tree.py`)

---

## Periodic Review

Every **quarter** or before a major release:

1. Run `just docs-audit` — fix all reported issues
2. Run `just lint` — fix all detekt/ktlint violations
3. Review DIGEST.md — confirm consequences bullets are still accurate
4. Check for outdated architecture references in ARCHITECTURE.md and AGENTS.md

---

## Adding New Documentation

- **Per-feature README** (`feature/<name>/README.md`): Required for new features. One paragraph minimum.
- **Module README**: Required for new Gradle modules.
- **ADR**: Required for any architectural decision (see §Decision Log).
- **Skill update**: Update AGENTS.md §"Ключевые скиллы" when adding new patterns.

---

## Anti-patterns

- **Don't write long narrative docs** — prefer short ADRs + DIGEST bullets
- **Don't duplicate AGENTS.md content** — AGENTS.md is the entry point; cross-refer from docs
- **Don't skip ADR for "obvious" decisions** — if it took discussion, write it down
- **Don't leave TODO without ADR reference** — use `TODO(#ticket)` format
