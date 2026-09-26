---
title: Epic 2 retro findings — architecture phase retrospective
date: 2026-09-26
status: accepted
tags: [retro, tech-debt, epic2, detekt, testing]
epic: refactor/techdebt-epic2-v2
---

# Epic 2 Retro Findings

Per the post-phase retrospective protocol, after PR 2.1 (`360fe45a`) and
PR 2.2+2.3+2.5 (`b55576cc`).

## Findings

| # | Finding | Severity | Action |
|---|---|---|---|
| R1 | **Gradle daemon caches detekt plugin classloaders.** After editing a custom rule, detekt kept executing the old classes — stale findings, debug code never ran, ~1 h of misdirected debugging. Verified: `strings` on the jar showed new logic while the report was unchanged; `--stop` + rerun fixed it instantly. | **High (process)** | Fixed in `singularity-todo-detekt-rules-authoring` (new section). Positive-control procedure must run daemon-free. |
| R2 | **`@Tag("slow")` masks product bugs, not just test flakiness.** Un-suppressing the 12 classes surfaced 3 real production bugs (SyncViewModel debounce, NoteEditor baseline, missing post-autosave hook). | High (lesson) | Fixed in PR 2.1. Reinforces PR 3.1: remaining slow classes may hide more. |
| R3 | **Roadmap drift from parallel work is normal.** MR-6/MR-6d (parallel session) completed the MviViewModel migration mid-sprint — PR 2.2's "lifetime sharing" and PR 2.3's "9 hand-rolled VMs" scopes were already done on landing. | Low | Re-scoped in `2026-09-26-epic2-roadmap` before execution, not after. |
| R4 | **ADR-noted fixes can vanish in merges.** The read-before-write guard (`a449f249`, ADR-documented as "PR 2.3 partial") was merged into an old branch but absent from main — silently dropped. | Medium | Re-applied in PR 2.5. Lesson: grep-verify merged fixes, don't trust merge ancestry. |
| R5 | **coroutines 1.11 backgroundScope semantics are non-obvious** and broke every "obvious" wiring. Canonical table now in `singularity-todo-test-helpers`. | Medium (doc) | Fixed via skill. |
| R6 | `advanceTimeBy` skips tasks at the exact current instant — every StateFlow-resumption pump needs a trailing `runCurrent()`. | Low | Documented in the same table. |
| R7 | DIGEST.md is 1654 lines (limit 1500) and growing with every ADR. | Low | PR 3.5 candidate: slim DIGEST policy (move per-PR rows to per-ADR pages). |
| R8 | Main checkout has uncommitted parallel changes to `config/detekt/detekt.yml` (KDoc enforcement wave). This branch also edits detekt.yml (ruleset activation) — merge conflict likely at PR-merge time; resolution is mechanical (both blocks are additive). | Low | Flagged for the merge operator. |

## Deferred (triggers documented)

- **PR 2.4a / 2.4b (God-VM splits: Settings ~340 lines / ProjectDetail)** — unchanged
  scope, next session. SettingsViewModel additionally now carries the
  `addCloseable(scope)` leak fix (PR 2.3), which the split will inherit.
- PR 3.x quality phase — next session, per roadmap.

## Immediate fixes applied

R1/R5/R6 (skills), R4 (guard re-applied), R3 (roadmap ADR re-scope).

## Links

- `2026-09-26-epic2-roadmap` — phase ADR with wiring decision table
- `2026-09-26-preflight-retro-findings` — previous retro
