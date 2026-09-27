---
title: Triage findings from the doc-and-skills hygiene sprint
date: 2026-09-27
status: accepted
tags: [sprint, hygiene, triage, documentation]
---

# Triage findings from the doc-and-skills hygiene sprint

Append-only log of what post-phase reviews turned up. Critical items are fixed inline in
the phase commit; everything below is deliberate backlog.

## Phase A — stale-ref cascades

### Fixed inline

- **3 ADRs had body text swallowed by frontmatter.** `2026-09-16-calendar-feature.md`,
  `2026-09-16-calendar-post-merge-fixes.md` and `2026-09-16-tasks-upcoming-screen.md` had
  their Context text inside the `---` block, so YAML parsed prose as keys and the whole
  Context section was invisible. This broke `refresh-decisions-digest.py` parsing. All three
  rebuilt with proper frontmatter, a `title`, and a `## Context` section.
- **2 ADRs had no H1 at all** (`2026-09-23-deprecation-tech-debt`,
  `2026-09-23-pomodoro-alarm-refactor`) — a `title` in frontmatter but no `# heading`, so
  the digest entry fell back to the filename. H1s added.

### Backlog

- **141 of 275 ADRs have no H1 heading and 106 have no `title:` in frontmatter.** The digest
  renders most of these as `_(no title)_`, which makes the most-recent-decisions view much
  harder to scan than it needs to be. Fix in the frontmatter normalisation script (Phase C):
  backfill `title` from the first `# heading`, and add an H1 derived from the slug when
  neither exists.
- **`ModelPricing` and `UsageExtractor` are documented but not implemented.**
  `singularity-todo-llm-usage-tracking` describes both as existing files; in the code
  `ToolUsageEvent.costUsdMicros` is always `null` and nothing reads Koog `metaInfo`. The
  skill now carries an explicit warning, but the pricing table and extractor still need to
  be written for the AI Usage screen to show real cost. Tracked as its own task.
- **141 ADRs predate `## Context` / `## Decision` structure** (retro-style entries use
  `## Status`, `## What would unblock`). Not worth normalising — the doc-maintenance policy
  only requires the structure for new decisions.
- **Konsist test for the DI facade.** A test asserting `Modules.kt` contains no `single {}` /
  `factory {}` outside its two facade functions would make the facade invariant executable
  rather than documentary. See `2026-09-27-di-module-aggregator-narrative.md`.
- **12 modules have zero ADR coverage**: `feature/agenda`, `feature/calendar*`,
  `feature/profile`, `feature/genui`, `feature/gate`, `feature/whatsnew`, `feature/alarms`,
  `core/observability`, `core/tree`, `core/draft`, `core/serialization`, `core/ids`.
- **Supabase stub TODOs** — `core/auth/AuthRepository.kt` (3), `core/sync/SyncApi.kt` (6),
  `core/di/CoreDiModule.kt:219` (analytics). All wait on the real backend; consolidate into
  one deferred-backlog ADR so they stop looking like forgotten work.
- **`TaskMenuBuilder.kt` has 15 TODO markers** for unwired menu actions. Each says
  "wire to `<UseCase>`", but the `PassThroughUseCase` rule forbids the thin use cases those
  TODOs imply. The TODOs need rewriting to name the repository method they should call.
- **Thin KDoc** on `core/di/Modules.kt:24` (restates the function name),
  `core/sync/SyncRepository.kt:6` (`ConnectionTestResult` semantics undocumented),
  `core/sync/SyncableEntity.kt` (no explanation of how `toJson()` maps to the sync
  protocol). Allowed by the current KDoc policy, but these are the three files a new
  contributor reads first when touching sync.

## Phase B — retiring `vm-koin-scoping`

### Fixed inline

- **6 live references redirected.** `vm-pattern-overview` (router table + decision tree),
  `koin-dsl` ("See also"), `koin-overview` (router table + decision tree),
  `vm-intent-pattern` ("See also") and `attachments` all pointed at the retired skill. Each
  now points at the `AGENTS.md` DI table or `koin-dsl`.

### Backlog

- **5 skills still have no YAML frontmatter** and are silently skipped by
  `check-skill-frontmatter.sh` (it `continue`s instead of failing): `detekt-workflow`,
  `koin-dsl`, `tech-debt-refactor`, `vm-lifecycle-addcloseable`, `worktree-isolation`.
  Because the check skips rather than fails, nobody is told. Fix in Phase C — retrofit
  frontmatter and make the script report skips as a warning.
- The retired directory is named `singularity-todo-vm-koin-scoping-RETIRED`, which will keep
  matching grep for the old name. That is intentional (history stays greppable) but means
  "is this skill live?" checks need to filter on the suffix.

## Phase C — frontmatter + CI gates

### Fixed inline

