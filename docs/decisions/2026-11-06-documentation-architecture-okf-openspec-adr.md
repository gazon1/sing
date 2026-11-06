---
title: "Documentation architecture: Spec Kit / OpenSpec / OKF / ADR / docs"
date: 2026-11-06
tags: [documentation, knowledge, okf, openspec, adr]
status: accepted
---

## Context

Documentation can look current while no longer matching code. We need one canonical place per information type, machine-checkable links between knowledge and sources, and a visible staleness status for agents.

## Decision

- Product SDD stays in Spec Kit (`specs/`).
- Engineering SDD stays in OpenSpec (`openspec/changes/`).
- Current architecture is described in `knowledge/` using an OKF-compatible profile (no independent OKF CLI available — Variant B).
- Decision rationale lives in ADRs (`docs/decisions/`), not architecture copies.
- Human instructions live in `docs/` (how-to, tutorials, reference).
- Agent workflow lives in skills + `AGENTS.md`.

Knowledge concepts use Git blob OIDs for `source_state` and a normalized `body_state` (SHA-256 over frontmatter minus state fields plus body). Status is computed as verified/generated/body_stale/sources_stale/stale/unstamped.

`canonical_for` IDs are validated against `knowledge/facts.yaml`.

## Consequences

- Agents must run `status`/`show` before relying on a concept.
- `stamp` is forbidden on dirty sources; `stamp --verified` requires `--by human:...` and confirmation.
- Reverse index only covers explicitly listed sources; blind spots measured via advisory same-package scope.
- Conformance is relative to the project profile, stated honestly here.

## Links

- Plan: `phase-0-audit.md`
- Checker: `scripts/documentation/check.py`
- Conventions: `docs/how-to/documentation-system.md`