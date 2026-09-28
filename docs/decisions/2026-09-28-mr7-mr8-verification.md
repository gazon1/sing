---
title: "MR-7 and MR-8 verification — the sealed hierarchy is already migrated, and three of MR-8's items are mis-scoped"
date: 2026-09-28
tags: [retro, tech-debt, verification, settings, docs]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Verification of MR-7 and MR-8 before implementation, following MR-1 through MR-6.

**MR-7: its central item is already done, and the rest is a refactor with no defect behind
it. MR-8: two items are valid, three are mis-scoped, and the largest asks for content
deletion that the budget, not the generator, is setting.**

## MR-7 — `SettingsContributor` → `FeatureSlot`

### Item 1 is complete

The plan's first item was: *"`core.settings.SettingsIntent`: typealias → sealed interface
extends `MviIntent`"*. It is not a typealias, and it already extends `MviIntent`:

```kotlin
// core/settings/SettingsBundle.kt:101
sealed interface SettingsIntent : com.singularity.todo.core.ui.MviIntent {
    sealed interface Appearance : SettingsIntent { … }
    sealed interface Ai : SettingsIntent { … }
```

`2026-09-27-mvi-single-state-entry-and-vm-sweep` did this, and its deferred-findings list
records the resulting coupling (`core.settings → core.ui`) as *"noted, no action"*.

### Item 2 has no defect behind it

There are **5** contributors: `AppearanceContributor`, `NotificationsContributor`,
`GreetingContributor`, `WorkScheduleContributor`, `AiContributor`. The plumbing is already
generic, because `2026-09-26-pr24-rescope` collapsed 6 dispatchers and 7 observe blocks
into one of each:

```kotlin
private fun <S : SettingsSection> bind(contributor: SettingsContributor<S, *>?, slot: MutableStateFlow<S>)
private fun dispatch(contributor: SettingsContributor<*, *>?, errorLabel: String, intent: SettingsIntent)
```

`2026-09-27-feature-slot-pattern` declined the conversion deliberately, listing three
axes of difference. One of those reasons is now obsolete — `SettingsIntent` *is* an
`MviIntent` — but the other two stand:

| | `SettingsContributor` | `FeatureSlot` |
|---|---|---|
| state | `fun observe(): Flow<S>` | `val state: StateFlow<S>` |
| intent | `suspend fun process(intent: I)` | `fun onIntent(intent: I)` |

Converting means giving each contributor a `StateFlow` — which needs a scope and
`stateIn` that they do not have today, and which the project bans in ViewModels without
cause — and either making `FeatureSlot.onIntent` suspend, or wrapping every call in a
launch. **The first changes an interface that 7 task slots already implement**; the
second makes every intent fire-and-forget.

What remains after that is naming consistency. The one genuinely valuable outcome is that
`core.settings` would depend on `core.ui.featureSlot` instead of declaring its own
parallel abstraction — but it would deepen the same coupling the sweep already flagged.

**Verdict: do not do it in a roadmap item.** Either leave `SettingsContributor` as the
settings-layer abstraction and record why, or scope it as its own MR with a decision on
whether `FeatureSlot.onIntent` becomes suspend.

### Item 4 is valid and cheap

Neither `BackupUiEvent.kt` nor `SettingsUiState.kt` carries an invariant note — a search
for "invariant", "exactly one" and "mutually exclusive" returns **0** in both. Both are
`sealed` hierarchies rendered by exhaustive `when`, and `NoteEditorScreen.toNotification`
is one such `when` — the compiler caught the new variant added in MR-6 exactly as intended,
which is the argument for writing the invariant down.

## MR-8 — documentation and process backlog

### Item 1 — valid, but "rewrite all 15 to `taskRepo.Xxx`" is wrong for at least four

There are 15 `TODO`s in `TaskMenuBuilder.kt`, and the plan's diagnosis is right: the
`PassThroughUseCase` rule forbids the thin use cases they name. But they are not one kind
of thing:

| Count | Kind | Right treatment |
|---|---|---|
| 8 | Name a use case that was never written — `MoveTaskUseCase` ×4, `AddLabelUseCase`, `DuplicateTaskUseCase`, `CopyTaskToProjectUseCase`, `ReorderTaskUseCase` ×2, `ArchiveTaskUseCase`, `ShareTaskUseCase` | rewrite to the repository call that exists — `updateTask(id) { copy(…) }`, `softDelete`, `taskRepo.togglePinned` |
| 2 | UI, not domain: *"open DependencyPickerSheet with current dependsOn"*, *"restore as item { onSetRecurring?.let { … } }"* | leave as deferred UI work, but say so in the TODO |
| 1 | *"enumerate existing projects dynamically"* | needs `ProjectsRepository.observeAll()` plumbed into the menu builder |
| 2 | `PrintTaskUseCase`, `ShareTaskUseCase` | **not repository concerns** — a print dialog and a platform share sheet. No use case is the right answer; the TODO should say what the platform call is |

