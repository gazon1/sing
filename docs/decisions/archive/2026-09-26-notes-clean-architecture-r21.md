---
title: R21: Notes Clean Architecture — deferred
date: 2026-09-26
status: archived
---

**Archived 2026-10-05.** This is a R21 notes refactor inventory, not an architectural
decision. It left the decision corpus because its content is inventory
that nothing will migrate into a spec, and keeping it in `docs/decisions/`
made findings files look like decisions with pending status.


# R21: Notes Clean Architecture — deferred

## Context

R21 proposes splitting `feature/notes` into domain/data/presentation layers.

## Status

**Deferred.** The notes feature is functionally complete and stable. A clean architecture split would require significant refactoring with no immediate user benefit.

## What would unblock

- A new notes feature that requires it (e.g., shared notebooks)
- A performance issue traceable to the current structure
- An external contributor wanting to work on it

## Consequences

- R21 remains in ARCHITECTURE.md backlog