- **108 ADRs normalised**: 106 gained a `title` (from the first H1, else the slug), 39
  gained a `date` (from the filename). The digest previously rendered most recent
  decisions as `_(no title)_`; it now renders none.
- **5 skills retrofitted with frontmatter** — `detekt-workflow`, `koin-dsl`,
  `tech-debt-refactor`, `vm-lifecycle-addcloseable`, `worktree-isolation`. All 89 now
  have frontmatter.
- **`check-skill-frontmatter.sh` no longer skips a skill with no frontmatter.** It
  skipped them silently, so 5 broken skills looked like 5 healthy ones. A skill without
  frontmatter is invisible to the loader, so it is now an error.
- **Writing the dead-ref checker surfaced three more cascades** beyond the 13 in Phase A:
  `core/profile/` → `feature/profile/`, `AiToolsDiModule.kt` → `AiToolsModule.{jvm,android}.kt`,
  `core/di/DiGraphTest.kt` → `test/KoinGraphValidationTest.kt`. All fixed.
- **`detekt-rules-authoring` listed two rules that no longer exist**
  (`NoCombineSideEffectRule`, `NoGlobalScopeLaunchRule` — deleted per
  `2026-09-26-detekt-rules-activation-audit`). Removed from the table.
- **`koog-agent` pointed at `AndroidKoogFactory.kt`**, which does not exist; the actual
  lives in `core/di/KoogPromptExecutorFactory.kt`.

### Backlog

- **~30 dead refs remain in skills** that prescribe files which were designed but never
  written: `AppDatabaseCtor.kt`, `ChannelTransportTest.kt`, `Args.kt`,
  `DateBucketExtensions.kt`, `RunInLifecycle.kt`, `TaskFormatters.kt`, `TasksFormatters.kt`,
  `NotesScreen.kt`, `NoteCardActions.kt`, `TaskDetailActions.kt`, `AttachmentButton.kt`,
  `AttachmentSheet.kt`, `TaskDetailScreen.kt`, `AndroidDiGraphTest.kt`, plus ADR paths that
  were renamed (`2026-09-26-junit-tag-default-semantics.md`). These are examples in skill
  prose rather than claims that would mislead a code edit, so they rank below the Phase A
  cascades — but `just docs-audit` keeps failing until they are resolved. Either write the
  files or reword the examples so the hypothetical is explicit.
- **`DIGEST.md` is 1865 lines against a 1500 budget** (up from 1823 as ADRs were added).
  Trimming needs a change to `refresh-decisions-digest.py`'s selection, not manual pruning
  — already deferred to PR-1.2.
- **`writing-for-agents` references `CLAUDE.md` and `package.json`**, which do not exist
  here. Correct for a generic skill, but the checker flags it. Consider a per-skill
  ignore list rather than loosening the checker.

## Phase D — detekt wiring

### Fixed inline

- **`NoFactoryViewModelRule` had never run.** The rule and its unit tests existed, and
  `2026-09-26-konsist-architecture-tests` credited it with catching a real
  `factory<CalendarSyncViewModel>` registration — but the provider was never listed in
  `META-INF/services/dev.detekt.api.RuleSetProvider`, so detekt never loaded it. The
  finding came from a manual review. Now registered and active, 0 findings.
  **Worth noting: `singularity-todo-detekt-rules-authoring` already documented
  "Provider not in ServiceLoader" as Common Mistake #1. Knowing the failure mode did not
  prevent it.**
- **`check.sh` swallowed detekt failures.** The step printed "report-only,
  ignoreFailures=true" and had a `|| { echo ... }` with no `exit 1`, so a green
  `./check.sh` did not imply a clean detekt run — while `ignoreFailures` had actually been
  `false` since PR 3.3. The local check was the most-trusted gate and it did not gate.
- **4 more places claimed detekt was report-only** (`quality-tools` ×2, `detekt-workflow`,
  `AGENTS.md`). An agent reading those would have treated lint as optional.
- **`MviViewModelExtRule` never existed** — the real rules are `NoStateIn`,
  `NoFactoryViewModel`, `ViewModelMustHaveKDoc` plus the `mvi-viewmodel` trio.
- **Both detekt skills listed a subset of the rules** (3 of 9 and 5 of 9). Both now list
  all 9, with the rule-registration procedure called out in both.

### Backlog

- `mcp-server` still carries a 74-finding detekt baseline and `androidApp` has no detekt at
  all (`quality-tools` coverage table). Both are known, both are unaddressed.
- The `check.sh` fix changes local-check behaviour: contributors who were relying on
  detekt warnings not failing will now see failures. Expected — it matches CI — but
  worth a line in the PR description.

## Phase E — oversized skill splits

### Done

- `shared-ui-components` 756 → 42-line router + 5 leaf files
  (`widget-library`, `decomposition`, `content-slot-api`, `menus-and-dialogs`,
  `document-style-layout`).
