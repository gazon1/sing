---
title: Doc & skills hygiene sprint — results
date: 2026-09-27
status: accepted
tags: [sprint, hygiene, documentation, skills, tooling]
---

# Doc & skills hygiene sprint — results

**Branch** `sprint/doc-skills-v2` · **Scope** 180 files, +3694/−2268, 12 commits.

## What the sprint was

A documentation and skills audit turned up stale references, one directly contradicted
skill, rules that existed only in prose, and a detekt rule that had never run. Rather than
fix findings one at a time, the work was batched by root cause — most of the ~100 stale
references traced back to a handful of refactors whose documentation was never updated.

## Results

| Metric | Before | After |
|---|---|---|
| `AGENTS.md` | 387 lines | 211 |
| ADR entries rendering as `_(no title)_` in the digest | 106 | 0 |
| ADRs with broken frontmatter (body swallowed by YAML) | 3 | 0 |
| Skills with no YAML frontmatter | 5 (silently skipped) | 0 |
| Custom detekt rules registered and running | 8 of 9 | 9 of 9 |
| Oversized skills (>500 lines) | 2 (+5 near the limit) | 0 (+5 in backlog) |
| Directly contradicted rule (viewModelOf) | 1 | 0 |
| `check.sh` failing on a detekt violation | no | yes |
| Stale file references in live docs | ~100 | 34 (all in skill prose) |
| Docs invariants enforced by a script | 1 (frontmatter presence) | 5 |

## Three findings worth keeping

**A rule that is only documented is a rule nobody follows.** `NoFactoryViewModelRule`
existed with unit tests, an ADR credited it with catching a real
`factory<CalendarSyncViewModel>` registration — and its provider was never listed in
`META-INF/services/dev.detekt.api.RuleSetProvider`, so detekt never loaded it. The catch
came from a human. The skill that covers authoring detekt rules already listed "provider
not in ServiceLoader" as its first common mistake; knowing the failure mode did not
prevent it, which is the argument for the automated check rather than the note.

**The most-trusted gate was the one that did not gate.** `check.sh` printed "detekt
(report-only)" and had a `|| { echo }` with no `exit 1` — while `ignoreFailures` had been
`false` since PR 3.3. Four documents repeated the same stale claim. A green `./check.sh`
meant nothing about lint.

**Retiring a skill needs the code checked, not the argument.** `singularity-todo-vm-koin-scoping`
argued at length that `viewModelOf` must never be used. Measuring the tree settled it in a
minute: `SharingStarted` appears nowhere in the DI modules, 32 registrations use explicit
`viewModel { }` and exactly 1 uses `viewModelOf`, and only 1 ViewModel has a second
constructor. The ban was a rule for a problem this codebase does not have.

## What shipped

**New enforcement** (all in `just docs-audit`):

- `scripts/check-doc-sizes.py` — `AGENTS.md` ≤ 250, `SKILL.md` ≤ 500, `description` ≤ 1024
  chars, `DIGEST.md` ≤ 1500. Fails the build.
- `scripts/check-doc-dead-refs.py` — classifies every backticked path in docs, skills and
  KDoc as dead / drifted / historical, where *historical* means "inside a supersedure
  banner", so a retired ADR can still name what it described.
- `check-skill-frontmatter.sh` — no longer skips a skill that lacks frontmatter; that is
  now an error, because such a skill is invisible to the loader.
- `scripts/regen-skills-catalog.sh` + `just docs-regen` — generates
  `docs/SKILLS-CATALOG.md` (91 skills). Generated, not maintained.
- `just tcheck-evals` — the workflow evals existed and were run by hand.

**New ADRs:** `2026-09-27-di-module-aggregator-narrative` (Modules.kt is an aggregator, not
the source of truth), `2026-09-27-vm-koin-scoping-retired`,
`2026-09-27-doc-and-skills-sprint-findings` (the running triage log — every post-phase
review appended to it instead of spawning a new file).

**Skill ecosystem:** the plan called for 11 new meta-skills. Deduplication against the
existing 89 showed 4 were already covered and 4 more were one workflow sliced three ways.
Shipped instead: 2 new skills (`monthly-doc-audit`, `scheduled-maintenance`), 5 additions
to skills that already owned their topic, and 2 oversized skills split into a router plus
13 leaf files.

## Left behind, on purpose

`DIGEST.md` is 1865 lines against a 1500 budget, and 34 dead references remain in skill
prose — mostly files that were designed in a document and never written. Both need a
decision per item, not a mechanical fix, and both are catalogued in the findings ADR. The
dead-ref check will keep `just docs-audit` red until they are resolved, which is the
intended pressure: the remaining references are all "this design was never built", and
each should either become code or stop being described as if it exists.

The process is now a ritual rather than a one-off: a post-phase review after every phase
of work, appending to one findings ADR, with `./check.sh` + `just tcheck-evals` +
`just docs-audit` as the gate.

## Links

- `docs/decisions/2026-09-27-doc-and-skills-sprint-findings.md` — per-phase triage log
- `docs/decisions/2026-09-27-di-module-aggregator-narrative.md`
- `docs/decisions/2026-09-27-vm-koin-scoping-retired.md`
- `docs/SKILLS-CATALOG.md` — generated skill index
- `PROGRESS.md` — epic journal
