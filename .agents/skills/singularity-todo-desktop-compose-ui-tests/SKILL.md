---
name: singularity-todo-desktop-compose-ui-tests
status: active
description: Write and debug JVM Desktop Compose UI tests in desktopApp/src/jvmTest. Use when adding a flow test that mirrors a Maestro flow, when a desktop test fails on a missing node or an ambiguous selector, when a test HANGS or the VM never leaves Loading, or when a test needs seeded data. Covers the runDesktopAppTest harness, the failure diagnostics bundle, the in-memory platform module (DAO parity guard), the Koin load order, and the shell's contentDescription selectors.
---

# Desktop Compose UI tests

Desktop flow tests live in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/`
and mount the **production `App()`**, so a test is a real mirror of a Maestro
flow rather than a screen rendered in isolation.

## Running

```bash
./gradlew :desktopApp:test                                  # everything
./gradlew :desktopApp:test --tests '*CalendarFlowTest'      # one suite
```

`desktopApp/build.gradle.kts` maps the `jvmTest` source directory onto the `test`
task, so the task name is `:desktopApp:test`, not `:desktopApp:jvmTest`.

## The harness

```kotlin
@Test
fun my_flow() = runDesktopAppTest { koin ->
    tapTab("Inbox")
    awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
}
```

`runDesktopAppTest(overrides, test)` builds a per-test `KoinApplication` and
mounts `App()`. It hands the `Koin` to the block so a test can seed or inspect:

```kotlin
koin.seedTask(title = "Buy milk", dueDate = todayInSystemZone())
koin.get<TaskRepository>().observeAll().first()
```

Helpers in `test/helpers/`:

| Helper | Use |
|---|---|
| `tapTab(label)` | Opens the drawer, activates a tab or menu entry, waits for it to close |
| `openDrawer()` | Drawer open, idempotent — a blind hamburger click would *close* it |
| `assertCurrentTab(label)` | Asserts the drawer's `Selected` semantics |
| `goBack()` | Pops the shell stack via the back arrow |
| `awaitTag(tag)` | Waits for a node after an async write, then returns a handle |
| `awaitTagGone(tag)` | Waits until a node is gone — for asserting a *disappearing* thing |
| `awaitAnyDisplayed(tag)` | Waits until *some* matching node is on screen — for pagers where the tag is composed several times |
| `tasks(koin)` | `TasksRobot`: `given(due, title)` / `givenUndated(title)` / `assertInAgenda` / `open` |
| `seedTask(...)` / `seedBuyMilk()` | Writes a task through the repository |
| `DesktopShell.TABS` / `.MENU_ENTRIES` | The drawer's labels |

## Rules

**Select the shell by contentDescription, not testTag.** The desktop drawer
entries and the FAB carry no testTag, unlike the Android bottom bar. Use
`DesktopShell.HAMBURGER` ("Menu"), `DesktopShell.BACK` ("Back"),
`DesktopShell.FAB_ADD_TASK`, or a label from `DesktopShell.TABS` / `.MENU_ENTRIES`.

**Assert arrival on content, not on the drawer entry.** Selecting a drawer entry
closes the sheet, so the entry is off-screen afterwards and `assertIsDisplayed`
fails on a correct app. Use `assertCurrentTab` or a node the destination renders.

**Never assert on a top-bar title to identify the screen.** On a fresh database
the title text matches three nodes — the agenda section header, the title, and
the off-screen drawer entry. `assertCurrentTab` is the unambiguous form.

**Wait after any write.** Saving is asynchronous; a click issued straight after a
save lands before the row is in the tree. Use `awaitTag`.

**Wait for arrival, and wait for departure.** Navigation commits asynchronously
and screens gate their content on a `Loading` state: a destination's real
affordance simply is not in the tree until its VM has data, and the outgoing
screen stays composed until the incoming one resolves. So:

- to assert a screen *arrived*, `awaitTag(...)` the affordance, then assert on it;
- to assert something is *gone because of what you just did*, use
  `awaitTagGone(...)` — `assertDoesNotExist` checks once after auto-sync and
  races the transition, failing intermittently under machine load.

"`assertDoesNotExist` is still right when the node was never there. And never
pick a node by index in a pager: `HorizontalPager` keeps neighbouring pages
composed, so `[0]` is composition order, not what is on screen — a month-edge
day pads into the neighbouring page and the ordering flips on the 1st of the
month. Use `awaitAnyDisplayed`. And a positive
and a negative assertion on the same `when` branch in one composable (e.g.
`PLAY_BUTTON` displayed, `PAUSE_BUTTON` not) are atomic — no waiting needed.

This is not hypothetical: `ProjectsFlowTest.opening_a_project_reaches_its_detail_screen`
was flaky at roughly 1 run in 2 for exactly this reason — it asserted
`PROJECT_DETAIL_QUICK_ADD` (which only exists in the detail screen's `Content`
state) immediately after clicking the card, then asserted the card was gone.

**Run `checkA11y` on every flow you touch.** `runDesktopAppTest(checkA11y = true)`
fails when the merged semantics tree contains a clickable node that announces
nothing — no text, no `contentDescription`, no `onClickLabel`, no `testTag`. The
whole desktop flow suite currently passes with it on. Editable fields are exempt:
a screen reader announces them as edit boxes, and their `OnClick` is the
focus affordance Compose adds to every `TextField`, not an unnamed button.

**Give `due` explicitly when seeding a task.** `tasks(koin).given(due = …)` has no
default for `due` on purpose. The undated path is an open question, not a settled
one (`2026-09-30-nodate-root-cause.md`), so a fixture that defaults to undated —
or quietly to today — puts the test on ground that has not been decided. Ask for
the undated case by name, `givenUndated(...)`, so `grep givenUndated` lists every
test standing on it.

**Seed fixtures through the repository, not the UI.** It keeps a flow's
precondition independent of another flow's save path, so a failure localises.
`seedTask` reads the id from `ProfileAwareCurrentUser` rather than hardcoding
one, because the write path re-stamps it and a hardcoded id would be stamped
over anyway.

**Every fake defaults to `TestUsers.DEFAULT`.** `testTask()`, `testNote()`,
`FakeSettingsRepository` and `FakeProfileAwareCurrentUser` all resolve to the
same id. If you add a fixture, default it to `TestUsers.DEFAULT` too — the
defaults disagreed once (`anonymous` vs `test-user`) and nothing failed loudly,
because the write path silently re-stamps. Multi-user tests pass both ids
explicitly.

**Use `androidx.compose.ui.test.v2.runDesktopComposeUiTest`.** The v1 overload is
deprecated in Compose 1.12. v2 runs composition on a `StandardTestDispatcher`,
which works fine against the app's `Dispatchers.Default` background scopes.

**A tag ships with the flow that uses it, or not at all.** A constant in
`TestTags.kt` that no composable applies is worse than a missing one: a flow
written against it fails with a bare "could not find any node" and nothing points
at the real problem. `TestTagsWiringTest` fails the build on an unapplied
constant, and also fails on a *stale* allowlist entry — so when a tag becomes
applied, removing it from the allowlist is enforced, not optional.

**Do not build a tag from localized text.** `TestTags.taskAction(action)` takes
a stable id, not the label. A label-derived tag breaks in every locale but the
one it was written in.

## Two load-order facts that are easy to get wrong

1. **`testPlatformModule()` must load after `domainModule()`.** Koin resolves
   duplicate definitions last-wins. Loading it first let `coreModule()`'s
   `SupabaseAuthRepository` override the fake; its `userId` moves from `anonymous`
   to a generated ULID shortly after startup, which orphans anything written in
   that window and makes the row invisible to every later read.

2. **Do not redirect `user.home`.** It is process-global, so two tests swapping it
   race and the loser fails with `SQLiteException` code 14 (`SQLITE_CANTOPEN`) —
   only in a full-suite run, never when the test runs alone. `FakeAppDatabase`
   removes the need.

## Debugging

**When a test fails**, the harness bundles diagnostics automatically into
`desktopApp/build/diagnostics/<TestClass>/attempt-N/`:

| Artifact | What it contains |
|---|---|
| `db-state.txt` | FakeAppDatabase dump |
| `kermit.log` | Kermit log (logcat equivalent) |
| `coroutines.txt` | Coroutine snapshot — all coroutines, states, stack traces |
| `screenshot.png` | Last composed frame |

Read them before reading source — a screenshot answering "what was actually on screen"
in one glance beats an hour of source review. The semantics tree also rides on the
failure itself as a suppressed exception, so it is in the test XML too.

Capture order (hang-proof first): db-state → kermit → coroutines → screenshot.
If the screenshot path hangs, the first three artifacts are already written.

Two contracts baked into the bundle:

- The frame clock is frozen (`mainClock.autoAdvance = false`) before the screenshot.
  A never-idle composition — an indeterminate spinner on an unresolved state, a Koin
  error retried per frame — would otherwise hang `captureToImage` forever (it blocks
  on `EventQueue.invokeAndWait`, which no coroutine timeout can cancel). The captured
  frame may be mid-transition; it is diagnostic evidence, not a visual baseline.
- `-Dsingularity.test.screenshot=false` skips the capture entirely.

**Opt-in switches** (forwarded into the test JVM via `providers.systemProperty` —
provider reads are configuration-cache inputs, so they work on cache reuse where a
plain `System.getProperty` snapshot would silently freeze stale values):

```bash
./gradlew :desktopApp:test --tests '*MyFlowTest' -Dsingularity.ui.dumpTree=true
./gradlew :desktopApp:test --tests '*MyFlowTest' -Dsingularity.test.log=true
```

The first prints the tree between `=== SEMANTICS TREE ===` markers; the second
routes Kermit to stdout at verbose severity. **Dump the tree; never guess a
selector** — guessing is the largest source of wasted turns here.

Reading a failure:

| Message | Means | Do |
|---|---|---|
| "Tag '…' is not in the semantics tree" | `awaitTag` timed out | The explainer lists nearby tags; dump the tree if you need the full picture |
| "found N nodes that satisfy…" | Ambiguity — several nodes match | Narrow with a role, a tag, or a different assertion |
| `ComposeTimeoutException` | Value never arrived | Check the data layer, not the selector |

When the UI disagrees with the data, resolve the repository straight from the
harness's `Koin` and ask it what it holds:

```kotlin
println(koin.get<TaskRepository>().observeAll().first())
```

"The repository returns it and the screen does not show it" is a different
investigation from "the repository returns nothing", and guessing between them
is the expensive mistake.

**For a HANG** (gradle never returns) — do not re-run and hope. `jstack` the Gradle
test worker while it is stuck and follow the hang protocol in
`debugging-investigation` (step 4): test thread in `EventQueue.invokeAndWait` plus a
100%-busy `AWT-EventQueue-0` in `RenderNode_nDrawInto` means a never-idle
composition; for silent coroutine death (VM stuck on its initial state, no events),
read `build/diagnostics/<TestClass>/coroutines.txt` — the coroutine snapshot shows
the last observed stack trace of every active coroutine and flags the one that died.
The headless probe pattern — build `domainModule()` + `testPlatformModule()` in a plain
`runTest`, construct the VM directly, wait for `Loaded` — splits "VM never resolves"
from "screen does not render it" in one run; see
`TaskDetailCoordinatorGraphTest` for the shape.

## Graph-level guards

Two tests keep the harness graph honest; keep them green when touching DI:

- `TestPlatformModuleParityTest` — every DAO `platformModule()` binds must also be
  bound by `testPlatformModule()`. A missing DAO throws
  `NoDefinitionFoundException` inside composition, which Compose retries every
  frame: it presents as a hang, not an error. Registration-only comparison — no
  real files or ports are touched.
- `TaskDetailCoordinatorGraphTest` — the detail VM, built from the real DI graph,
  must reach `Loaded` in real time. Pins against a combine coroutine dying before
  its first emission: no error state, no event, just an eternal loading shell.
  Companion rule for any VM: **a property read from `init` must be declared before
  it** — Kotlin initialises properties in declaration order, and an init block sees
  a later property as null (no intrinsic check inside the same class).

If a test passes alone but fails in the suite, suspect shared state before the
selector — the usual culprit is a process-global mutation. See
`debugging-investigation` for the wider playbook.

## Known gap: undated tasks

A task written through the repository is returned by
`TaskRepository.observeAll()` while the agenda renders "No tasks", and stays
empty across a tab switch that recreates the ViewModel. Dated tasks render
normally.

**The domain layer is exonerated** — `AgendaNoDateRegressionTest` is green, and
`AgendaPresets.Inbox` does declare a "No Date" section. Steps 2–4 of the bisect
(Fake⇄Room contract, the ViewModel `combine`, `scopedUserId`) are not done; see
`deferred-backlog.md#nodate-steps-2-4` before writing a fixture around this.

