---
name: singularity-todo-desktop-compose-ui-tests
status: active
description: Write and debug JVM Desktop Compose UI tests in desktopApp/src/jvmTest. Use when adding a flow test that mirrors a Maestro flow, when a desktop test fails on a missing node or an ambiguous selector, or when a test needs seeded data. Covers the runDesktopAppTest harness, the in-memory platform module, the Koin load order, and the shell's contentDescription selectors.
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

**Seed fixtures through the repository, not the UI.** It keeps a flow's
precondition independent of another flow's save path, so a failure localises.

**Use `androidx.compose.ui.test.v2.runDesktopComposeUiTest`.** The v1 overload is
deprecated in Compose 1.12. v2 runs composition on a `StandardTestDispatcher`,
which works fine against the app's `Dispatchers.Default` background scopes.

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

Two switches, both opt-in and both forwarded into the test JVM by
`desktopApp/build.gradle.kts`:

```bash
./gradlew :desktopApp:test --tests '*MyFlowTest' -Dsingularity.ui.dumpTree=true
./gradlew :desktopApp:test --tests '*MyFlowTest' -Dsingularity.test.log=true
```

The first prints the semantics tree between `=== SEMANTICS TREE ===` markers in
`build/test-results/test/TEST-<class>.xml`. The second routes Kermit to stdout at
verbose severity. **Dump the tree; never guess a selector** — guessing is the
largest source of wasted turns here.

Reading a failure:

| Message | Means | Do |
|---|---|---|
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

If a test passes alone but fails in the suite, suspect shared state before the
selector — the usual culprit is a process-global mutation. See
`debugging-investigation` for the wider playbook.

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
