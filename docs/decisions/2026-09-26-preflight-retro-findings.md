---
title: Pre-flight retro findings — phase 0 retrospective
date: 2026-09-26
status: accepted
tags: [retro, tech-debt, preflight, detekt, docs]
epic: refactor/techdebt-preflight
---

# Pre-flight Retro Findings

Per the roadmap's post-phase retrospective protocol: what the Pre-flight phase
revealed, what was fixed immediately, what is deferred where.

## Findings

| # | Finding | Severity | Action |
|---|---|---|---|
| R1 | Custom detekt rules can be ServiceLoader-registered but silently inactive without a detekt.yml ruleset block. Two rules (`no-runblocking`, `no-viewmodel-scope`) sat inactive for days; only a positive-control test revealed it. | **High (process gap)** | Fixed now (detekt.yml activated). Rule: every new rule needs BOTH ServiceLoader entry AND detekt.yml block + a positive-control run. Encoded in `singularity-todo-detekt-rules-authoring` (see below). |
| R2 | ADR `2026-09-22-pomodoro-hybrid-timer` says "use `kotlinx.datetime.Clock` in new code" — but under kotlinx-datetime 0.8.0 the codebase convention (CalendarDiModule, FakeClock, platform Clock.kt) is `kotlin.time.Clock`. Following the ADR literally breaks compilation. | Medium (doc bug) | Fixed in code (used `kotlin.time.Clock`). ADR correction deferred to Epic 3 PR 3.5 (deprecation sweep owns the Clock alias topic). |
| R3 | AGENTS.md documents `./gradlew :shared:commonTest` — no such task exists; commonTest sources execute inside `:shared:jvmTest`. | Low (doc bug) | Fixed now (AGENTS.md test table). |
| R4 | Baseline `baseline-shared.xml` (1382 entries) contains stale fingerprints — e.g. `VmCloseable:TaskCreateViewModel` referencing a debounce loop that has since moved to `DraftMviViewModel`; `VmScopePosition:SettingsViewModel` since fixed. Stale entries mask nothing today but bloat the baseline. | Low | Deferred to PR 3.3 (baseline regeneration belongs to detekt promotion). |
| R5 | Detekt currently reports 21 `VmCloseable` findings (VMs with injected scope but no `addCloseable(scope)` in init) — not in baseline, not enforced (`ignoreFailures = true`). | Medium | Input to Epic 2 PR 2.3 (MviViewModel migration sweep fixes these mechanically). |
| R6 | ~340 detekt findings, dominated by formatting (ArgumentListWrapping, Indentation, NoUnusedImports in test files) — a `just detekt-fix` auto-format pass would clear most. | Low | Deferred to PR 3.3 (format pass before `ignoreFailures = false`). |
| R7 | The 17-failing-tests ADR list was stale (3 classes deleted) and the 63-failure count was never re-baselined. | Low | Fixed now (amendment); PR 3.1 owns the live re-count. |
| R8 | `isExpired` boundary semantics (strict `>`) were undocumented — discovered by writing the boundary test. | Low | Fixed now (test documents it). |

## Immediate fixes applied in this phase

R1 (detekt.yml), R3 (AGENTS.md), R7 (ADR amendment), R8 (boundary test).

## Deferred (with triggers)

- R2 → PR 3.5 Clock deprecation sweep.
- R4, R6 → PR 3.3 detekt promotion.
- R5 → Epic 2 PR 2.3.

## Skill update

`.agents/skills/singularity-todo-detekt-rules-authoring/SKILL.md`: add the
"activation checklist" (ServiceLoader + detekt.yml + positive control) —
done in this commit.

## Links

- `2026-09-26-preflight-quick-wins` — phase ADR
- Roadmap protocol: Post-phase retrospective (triage ≤45 min, one ADR per phase)
