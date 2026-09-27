---
title: R23: GenUI subsystem — applied
date: 2026-09-26
status: accepted
supersedes: 2026-09-26-notes-clean-architecture-r21
---

# R23: GenUI subsystem — applied

## Context

R23 required an ADR for `feature/genui/` — catalog, parser, render, schema.

## Decision

The GenUI subsystem is implemented in `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/`:
- `catalog/` — the component catalog
- `parser/` — GenUI DSL parser
- `render/` — renderer
- `schema/` — schema definitions
- `GenuiEngine.kt` — the engine

This was implemented in a previous epic and is in production. R23 is **closed as applied**.

## Consequences

- R23 removed from ARCHITECTURE.md backlog
