---
name: singularity-todo-desktop-compose-ui-tests
status: active
description: Write and debug JVM Desktop Compose UI tests in desktopApp/src/jvmTest. Use when adding a desktop compose UI test — a flow test that mirrors a Maestro flow or a component test that mounts one screen in isolation — when a desktop test fails on a missing node or an ambiguous selector, when a test HANGS or the VM never leaves Loading, when a long press does not fire, when a ModalBottomSheet renders below the window, or when a test needs seeded data. Covers the runDesktopAppTest harness, the failure diagnostics bundle, the in-memory platform module (DAO parity guard), the Koin load order, the shell's contentDescription selectors, and the component-test rules (window size, sheet window, long press).
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
    assertTagDisplayed(TestTags.taskItem("Buy milk"))
}
```

**Never write `awaitTag(...).assertIsDisplayed()`** (#569): `awaitTag` is a
*presence* wait — a dialog or popup node can be in the semantics tree before
its window has laid out (bounds 0×0), and the one-shot `assertIsDisplayed`
right after fails. The display wait lives in the helpers:

| Helper | Use for |
|---|---|
| `assertTagDisplayed(tag)` | a tagged node is visible |
| `assertTextDisplayed(text)` | a text node is visible |
| `assertContentDescriptionDisplayed(desc)` | an a11y-node is visible |
| `assertNodeDisplayed(matcher, description)` | composite matchers, e.g. `hasText("Edit") and hasClickAction()` |

`HarnessConventionTest` enforces this: raw `.assertIsDisplayed()` outside
`test/helpers` fails the suite (the one exemption is CreateTaskFlowTest's
negative control).

`runDesktopAppTest(overrides, test)` builds a per-test `KoinApplication` and
mounts `App()`. It hands the `Koin` to the block so a test can seed or inspect:

```kotlin
koin.seedTask(title = "Buy milk", dueDate = todayInSystemZone())
koin.get<TaskRepository>().observeAll().first()
```

Helpers in `test/helpers/`, split by role (all top-level extensions on
`DesktopComposeUiTest`, so they import individually):

| File | Holds |
|---|---|
| `DesktopNavigation.kt` | `tapTab`, `openDrawer`, `goBack`, `assertCurrentTab`, `DesktopShell` |
| `DesktopAssertions.kt` | `await*` and `assert*`, plus `TIMEOUT_MS` and `explainMissingTag` |
| `DesktopInteractions.kt` | `click*`, `longClickTag`, and `typeIntoTag` / `clearAndTypeIntoTag` |
| `TasksRobot.kt` | `tasks(koin)` — the `given`/`assert`/`open` robot |
| `DesktopAppHarness.kt` | `runDesktopAppTest`, `seedTask`, `seedBuyMilk` |

`TIMEOUT_MS` lives in `DesktopAssertions.kt` because the wait helpers are its only
real users — a nav-only timeout constant invites someone to wait on navigation
with an assertion-tuned budget.

## Component tests: one screen, mounted alone

A flow test mounts the whole `App()`. A **component test** mounts one composable
and drives it — that is the right shape for a sheet, a grid or a card whose
behaviour does not need the shell around it. The helper is
`runIsolatedComposeTest` (in `test/helpers/IsolatedComposeTest.kt`):

```kotlin
@Test
fun `three tapped days are confirmed`() = runIsolatedComposeTest(width = 420, height = 1600) {
    var confirmed: Set<LocalDate>? = null
    setContent {
        MultiDatePickerSheet(
            initialDates = emptySet(),
            onConfirm = { confirmed = it },
            onDismiss = {},
            today = today,
            maxDates = TaskSeriesPlanner.MAX_DATES,
        )
    }

    clickTag(TestTags.MultiDatePicker.day("2026-10-05"))
    assertTextDisplayed("Выбрано: 3")
}
```

Three things about it are not obvious and each one costs an hour the first time.

### The window size is part of the test

`runIsolatedComposeTest(width = 1024, height = 768)` — the defaults. The cells of
a month grid are a seventh of the window's width, so a grid that is wider than
it is tall puts its lower rows **below the fold**: the test cannot click them,
and neither can a person on a short screen. Narrow the window (and raise it) so
the whole grid fits:

| The component wants | The window |
|---|---|
| a month grid | narrow and tall — `width = 420, height = 1600` |
| a row or a card | the defaults are fine |

A click on a node that is off-screen does not fail with "not found" — it fails
with a timeout, which reads like a broken selector. When a node cannot be
clicked, check the window before you check the tag.

### A `ModalBottomSheet` is drawn in its own window

A sheet is a separate window with a **fixed height of about 1080** — it does not
follow the test window. On a 768-pixel-tall window the sheet's content ends up
*below* the window: the node is composed, and it is not on screen. The window of
the component test has to be tall enough to hold the whole sheet, and narrow
enough that its content does not stretch across a metre of screen:

```kotlin
runIsolatedComposeTest(width = 420, height = 1600)   // a sheet's own window fits
```

The same rule applies inside a flow test: the sheet's window is the shell's
window, so a sheet whose content does not fit is a sheet a person cannot use
either — that is a finding about the screen, not about the test.

### A long press does not fire from a real gesture

A long press is recognised by a timeout **inside the gesture detector**, and
that timeout runs on the clock of the composition — not on the clock the desktop
test drives. A press held for a thousand milliseconds of test time is a press
the detector has not seen the end of yet, so `performTouchInput { longClick() }`
never fires here. Use the semantic action, which is the same handler the gesture
ends up calling:

```kotlin
longClickTag(TestTags.Calendar.day("2026-10-15"))
```

`longClickTag` (`DesktopInteractions.kt`) waits with `awaitAnyDisplayed` and
presses the node that is *on screen*. That last part matters in a pager: a
neighbouring month is composed next to the current one and carries the same day
tag, so a plain lookup finds the off-screen copy first, and a gesture that
starts outside the window presses nothing.

What the test is about here is *which* node answers the long press, not how long
the platform holds a pointer down — the semantic action tests exactly that.

| Helper | Use |
|---|---|
| `tapTab(label)` | Opens the drawer, activates a tab or menu entry, waits for it to close |
| `openDrawer()` | Drawer open, idempotent — a blind hamburger click would *close* it |
| `assertCurrentTab(label)` | Asserts the drawer's `Selected` semantics |
| `goBack()` | Pops the shell stack via the back arrow |
| `awaitTag(tag)` | Presence wait — a node after an async write, then returns a handle. Not a display wait |
| `assertTagDisplayed(tag)` | Presence wait + display wait + assert. The correct form of "is it on screen" |
| `assertTextDisplayed(text)` / `assertContentDescriptionDisplayed(desc)` / `assertNodeDisplayed(matcher, desc)` | Same two waits, for text / a11y / composite-matcher nodes |
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
closes the sheet, so the entry is off-screen afterwards and the node is
genuinely not displayed. Use `assertCurrentTab` or a node the destination
renders.

**Never assert on a top-bar title to identify the screen.** On a fresh database
the title text matches three nodes — the agenda section header, the title, and
the off-screen drawer entry. `assertCurrentTab` is the unambiguous form.

**Wait after any write.** Saving is asynchronous; a click issued straight after a
save lands before the row is in the tree. Use `awaitTag`.

**Wait for arrival, and wait for departure.** Navigation commits asynchronously
and screens gate their content on a `Loading` state: a destination's real
affordance simply is not in the tree until its VM has data, and the outgoing
screen stays composed until the incoming one resolves. So:

- to assert a screen *arrived*, use `assertTagDisplayed(...)` (or
  `assertTextDisplayed`) on the destination's own node — it carries both the
  presence wait and the display wait, unlike a bare `awaitTag` + one-shot
  assert;
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

**Use helpers, not raw selectors.** `onNodeWithTag`, `onNodeWithText`,
`onNodeWithContentDescription`, and `onAllNodesWithTag` belong in `test/helpers/`.
`HarnessConventionTest` enforces this: raw calls outside helpers require a reason
in `EXEMPT_RAW_TAGS`; exemptions without actual raw calls are caught as stale.

## Reading a failure bundle

When a flow test fails, a bundle is written to
`build/diagnostics/<TestClass>/attempt-N/`. Read it in this order:

```
steps.txt        → what the test was doing (step name, detail, duration, OK/FAIL)
screenshot-annotated.png  → bounding-box overlays: red = failed/highlight, gray = others
nodes.txt        → all tagged semantics nodes: tag / text / contentDescription / bounds
tree.txt         → raw semantics tree (unmerged)
kermit.log      → app logs
db-state.txt    → FakeAppDatabase contents
a11y.txt        → (only when checkA11y = true) accessibility violations
```

For a timeout failure, `steps.txt` shows which `awaitTag` timed out and after how
many polls; `nodes.txt` shows what tags *were* present.

New `-D` flags for the suite:

| Flag | Effect |
|---|---|
| `-Dsingularity.test.screenshot=false` | Skip screenshot capture |
| `-Dsingularity.test.a11y=fail` | Make a11y violations fatal (warn + a11y.txt always) |
| `-Dsingularity.test.steps=true` | Write steps.txt for *passing* tests too — feeds `scripts/step-duration-report.py` |
| `-Dsingularity.test.baseline=true` | On passing runs, snapshot `baseline/` (screen + tag inventory); on failures, `nodes-diff.txt` reports which tags appeared/disappeared vs that snapshot |

## Timing profile

```bash
./gradlew :desktopApp:test -Dsingularity.test.steps=true
python3 scripts/step-duration-report.py
```

`tapTab` is the heaviest step (drawer animation, ~0.35s median); `awaitTag` is
near-instant on a healthy screen (p95 ~0.1s). A step whose p95 climbs toward
`TIMEOUT_MS` (5s) is the flake candidate.

## Regression baseline

Run once with `-Dsingularity.test.baseline=true` after a known-good change: each
test class gets a `build/diagnostics/<Class>/baseline/` snapshot (per class, not
per test — the last passing test in the class wins). From then on, any failure
writes `nodes-diff.txt` comparing the tag inventory at failure against that
snapshot: "Disappeared" tags are what the failure removed from the screen, new
ones what it left behind. Semantic diff — stable under animation and
antialiasing noise that breaks pixel comparison.

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
