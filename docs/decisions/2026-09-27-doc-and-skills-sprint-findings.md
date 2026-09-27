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

## Phase D

_(pending)_

## Phase E

_(pending)_

## Phase F

_(pending)_
