---
title: R22: Agenda Clean Architecture — deferred
date: 2026-09-26
status: archived
---

**Archived 2026-10-05.** This is a R22 agenda refactor inventory, not an architectural
decision. It left the decision corpus because its content is inventory
that nothing will migrate into a spec, and keeping it in `docs/decisions/`
made findings files look like decisions with pending status.


# R22: Agenda Clean Architecture — deferred

## Context

R22 proposes splitting `feature/agenda` into domain/data/presentation layers.

## Status

**Deferred.** The agenda feature is architecturally sound for current requirements. The `AgendaEvaluator` is already in `feature/agenda/domain/logic/`.

## What would unblock

- A new agenda view type that requires it
- A performance issue in the evaluator
- A shared agenda editor feature

## Consequences

- R22 remains in ARCHITECTURE.md backlog
