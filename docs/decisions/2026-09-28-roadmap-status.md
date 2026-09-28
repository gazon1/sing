---
title: "Tech-debt roadmap v3 — what three MRs closed, and what is left"
date: 2026-09-28
tags: [retro, tech-debt, roadmap, status]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Status of `refactor/tech-debt-roadmap-v3` after MR-1, MR-2 and MR-3. The roadmap was
built from a reading of the ADR corpus; **three of its items did not survive contact with
the code**, and in each case the item was wrong in a way that would have caused damage if
implemented as written. This entry records the corrected state so the next MR starts from
facts rather than from the plan.

Verification at time of writing: **1140 tests, 0 failed, 0 skipped; detekt 0 findings.**
Updated after MR-4: see `2026-09-28-mr4-combine-soundness.md`.

## Closed

| Item | Was | Now |
|---|---|---|
| Slot suite on real time | 45 tests, `delay(SETTLE)` = 100 ms each | virtual time; `SETTLE` deleted |
| `DraftMviViewModelTest` | 36 real `delay(20)` | `advanceTimeBy` + `runCurrent()` |
| `FakeTaskRepository.currentUser` | `get()` constructing a `SupervisorJob` per read, 17 reads | `by lazy` |
| `ProjectDetailViewModel` task stream | restarted on every field write (2 → 3 subscriptions) | stable at 2 |
| `ProjectDetailViewModel._latestProject` | written every emission, never read, 2 lying comments | deleted |
| `TagsRepositoryImpl.observeTag` | unscoped — another profile's tag, and soft-deleted ones | scoped |
| `TagGroupRepositoryImpl.observe` | unscoped — another profile's tag group | scoped |
| `NoRunBlocking` in new tests | caught by the project's own rule | tests are `suspend` |
| R7 in `2026-09-28-mr2-retro-findings` | **closed** — 7 call sites migrated to `invoke(id) { copy(…) }`; 0 deprecation warnings remain | `2026-09-28-mr5-vm-hygiene` |
| Ledger #3 in `2026-09-27-write-layer-soundness` | a 60 s hang with a stated cause | **obsolete, not fixed** |
| "6 VMs not on MVI" (`2026-09-25-mvi-framework-status`) | 6 flagged | all 24 production VMs on MVI |
| `ProjectDetailViewModel` split | roadmap item | **not needed** — already rejected in `pr24-rescope` |
| `CalendarSyncViewModel` combine | `updateState` inside the transform, `.collect {}` on nothing | transform returns the reducer; collector applies |
| MR-5 items 1–3 | deprecated `invoke(task)` ×7, unreachable `else` ×7, `repeat(3)` ×15 | all gone; 1140 tests pass |
| `SearchViewModel` combine | `listOf` packing + `@Suppress("UNCHECKED_CAST")` | `combineStates`; suppression gone |
| `NoCombineSideEffect` coverage | `updateState`/`setState` unguarded in a projection | both covered, positive control verified |
| `NoOpUpdateStateRule` | aborted `:shared:detekt` on `updateState(reduce)` | explicit walk; no `psiUtil` |

## Where the plan was wrong

**MR-1 asked for the wrong direction.** The roadmap said to change
`FakeProfileAwareCurrentUser`'s default from `Unconfined` to `Default`. Commit `560f3bf8`
had already made it `Unconfined`, and its own ADR recorded that the change was unverified.
Implementing the plan would have re-introduced the exact bug that commit fixed. A probe
test — not an argument about dispatchers — settled it: the default was already correct, and
no fake needed changing.

**MR-2 asked for a split that had been rejected three days earlier.**
`2026-09-26-pr24-rescope` weighed `ProjectTasksViewModel` + `ProjectMetadataViewModel` and
declined them, and the replacing work was already in the file. The "23 intents" were 13
`Domain` plus 10 `Routing` variants the ViewModel never receives. None of the four bugs
that justified the TaskDetail split existed here.

**MR-3 promised a baseline cleanup the split cannot deliver.** The `NotesRepository.kt`
`TooManyFunctions` entries are suppressed, and moving the mappers out does not clear them:
the limits that fire are 11 per interface and 11 per class, not 25 per file, and the
interface alone has 20 methods. Both entries stay.

## Open

### Carried from earlier ADRs, still true