`ArchiveTaskUseCase` is worth calling out: the write-layer sweep added
`archiveCompletedTasks` and a soft-delete path, so the repository call now exists and the
TODO is stale in the other direction — the work landed and the marker did not.

### Item 2 — the generator is not the lever

`refresh-decisions-digest.py` is 192 lines. Its selection is two rules: bullets matching
`**Always** / **Never** / **MUST**` go to Critical; everything else is grouped by tag with
**no cap**. The current shape:

| Section | Lines |
|---|---|
| header + Critical | 89 |
| per-tag, 57 tag sections | 1851 |
| budget | 1500 |

So the excess is 440 lines, all of it per-tag, and **none of it is redundant output** —
it is one bullet per ADR consequence. Trimming it means deleting domain knowledge that
`AGENTS.md` and the skills point at. There is no selection bug to fix.

The real question is a policy one: either the 1500 budget is wrong for a corpus of this
size, or the per-tag section needs a per-tag cap with a stated rule for what gets dropped.
That is a decision about the digest's purpose, not a generator tweak, and it should be
recorded as such.

### Item 3 — accurate, and only one skill is over the line

`test-helpers` is 500 lines against a 500 budget, i.e. exactly at the limit;
`llm-usage-tracking` 469, `kotlin-idioms` 455, `feature-scaffold` 455, `ai-tool` 434.
Splitting a 500-line skill that passes its budget solves nothing. `testable-vm` (435) is
sixth and unmentioned by the plan.

### Item 4 — the infrastructure is already there; the missing part is larger than two files

`costUsdMicros` is plumbed end to end: declared on `UsageRecorder` (*"null if model not in
pricing table"*), written by `RoomUsageRecorder`, summed in the `llm_usage` aggregation.
What is missing is exactly what the ADR says: the **pricing table** and the bridge that
reads Koog's `metaInfo` — nothing in the tree reads it today. So this is not "write two
files"; it is a pricing table plus a hook into the Koog agent pipeline, which is a
dependency the roadmap has not scoped anywhere.

### Item 5 — accurate, and low value

Twelve modules have no ADR. Absence is not an error, and 12 short files written to satisfy
a coverage number is documentation that will drift. Two of the listed modules —
`core/ids`, `core/serialization` — are so small that the honest record is their absence,
not an ADR written to fill a cell.

## Revised scope

**MR-7 becomes:** the KDoc invariant notes on `BackupUiEvent` and `SettingsUiState` (one
small commit), plus a decision recorded about `SettingsContributor` — kept as-is, with the
reason, or promoted to its own MR.

**MR-8 becomes, in priority order:**

1. `TaskMenuBuilder` TODOs rewritten **per kind** — repository calls where they exist,
   delete where the use case was the wrong abstraction (print, share), and honest
   "deferred UI" wording for the two that are not domain work.
2. `ArchiveTaskUseCase` TODO removed outright — the repository call landed with the
   write-layer sweep.
3. Cost tracking, if wanted: pricing table **and** the Koog `metaInfo` bridge, scoped
   together or not at all.
4. The digest budget: decide whether 1500 is the right number before touching the
   generator.
5. ADR coverage for the 12 modules: record the decision (per-module ADR, or an explicit
   "absence is fine" list) rather than defaulting to writing 12 files.

## Rules

- **A refactor with no defect behind it needs a defect.** `SettingsContributor` is
  consistent, tested, and already collapsed to generic plumbing. Renaming it to match a
  framework is a style decision, and it would change an interface 7 slots depend on.
- **"The generator selects the wrong things" is a claim about the generator.** Read the
  output distribution first: 89 lines are Critical and 1851 are per-tag content. There is
  no selection defect; there is a budget question.
- **TODOs are not one kind of thing.** Naming the right resolution for each of fifteen
  markers means four of them do not get a repository call.

## Links

- `2026-09-27-feature-slot-pattern` — the decision that declined this conversion
- `2026-09-26-pr24-rescope` — the collapse that made `bind`/`dispatch` generic
- `2026-09-27-mvi-single-state-entry-and-vm-sweep` — deferred finding #10, the
  `core.settings → core.ui` coupling
- `2026-09-24-deferred-backlog` — the original `TaskMenuBuilder` list
- `2026-09-28-mr6-verification` — the preceding verification, same method
