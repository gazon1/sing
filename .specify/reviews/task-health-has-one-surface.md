---
change: task-health-has-one-surface
verdict: pass
date: 2026-11-15
pr: 2989
reviewed: 17848c254d864f932aadb62b47345f7c75ddd159
documents: sha256:c09a06014cb6a0e11aae14b3ece686f268dd42af71d1c8e2ec9a95574b7a0b1f
---

## What was reviewed

The change that makes the surfaces reporting task health agree, after the first round
of review:

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskHealth.kt`
  — `HealthRules` (the precedence, one owner) and `stagnationWindowOf` (what a stored
  threshold means).
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/TaskToolSupport.kt`
  — the duplicate classifier removed; `health()`, `summary()`/`detail()` and `select()`
  go through `HealthRules` and the translated window.
- `ListTasksTool.kt`, `ProjectReviewTool.kt`, `WeeklyPlanTool.kt` — every AI tool resolves
  `selection=stagnant` with the stored threshold.
- `StatisticsViewModel.kt`, `StatisticsScreen.kt` — the shared window translation, the
  card `hint`, and `TaskHealthBucket.explanation()`.
- `AgendaRouteMapping.kt` — `stagnantDefinition` and the waiting screen.
- `HealthClassificationAgreementTest.kt`, `StagnantDefinitionTest.kt`,
  `StagnantDrilldownHonoursThresholdFlowTest.kt` — the tests.
- `docs/decisions/2026-11-15-task-health-has-one-classifier.md` and
  `openspec/changes/task-health-has-one-surface/` — the contract.

## What was found, and what was done about it

**The two copies had already drifted.** Verified against `git show HEAD~1`: the old
`TaskToolSupport.bucket()` passed `stalenessDays` straight into `Selector.Stagnant`
(no `> 0` guard) while `StatisticsViewModel` translated `0` into a `null` window. On a
stored `0` the tools called every undated task stagnant and the card called none. The
claim in the ADR is true, not reconstructed from memory.

**The first round's test did not prove the defect was fixed.** The initial
`HealthClassificationAgreementTest` compared only zero-filtered problem buckets, so an
input set that resolved to nothing would have passed as agreement. It now asserts an
explicit non-zero count for all three buckets in the precedence case, and the
`.problems()` helper is documented as the reason that pairing is required.

**The first round's flow test was not discriminating.** It seeded two tasks through two
`tasks(koin)` calls; `TasksRobot` restarts its id counter per instance, so the second
seed overwrote the first. The test passed against the reverted code. It was replaced
with one robot and an assertion that both tasks were stored, and re-run against the
reverted `stagnantDefinition` to confirm it now fails.

**The drift also remained on the selection side.** `list_tasks` read the threshold for
its health counts but let `TaskSelectionResolver` resolve `selection=stagnant` with the
21-day default — one response could report two answers. The planning tools had the same
gap. All three now pass the translated window.

**The FINE hint was false.** It said "recently touched"; a dated task that has not been
edited in a year is fine. It now states the absence of the three conditions. The other
three hints were checked against their predicates and matched.

**The live-change path.** A threshold change while the drill-down is open does not
recompute, because Koin keys the ViewModel by class. This is not reachable through the
UI — the only writer of the setting is the Settings screen — so no refresh mechanism
was added, per the review's rule. Reopening after a change is covered by a test.

## What was deliberately not done

No new route, screen, enum, stored table, priority scale or DI service. `HealthRules`
stayed a plain Kotlin class in `feature/tasks/domain/logic`, beside `TaskHealthBucket`.
"The two produce the same sections and differ only in the window value" was checked
against `byStagnant()`'s actual composition and holds.

## Verification

```
./gw :shared:jvmTest --tests "*HealthClassificationAgreementTest*" \
  --tests "*StagnantDefinitionTest*" --tests "*TaskHealthTest*" \
  --tests "*TaskReadSurfaceTest*" --tests "*ReadToolsProfileAwareTest*" \
  --tests "*TaskVocabularyTest*" --tests "*StatisticsViewModelTest*"     # 68 passed
./gw :desktopApp:test -Ptest.tags=slow:compose \
  --tests "*StagnantDrilldownHonoursThresholdFlowTest*" \
  --tests "*StatisticsHealthTabFlowTest*" \
  --tests "*AgendaSectionTagReachabilityFlowTest*"                       # 9 passed
./gw :shared:detekt                                                       # clean
python3 scripts/ktlint-lint.py --require                                  # clean
easement check                                                            # 0 violations
openspec validate task-health-has-one-surface --strict                    # valid
```

Both new tests were run against the old behaviour and failed there before being kept.