- `ui-event-vs-state` 679 → 63-line router + 8 leaf files (one-shot events, VM event
  tests, anti-patterns, routing state, state ownership, debounced edits, collection
  strategy, mirror state).
- Both routers keep the decision rule inline so an agent gets it without opening a leaf,
  and `SKILL.md` stays the entry point, so the ~12 inbound references keep resolving. All
  13 router→leaf links verified.

### Backlog

- **4 more skills are over the 500-line budget** and will fail `check-doc-sizes.py`:
  `test-helpers` (500, at the limit), `llm-usage-tracking` (467),
  `kotlin-idioms` (455), `feature-scaffold` (455), `ai-tool` (434). `feature-scaffold`
  and `ai-tool` are close enough to be worth a look; `test-helpers` and `llm-usage-tracking`
  are the two densest.
- **The size check will fail the docs-audit until DIGEST.md is under 1500 lines.** The
  other budgets are now green.

## Phase F — skill ecosystem rebalance

### Done

The plan called for 11 new meta-skills; deduplication against the existing 89 showed 4 were
already covered and 4 more were one workflow sliced three ways. Implemented instead:

- **5 additions to skills that already owned their topic** (no new description in context):
  `code-review-pr-workflow` (Phase 0 author pre-flight), `debugging-investigation`
  (incident report template), `decisions-workflow` (supersede protocol + corpus
  validation), `writing-for-agents/SKILL-MECHANICS.md` (size budgets, router+leaf
  convention, description-as-invocation-condition), and the generated
  `docs/SKILLS-CATALOG.md`.
- **2 new skills**: `singularity-todo-monthly-doc-audit` (the judgement pass the
  mechanical checks cannot do) and `singularity-todo-scheduled-maintenance` (runtime
  measurement, wrapping the previously unwrapped `scripts/ram-bench.sh`).
- 91 skills, all with valid frontmatter, all within budget.

### Backlog

- **34 dead refs remain**, almost all in skill prose describing files that were designed
  and never written (`AppDatabaseCtor.kt`, `AndroidDiGraphTest.kt`, `Args.kt`,
  `ChannelTransportTest.kt`, `DateBucketExtensions.kt`, `RunInLifecycle.kt`,
  `TaskFormatters.kt` ×2, `TasksFormatters.kt`, `NotesScreen.kt`, `NoteCardActions.kt`,
  `TaskDetailActions.kt`, `AttachmentButton.kt` ×2, `AttachmentSheet.kt` ×2,
  `TaskDetailScreen.kt`, `ProjectDetailScreen.kt` path drift, `Context-MAP.md`,
  `RELEASE.md`, `CLAUDE.md`, `package.json`) plus one renamed ADR
  (`2026-09-26-junit-tag-default-semantics.md`). **These keep `just docs-audit` red.**
  Each is either "write the file" or "reword the example so it is clearly hypothetical" —
  a decision per reference, not a mechanical fix.

## Phase G — close-out

### Verified before the final commit

- Every number in `2026-09-27-doc-and-skills-sprint-results.md` re-checked against the
  tree: 9 rule files / 9 providers, 211-line `AGENTS.md`, 91 skills, 278 ADRs, 180 files
  changed, 12 commits.
- Digest regenerated (278 entries), skills catalog regenerated (91 skills), ADR
  frontmatter re-normalised.
- All router→leaf links in the two split skills resolve; every inbound reference to a
  renamed or retired skill was re-pointed, not deleted.

### Final state of the gate

```bash
./check.sh            # tests + Android build + detekt (now failing on violations)
just tcheck-evals     # 5/5
just docs-audit       # frontmatter OK, sizes warn-only, dead refs: 34 remaining
```

`just docs-audit` still exits non-zero because of the 34 dead references. That is
deliberate: the remaining ones are all "this was designed in a document and never built",
and each needs a decision (write it, or stop describing it as existing) rather than a
mechanical edit.

### Backlog carried out of the sprint

1. `DIGEST.md` 1867 → 1500 lines (generator selection, not manual pruning; already
   tracked as PR-1.2).
2. 34 dead references in skill prose.
3. 5 skills near the 500-line budget: `test-helpers`, `llm-usage-tracking`,
   `kotlin-idioms`, `feature-scaffold`, `ai-tool`.
4. `ModelPricing` / `UsageExtractor` — documented as existing, never implemented, so the
   AI Usage cost column is always empty.
5. 12 modules with zero ADR coverage.
6. `mcp-server` detekt baseline (74 findings) and no detekt on `androidApp`.
7. Supabase stub TODOs and the 15 `TaskMenuBuilder` TODOs that name use cases the
   `PassThroughUseCase` rule would forbid.
8. Konsist test for the DI-facade invariant from
   `2026-09-27-di-module-aggregator-narrative.md`.
