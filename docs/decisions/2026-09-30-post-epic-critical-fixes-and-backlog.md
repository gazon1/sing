---
title: "Post-Epic Critical Fixes and Remaining Backlog"
date: 2026-09-30
status: open
tags: [mr-review, tech-debt, cascade-delete, dead-ui, konsist]
---

# Post-Epic Critical Fixes and Remaining Backlog

## Context

A read-through of the code after the 6-MR tech debt epic closed found four items the epic's
own review passes had missed. Three were fixed here; the rest are recorded below.

The pattern connecting them: the epic's verification relied on `find-unwired-surfaces.py` and
detekt, and every one of these slipped through both. The scanner matches top-level
`*Screen` composables by name; detekt matches syntax. None of them can see a lambda with an
empty body, a stale foreign key, or a rule whose predicate is weaker than its stated intent.

## Items fixed immediately

### 1. Tag group delete left two dangling references (S-2, user-visible)

`TagGroupRepositoryImpl.delete` soft-deleted the group and stopped there. Two references
survived:

- **Member tags kept `group_id`** — a `// TODO: clear groupId on member tags (requires
  TagDao bulk update)` marked the gap since before the epic. Nullable, so nothing crashed;
  the tag just claimed membership in a group the user could no longer see.
- **`project_tag_groups` kept the group id** — the half that actually changed behaviour.
  `observeInheritedByProject` does not filter deleted groups, so `EffectiveTagsResolver`
  kept resolving a deleted group's tags into every project that inherited it. Deleting a
  group did not stop it from affecting projects.

Fixed by adding `TagDao.clearGroupForUser` + `listByGroupForUser` and
`ProjectInheritedTagGroupDao.deleteByGroupForUser`, and calling both from `delete`. Released
members are re-pushed to sync — their rows changed, so the server has to see the release or
it keeps them in the dead group. `TagEntity.toTag()` went from `private` to `internal` for
this; `TagsDiModule` now resolves the repository's six dependencies by name rather than
positionally, since it now takes three distinct DAOs.

Covered by `TagGroupDeleteCascadeTest` (5 tests: tag release, row retained for sync,
inheritance dropped, released tags enqueued, cross-profile delete rejected).

### 2. LinkedBacklinksCard was never rendered (S-2, user-visible)

The card existed, was clickable, and had two no-op `onClick` lambdas. But it was not dead in
the way the TODOs implied: `TaskBacklinksCollector` fed `TaskDetailUi.linkedNotes` /
`linkedTasks`, the coordinator merged them, and the state carried them. Only the render call
was missing — a task linked from a note simply showed nothing.

Its KDoc claimed "Rendered inside TaskEditorContent extraSections", which was false.

Wired rather than deleted, because the data pipeline was already correct. `onClick` also had
the wrong shape — one callback for the whole section — so `BacklinkSection` now takes
`onItemClick: (Int) -> Unit` and the clickable moved from the card to each row. Added
`TasksNavigator.openNote`, following the existing `openProject` / `SearchNavigator.openNote`
pattern of exiting the nested graph for another feature's route.

### 3. Project reminder affordance discarded every selection (S-3)

A bell `IconButton` in `ProjectBottomActionBar` opened `ReminderPickerSheet`; selecting an
offset invoked a `/* TODO: wire once project-reminder domain is implemented */` lambda. The
user picked a reminder and the sheet closed with nothing saved.

`Project` has no reminder field and no project-reminder domain exists — `ProjectRemindersRepository`
was deleted earlier in this epic, leaving the sheet and its button behind. Wiring it needs a
column, an entity field, a repository, a migration and sync propagation; that is a feature,
not a fix. Removed the bell button, `OpenReminderSheet` intent, `ActiveSheet.PickReminder`,
`onSetReminder` / `reminderOffset` from `CurrentProjectContent`, and the routing branch.

### 4. Konsist repository-impl rule was weaker than its name (S-3, enforcement)

`repository implementations are imported only from core di` filtered on **package**, with
`feature.agenda`, `feature.tasks` and `feature.notes` whitelisted wholesale. Any screen,
ViewModel or slot in those packages could import a `*RepositoryImpl` and the rule stayed
green — it enforced "the feature knows the impl", not "only DI knows the impl".

Rewritten to key on the file (`*DiModule.kt`) plus a `core/di` package entry for the
`Modules.kt` aggregator, which binds `ProfileRepositoryImpl` directly and is not
feature-scoped. That one file is an explicit allowlist entry rather than a widened predicate,
so the boundary stays narrow.

Verified with a positive control: a probe file in `feature.notes.presentation.screen`
importing `NotesRepositoryImpl` trips **both** the tightened rule and
`feature presentation does not import data layer`. Under the old version only the second
fired.

## Items requiring future refactor

| # | Severity | File / Area | Issue | Estimated |
|---|----------|-------------|-------|-----------|
| 1 | **S-3** | `test/fakes/FakeRepositories.kt` | 29 `error("not implemented")` stubs, nearly all on `FakeTaskDao` (lines 268–414). Any new test that reaches for the fake DAO fails on the first line with an opaque `IllegalStateException`. Tests needing a DAO use `FakeAppDatabase` instead, so nothing is broken today. | M |
| 2 | **S-3** | `scripts/find-unwired-surfaces.py` | Only matches top-level `*Screen` composables. A dead `*Card` or a no-op `onClick` lambda is invisible to it — which is how items 2 and 3 above survived six review passes. Widening it to `*Screen|*Card|*Section|*Sheet` would catch the card class. | S |
| 3 | **S-4** | `Maestro/scripts/check-tags.sh:81,103` | `ALLOW_PATTERNS` and `LEGACY_RAW` declared, never read. Dead code in the script that warns about dead code. | XS |
| 4 | **S-4** | `scripts/refresh-decisions-digest.py` | Per-tag cap hand-tuned 15→14→13 across this epic to stay under 1500 lines. No algorithmic basis; the next ADR in a full tag re-breaks it. Wants an ordering rule (e.g. drop `status: superseded` first) instead of a number. | S |
| 5 | **S-4** | `AppDestination.kt` | 6 deprecated Nav2 destination variants remain: `Inbox`, `Today`, `Upcoming`, `TasksByProject`, `TaskDetail`, `TaskDetailCreate`. MR-3 removed only the dead branches. | M |
| 6 | **S-4** | `core/coroutines`, `core/security`, `core/files`, `core/notifications` | ~13 hardcoded `Dispatchers.IO/Default`. **Re-scoped from MR-6's L estimate:** about half are `withContext(Dispatchers.IO)` wrapped around a single IO call (SecureStorage, CalendarProvider, FileRevealer, JvmNotificationPort), where an injected dispatcher buys nothing. The value is in `BackgroundScope.*` and `AlarmReceiver` — 3 files, not 13. | S–M |
| 7 | **S-4** | `feature/sync/presentation/SyncConfigScreen.kt` | Unwired since before the epic; now re-listed here so it stops being re-discovered each review. | S |

## Open Questions

- Should `find-unwired-surfaces.py` grow a lambda-body check (empty or TODO-only block passed
  as a callback), or stay a name-based scanner with the Kotlin layer picking up the rest?
  A lambda check needs a parser, not a grep; Konsist could do it but that is a new rule class.

## Links

- `2026-09-30-post-mr-6-final-triage.md` — the triage this review started from
- `2026-09-27-write-layer-soundness.md` — the DAO-scoping ledger this cascade fix extends
- `2026-09-27-di-module-aggregator-narrative.md` — why `Modules.kt` exists as an exception
- `2026-09-30-dispatcher-listviewmodel-cost.md` — the original (over-scoped) dispatcher estimate
