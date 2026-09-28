---
title: "MR-5 verification — two of four items do not survive, and a wrongly-closed finding is open again"
date: 2026-09-28
tags: [retro, tech-debt, verification, coroutines, testing]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Verification of MR-5 before implementation, following the pattern established by MR-1
through MR-4: read the code first, because the plan was written from the ADR corpus and the
corpus has been wrong every time.

**Outcome: one item is valid as written, one is valid but its fix is wrong, one is based on
a misidentified file, and one uncovered a finding this project had twice declared closed.**

## 1. `NotesListViewModel` `Dispatchers.Unconfined` — real, but the plan's fix is wrong

The three sites are real (`NotesListViewModel.kt:172, 185, 194`). The problem is not the
dispatcher itself but an inconsistency inside one file:

```kotlin
scope.launch { … }                     // :55  — the init collector, scope's dispatcher
scope.launch(Dispatchers.Unconfined)   // :172, :185, :194 — action handlers
```

The VM's default scope is `AutoCloseableCoroutineScope()`, which `createBackgroundScope()`
backs with `Dispatchers.Default` on both jvmMain and androidMain. So `:55` runs on
Default while the three action handlers run on the caller's thread.

**The plan's fix — inject a `CoroutineDispatcher` defaulting to `Dispatchers.Main` — is
not a mechanical change:**

- `Dispatchers.Main` appears **nowhere** in production `commonMain` / `jvmMain` /
  `androidMain` today. This would be its first use, and it changes the thread model on
  both platforms (EDT on desktop via `kotlinx-coroutines-swing`, UI thread on Android).
- `Unconfined` runs inline; `Main` always posts. For `createNoteWithTitle(title)` that is
  a behavioural difference, because the method returns an id **synchronously** and
  `NotesListScreen.kt:100` uses it:
  `onCreateNote = { title -> NoteId.fromString(viewModel.createNoteWithTitle(title)) }`.
- Nothing enforces the convention either. `AGENTS.md` says never hardcode
  `Dispatchers.Default` / `Unconfined` in production VMs, but there is no detekt rule for
  it — the rule set has no dispatcher check.

**Correct scope for this item:** decide whether those three writes belong on the UI thread
or off it, and make the file agree with itself. Both defensible; neither is the plan's
"inject Main". If they move to the scope's dispatcher, `createNoteWithTitle`'s synchronous
id return needs its own look — it is a latent ordering bug either way.

## 2. Four JUnit 4 instrumented tests → Jupiter — based on the wrong file

`2026-09-25-remaining-test-debt` O3 is about
`shared/src/androidHostTest/kotlin/…/core/di/AndroidDiGraphTest.kt` and Robolectric. That
file is gone; the ADR itself records the Robolectric suite as deleted.

The four `@RunWith(AndroidJUnit4::class)` classes are in
`androidApp/src/androidTest/kotlin/com/singularity/todo/ui/` — **instrumented tests that
need a device**, not Robolectric. Two further facts:

- **CI never runs them.** `ci.yml` runs `:shared:jvmTest`, `:desktopApp:test`,
  `:androidApp:assembleDebug`, detekt and kover. There is no `connectedAndroidTest` step.
- JUnit 5 on Android needs `AndroidJUnit5ClassRunner`, which is experimental, on top of a
  JUnit 4-based instrumentation runner. This is a port with its own risk, not a hygiene
  item in a mini-batch.

**Verdict: not MR-5.** Either leave the tests as they are — they are dormant, not
broken — or file it as its own MR with a device in the loop. Worth knowing that they have
almost certainly never been executed.

## 3. `repeat(3) { advanceUntilIdle() }` — valid, verified

`SettingsViewModelTest` has 31 of them. Replacing every occurrence with a plain
`advanceUntilIdle()` passes: **9 tests, 0 failures**. The workarounds died with MR-1, which
is what MR-1's retro predicted but could not confirm until the file suite ran.

`LlmUsageRecorderTest.kt:82` has a `repeat(3)` too, but it is unrelated — not a settling
loop.

## 4. The deprecation sweep found a finding twice declared closed

Compiling production and reading the warnings is what matters here, and it contradicts two
entries this project wrote.

**R7 is open.** `2026-09-28-mr2-retro-findings` recorded `UpdateTaskUseCase.invoke(task)`
as *"still deprecated and still used"*, and then a later pass marked it **closed** on the
evidence that *"a clean `:shared:compileKotlinJvm` emits no deprecation warning for
`invoke(task)`"*.

That is false. **7 call sites across 5 slot files** still use the deprecated form:

| Slot | Count |
|---|---|
| `TaskDraftSlot` | 2 (`:68`, `:79`) |
| `TaskAiSlot` | 2 |
| `TaskEntitySlot` | 1 |
| `TaskCompletionSlot` | 1 |
| `TaskChildrenSlot` | 1 |

Both errors were mine. The closure was based on a grep for `updateTask.invoke`, which
finds the two-argument form `updateTask.invoke(id) { … }`. The offending sites are
`deps.updateTask(task.copy(…))` — single-argument, no `.invoke` — so the grep matched
almost nothing, and the compile warnings were never inspected.

This is the exact failure mode the project has recorded repeatedly, and it is worth naming
precisely: **a grep for the identifier you expect is not a check.** A compile warning is
the evidence, and it was available the whole time.

The underlying risk is real and is what R7 originally described: these sites read a task
from a flow, `copy()` one field, and write the whole entity back, so a concurrent remote
edit to any other field is silently reverted. `invoke(id) { copy(…) }` re-reads and is the
fix.

**The other 74 warnings are mostly not actionable now.** The largest clusters are
entangled with work that is explicitly deferred:

- 12 in `AppDestination` — deprecated Nav2-era `Inbox` / `Today` / `Upcoming` /
  `TaskDetail` / `TaskDetailCreate`. **Still referenced**, 2–5 call sites each, so removal
  is a migration to the Nav3 equivalents, not a deletion.
- 7 in `CalendarEventMapper` — `kotlinx.datetime` deprecations, which is the deferred R26
  `Instant` migration. Doing them now means doing that migration.
- 3 × 6 in the slot files — `'when' is exhaustive so 'else' is redundant`. Genuinely
  cheap, and unrelated to the deprecated API.

## Revised MR-5

1. Migrate the 7 `deps.updateTask(task.copy(…))` sites to `invoke(id) { copy(…) }`, and
   close R7 with the compile output as evidence.
2. Remove the 31 `repeat(3)` wrappers in `SettingsViewModelTest`.
3. Remove the redundant `else` branches in the six slot files.
4. `NotesListViewModel` — decide the thread model explicitly rather than importing
   `Dispatchers.Main` by default; keep it out of this MR if the decision is not quick.
5. Instrumented-test migration — file separately, with a note that CI does not run them.

## Rules

- **Read the compile warnings. A grep for the identifier you expect proves nothing.** Two
  closures in this project rested on greps that matched the *replacement* form.
- **An ADR that names a file is evidence the file was read.** O3's Robolectric path and
  MR-5's "4 JUnit 4 tests" are the same claim about different files.
- **Check whether CI runs the thing before scheduling work on it.**

## Links

- `2026-09-28-mr2-retro-findings` — R7, whose closure is corrected here
- `2026-09-28-roadmap-status` — consolidated done/remaining list, updated
- `2026-09-25-remaining-test-debt` — O1 (the `Unconfined` sites) and O3 (Robolectric, now
  obsolete as written)
- `2026-09-28-mr1-test-virtualization-retro` — what made item 3 possible
