---
title: "Extractable public software must come only from shared/ — the pro tree is FSL"
date: 2026-10-07
status: accepted
tags: [licensing, tooling, packaging]
---

# Extractable public software must come only from shared/ — the pro tree is FSL

## Context

There was an idea on the table: pull a KMP-shaped seam out of this repository and
publish it on pub.dev, the way a Flutter/KMP package ecosystem pays for itself.

The obstacle is not technical and it is not "we might not find a maintainer". It is the
licence split:

| Scope | Licence |
|---|---|
| repository root | **Apache-2.0** |
| `pro/` | **FSL-1.1-ALv2** (Functional Source License) |

FSL-1.1 is *not* an open-source licence. It grants use and modification rights, but it
requires that a **change is not published to a public package registry** — that is, the
whole point of FSL is to stop a derived work becoming available as an open package
before a commercial period elapses.

## Decision

**Anything extracted and published must consist only of code under `shared/`.** Anything
that depends on, imports, or links `pro/` cannot be published at all — and this is a
property of the *composition*, not of the individual files. A file in `shared/` that
imports from `pro/` cannot be separated from it without rewriting it, which is a
different and much larger piece of work than moving files.

Consequences that follow, and are worth stating because each of them looks harmless on
its own:

- A package may not depend on the app shell, the DI graph wiring, or the Android/desktop
  entry points — those live outside `shared/`.
- A package may not depend on anything that reaches `pro/` transitively, even if the
  dependency is "only" a feature flag or an entitlement check.
- The licence of the *extracted work* is Apache-2.0 and inherits `LICENSE`, including
  the `NOTICE` obligations for any MIT-derived material.

The candidates, in order of how cleanly they stand on their own:

| Candidate | Note |
|---|---|
| Nav3 graph decorators | Sits in `shared/`; the risk is any intent/route type drifting into `pro/` |
| `AutoCloseableCoroutineScope` as a testable-VM base | Already dependency-free; the cleanest extraction |
| A `PassThroughUseCase` rule | A detekt rule; the KMP-relevant part is the rule engine wrapper |
| The notes markdown pipeline | Has the most surface — verify it does not reach `pro/` before starting |

**Nothing is extracted now.** The stated rule is that a seam is extracted *after* it has
proved itself in production, not before, and no publication is planned in this change.

## Licensing of the borrowed ideas

Two findings informed this work: Gander (MIT) for the pure classifier separating in-app
rendering from platform handling, and Focora (MIT) for the spotlight overlay's shape
and its key-plus-version "show once" semantics.

Neither had code copied. MIT permits reuse provided the notice and licence text are
retained, which is compatible with Apache-2.0. Both are recorded in
`config/legal/provenance-registry.tsv` under category **`SPEC-COMPATIBLE`** — the ideas
were reimplemented in this project's own shape, and the registry records where the idea
came from so the next reader does not have to guess whether something is derived.

`NOTICE` does not need editing, because no third-party text was copied into the tree.

## Links

- `LICENSE` (Apache-2.0), `pro/` (FSL-1.1-ALv2)
- `config/legal/provenance-registry.tsv` — the two `SPEC-COMPATIBLE` rows
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/` — where an extracted
  reusable component would have to live
- ADR `2026-10-07-attachment-viewer-routing` — Gander prior art