| # | Item | Source | Note |
|---|---|---|---|
| 1 | ~~`NotesListViewModel` uses `Dispatchers.Unconfined`~~ — **closed**: hardcoded dispatcher dropped, all four launches use the injected scope | `2026-09-28-mr5-vm-hygiene` | O1 |
| 1b | ~~`NotesListViewModel.createNoteWithTitle` returned an id the repository never used~~ — **closed**: `NotesUiEvent.NavigateToEditor` carries the real id, following `CalendarUiEvent.NavigateToTask` | `2026-09-28-notes-create-navigation` | the only user-facing bug found by this roadmap |
| 2 | `NoRealDelayInTest` has a `value <= 500` cutoff, so it could not flag any of the 109 real-time sites it exists to catch | MR-1 retro | threshold is the defect |
| 3 | 67 production deprecation warnings (was 81) across 30 files, untracked | MR-2 retro | 12 are deprecated Nav2 `AppDestination` variants |
| 3b | ~~`NoCombineSideEffectRule` does not fire on `updateState`~~ — **closed**: the Gradle daemon was caching the detekt plugin classpath, so the extension was invisible until `./gradlew --stop`. The rule is extended and verified | `2026-09-28-detekt-daemon-and-crashing-rule` | **run `./gradlew --stop` after editing any rule** |
| 3c | ~~`NoOpUpdateStateRule` throws on some inputs~~ — **closed**: `psiUtil.collectDescendantsOfType` is inlined and its synthetic class fails to load in detekt's classloader. Replaced with an explicit walk | same ADR | no `psiUtil` in detekt plugins |
| 4 | `UpdateProjectUseCase` returns `Result<Unit>`; `UpdateTaskUseCase` returns `Result<Task>`. Its full-entity overload has no callers and is not deprecated | MR-2 retro | harmless today |
| 5 | `NotesRepository.kt` per-declaration function limits | ledger #9 | the mapper split did not close it |
| 6 | `RoomNotesRepository`, `RoomSavedAgendaViewsRepository`, `RoomReminderRepository`, `RoomChecklistRepository` all deviate from the `*RepositoryImpl` convention | MR-3 retro | rename all four or none |
| 7 | `ProjectDetailViewModelTest` covers 5 of 13 domain intents; the two debounced ones are untested | MR-2 retro | |
| 8 | `TagGroupRepository` / `ReminderRepository` / `AttachmentRepository` do not extend `GenericUserScopedRepository` | MR-3 retro | a contract change, not a cleanup |
| 9 | `AttachmentRepository.create()` has no callers | MR-3 retro | delete, or keep for a bulk-import path |
| 10 | 4 JUnit 4 instrumented tests in `androidApp/src/androidTest` — CI has no `connectedAndroidTest` step, so they never run | `2026-09-28-mr5-verification` | O3 pointed at a deleted Robolectric file; needs its own MR with a device |
| 11 | `TaskMenuBuilder.kt` — 15 TODOs naming use cases the `PassThroughUseCase` rule forbids | `2026-09-24-deferred-backlog` | |
| 12 | 34 dead references in skill prose; `DIGEST.md` at ~1950 lines against a 1500 budget | `2026-09-27-doc-and-skills-sprint-findings` | pre-existing. The excess is **all** per-tag content across 57 sections — a budget question, not a generator defect; see `2026-09-28-mr7-mr8-verification` |
| 12b | `TaskMenuBuilder`'s 15 TODOs: 8 name use cases that were never written, 2 are UI, 2 (print, share) are platform actions where no use case is right | `2026-09-28-mr7-mr8-verification` | needs per-item resolution, not a blanket rewrite. `ArchiveTaskUseCase` is stale — the repository call landed with the write-layer sweep |

### Proposed by this work

| # | Item | Why it matters |
|---|---|---|
| 13 | **A Konsist rule for scoped reads** | `ArchitectureTest` has `DAO mutations are ownership-scoped` and no read counterpart. That mechanical gap is why MR-3's two leaks survived eight MRs of write-layer work. Highest-value item on this list |
| 14 | A sweep of the remaining DAO read paths for the same class | only Tasks has a read-isolation test; tags and tag groups were the two that turned out to be broken |
| 16 | **`SettingsContributor` vs `FeatureSlot`** — two parallel abstractions, both sound. Conversion needs a scope per contributor and a suspend change to an interface 7 task slots implement | `2026-09-28-mr7-mr8-verification` | no defect behind it; a style decision, not roadmap work |
| 17 | **Cost tracking is 60% wired** — `costUsdMicros` is plumbed end to end and documented as "null if model not in pricing table"; the pricing table and the Koog `metaInfo` bridge are both absent | same | not "write two files" — a table plus a hook into the agent pipeline |
| 15 | **The repository-package convention has never been settled** | 6 features keep repositories in `.data`, 5 at the feature root, and 2 (`search`, `tags`) in both. The ADR calls two files inconsistent with a convention that does not exist yet. Decide, then migrate — or record why the spread is fine. `2026-09-28-mr6-verification` |

| # | Item | Why it matters |
|---|---|---|
| 13 | **A Konsist rule for scoped reads** | `ArchitectureTest` has `DAO mutations are ownership-scoped` and no read counterpart. That mechanical gap is why MR-3's two leaks survived eight MRs of write-layer work. Highest-value item on this list |
| 14 | A sweep of the remaining DAO read paths for the same class | only Tasks has a read-isolation test; tags and tag groups were the two that turned out to be broken |

## Roadmap items not started

MR-4 is done — but the `SearchViewModel` half was not a deletion as planned; see the
retro. MR-5 (`NotesListViewModel`
dispatcher, instrumented tests, and the deprecation warnings), MR-6 (DI cleanup + the
`Modules.kt` facade Konsist test), MR-7 (`SettingsContributor` → `FeatureSlot`),
MR-8 (documentation backlog). **None has been verified against the code**, and three of
the first five were found wrong on inspection. Read before implementing.

MR-7 is the riskiest: it changes the sealed hierarchy in `core`, and
`2026-09-27-feature-slot-pattern` declined it deliberately because `SettingsContributor`
and `FeatureSlot` differ on three axes at once.

## Rules

- **Verify a claim about current behaviour before planning a fix around it.** Every wrong
  item above was a claim about the tree, not about design. `git log -S` settles it in one
  command.
- **An ADR counts things; recount them.** "23 intents", "6 VMs not on MVI", "28 tests on
  real time", "TooManyFunctions limit of 25 per file" were all wrong.
- **A sweep scoped to one direction is not a sweep.** Eight MRs of write-layer work left
  two read leaks.
- **A test that has never been seen to fail is not known to work** — including the
  negative direction: a rule can be active and still exempt every case it targets.

## Links

- `2026-09-28-mr1-test-virtualization-retro` · `2026-09-28-mr2-project-detail-retro` ·
  `2026-09-28-mr3-repository-read-isolation`
- `2026-09-26-pr24-rescope` — the split decision that was already made
- `2026-09-27-write-layer-soundness` — the write-layer ledger
- `2026-09-25-mvi-framework-status` — the MVI migration table, now historical
