---
title: "Roadmap close-out — what the verified items actually delivered"
date: 2026-09-28
tags: [retro, tech-debt, koin, konsist, docs]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Close-out for the tech-debt roadmap. The eight planned MRs were verified before
implementation, and **six of the eight were found wrong on inspection**. This records what
was actually built, what was declined, and what the verification pass turned up on the
way.

Final state: **1145 tests, 0 failed, 0 skipped; detekt 0 findings on `shared` and
`desktopApp`; both the JVM and Android compilations build.**

## Built

| Item | What | Verified by |
|---|---|---|
| MR-7a | `SettingsUiState.Loading` / `.Error` deleted — nothing produced them, and `SettingsScreen` carried two unreachable render branches | compiler; `SettingsScreen` now `when`s exhaustively over one variant |
| MR-7a | Invariant notes on `SettingsUiState` and `BackupUiEvent` | — |
| MR-6a | `DiFacadeTest` — the DI-facade invariant made executable | positive control, plus a real violation added to `Modules.kt` and caught |
| MR-6b | 89 AI-tool bindings expressed as `factoryOf` (44 JVM, 45 Android) | `KoinGraphValidationTest`, `ScopeIsolationTest` — the failure mode is runtime, not compile-time |
| MR-8a | 15 `TaskMenuBuilder` TODOs rewritten to name the actual next step | no behaviour change; 1145 tests |

### MR-7a found more than it was scoped for

The plan asked for a KDoc invariant note. The check behind it found that
`SettingsUiState.Loading` and `.Error` had **no producer**: `SettingsViewModel` initialises
with `Content()` and never emits anything else, so both render branches in `SettingsScreen`
were unreachable — code no test could cover, alive only because the compiler was satisfied
the `when` was exhaustive.

`2026-09-27-mvi-single-state-entry-and-vm-sweep` recorded that these two branches "were
removed". They were not. The sweep identified them and the removal never happened, which
is the same gap this roadmap has now found four times: a real observation in an ADR, and a
half-applied follow-through.

A transient failure already had a home: `Content.errorMessage`, cleared on the next
successful action. Nothing was lost by deleting the variants.

### MR-8a: the TODOs were not one kind of thing, and the plan's fix was wrong for four

The plan said to rewrite all 15 to call `taskRepo.Xxx(...)`. `buildTaskContextMenu` is a
pure UI component that receives `TaskMenuActions` and has no repository, and must not gain
one — so the correct next step for every unwired item is a nullable callback on
`TaskMenuActions`, invoked here and wired at the call site.

`Print` and `Share` are platform actions; no use case is the right answer for either, and
the markers now say that rather than implying a thin domain wrapper the `PassThroughUseCase`
rule forbids. `Archive` was stale in the other direction — the repository call landed with
the write-layer sweep, so only the callback was missing. The `set_dependencies` marker
recorded a real gap: the action fires with an empty set, so an existing dependency set is
cleared on save.

## Declined, with reasons

| Item | Why not |
|---|---|
| MR-1 dispatcher change | already fixed by `560f3bf8`; the plan would have regressed it |
| MR-2 coordinator + slots | already rejected in `2026-09-26-pr24-rescope`; the real defect was a subscription restart |
| MR-3 `assertCanWrite` on `AttachmentRepository` | the only caller-supplied-entity method has no callers |
| MR-5 instrumented-test JUnit 5 port | CI has no `connectedAndroidTest` step, so the tests never run |
| MR-6 repository package move | the convention was never settled; moving two of eight adds a fourth state |
| MR-7 `SettingsContributor` → `FeatureSlot` | no defect behind it; needs a suspend change to an interface 7 slots implement |
| MR-8 digest budget | 89 lines are Critical and 1851 are per-tag content — a budget question, not a generator defect |
| MR-8 ADR coverage for 12 modules | two are small enough that their absence is the honest record |

## Bugs the roadmap found

Five, in roughly descending severity:

1. **Two cross-user read leaks** — `TagsRepositoryImpl.observeTag` and
   `TagGroupRepositoryImpl.observe` used unscoped DAO reads; the tag query also lacked the
   `deleted_at` filter, so soft-deleted tags leaked too.
2. **A note id that never existed** — the ViewModel generated a second id, discarded the
   repository's, and returned its own, so "Create your first note" opened the editor for a
   note that was not created.
3. **A crashing detekt rule** — `psiUtil.collectDescendantsOfType` is inlined, and its
   synthetic class fails to load in detekt's classloader; the exception aborted the whole
   run *and* left the previous report on disk, so failures read as passes.
4. **A lint guard invisible to a warm daemon** — the Gradle daemon caches the resolved
   detekt plugin classpath, so rule edits had no effect until `./gradlew --stop`.
5. **Seven deprecated `UpdateTaskUseCase` writes** — closed twice on the basis of a grep
   that matched the replacement form rather than the callers.

## The pattern across the roadmap

Every plan was written from the ADR corpus, and **every one disagreed with the code**:

| Plan said | Code was |
|---|---|
| change the fake's dispatcher to `Default` | already `Unconfined`; the change would have regressed it |
| delete a redundant combine | the combine is the only state mirror; deleting it breaks the screen |
| split a god ViewModel into 5 slots | the split was rejected three days earlier and the replacement already applied |
| add `assertCanWrite` to three repositories | two have no callers; the third needs a contract change |
| rewrite 15 TODOs to `taskRepo.Xxx` | the builder has no repository by design, and four are platform actions |
| make DI baseline entries disappear after a file split | the limits are per-declaration (11), not per-file (25) |

The lesson is not that plans are unreliable — it is that **an ADR describes the tree at the
moment it was written, and a roadmap built from ADRs inherits their staleness**. Four ADRs
in this corpus had already been superseded by changes the ADRs did not record.

Three rules came out of it, all now in the decision log: verify a claim about current
behaviour before planning a fix around it; recount what an ADR counts; and read the
compile warnings, because a grep for the identifier you *expect* proves only that the
expectation holds.

## Links

- `2026-09-28-roadmap-status` — the consolidated done/remaining list
- `2026-09-28-mr1-test-virtualization-retro` · `…-mr2-project-detail-retro` ·
  `…-mr3-repository-read-isolation` · `…-mr4-combine-soundness` ·
  `…-mr5-vm-hygiene` — the per-MR retros
- `2026-09-28-detekt-daemon-and-crashing-rule` · `2026-09-28-notes-create-navigation`
- `2026-09-28-mr5-verification` · `2026-09-28-mr6-verification` ·
  `2026-09-28-mr7-mr8-verification` — the three verification passes
