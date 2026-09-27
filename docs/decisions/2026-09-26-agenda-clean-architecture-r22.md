---
title: R22: Agenda Clean Architecture — deferred
date: 2026-09-26
status: deferred
---

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