Until then: fixtures carry a due date, and the create flow stops at the editor.

Two contracts worth knowing because the obvious reading is wrong:

- `RelativeBucket.NoDate` maps to `DateRange(1970-01-01, 1970-01-01)`, but
  `SelectorMatcher` special-cases it with a direct `task.dueDate == null` branch
  and never uses the range. Reading the enum mapping sends you at the wrong layer.
- **The Inbox preset does not filter by completion.** `watchActive` selects on
  `archived_at IS NULL` only, so a completed task is still listed, badged
  `Completed`. Only archiving removes a row.

## Adding a flow

1. Find the Maestro counterpart under `Maestro/flows/`.
2. Create `feature/flows/<feature>/<Name>FlowTest.kt`.
3. Map the Android selectors — see the rules above for what changes on desktop.
4. Assert on content the destination renders.
5. Run it, then the full suite: a selector that only works in isolation usually
   means shared state, not a bad selector.

## See also

- `debugging-investigation` — the test-failure debugging loop and its traps
- `singularity-todo-maestro-flows` — the Android suite these mirror
- `singularity-todo-koin-dsl` — Koin 4.x DSL
- `singularity-todo-test-tag-strategy` — `@Tag("slow")` filtering
- `singularity-todo-test-flaky-prevention` — why fixtures use real dates
