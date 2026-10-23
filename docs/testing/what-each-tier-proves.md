# What each tier proves

Two UI tiers run in this repository, and they answer different questions. This
page says which is which, so the split is written down rather than inferred from
which suite a test happens to live in.

| Tier | What runs | Cost | What it can see |
|---|---|---|---|
| **device** | `:androidApp:connectedDebugAndroidTest` — one class (`AppStartupTest`) as an advisory smoke on every pull request, the whole instrumented suite nightly | ~10 minutes and an emulator | the real Android runtime |
| **JVM** | `:desktopApp:test` — the Compose Multiplatform suite on the JVM | seconds | the shared UI and the semantics tree it produces |

The device tier's own runbook is [android-instrumentation-runbook.md](./android-instrumentation-runbook.md);
the Maestro side of the Android story is [android-tier-runbook.md](./android-tier-runbook.md).

## The three questions of the device smoke

`AppStartupTest` asks three things, and they do not have the same home:

1. **Did the auth bypass reach the build?** — device only. `BuildConfig.TEST_AUTH_BYPASS`
   is a flag of the `androidApp` module, and the APK is what carries it.
2. **Is the app not on the sign-in screen?** — device only, for the real entry
   point. The JVM harness fakes the auth ports (`testPlatformModule`), so it never
   renders the sign-in screen and cannot answer this for Android.
3. **Do the shell's test tags reach the accessibility tree?** — **split**. This is
   the question the JVM tier now answers in part.

## The split inside question 3

"Do the test tags reach the tree?" is two claims, and only one of them travels:

- **(a) the tag reaches the semantics tree** — portable. Every platform's
  automation addresses a Compose `testTag` directly, and the JVM tier renders the
  same shared UI (`shared/src/commonMain`). Covered now by
  `com.singularity.todo.ShellTestTagReachabilityTest`, in seconds.
- **(b) the tag reaches the *accessibility* tree, as a resource id** — Android
  only, and it stays on the device:

  - `testTagsAsResourceId` is an Android semantics property. The desktop actual of
    `Modifier.mapTestTagsAsResourceIds()` is a no-op
    (`shared/src/jvmMain/kotlin/com/singularity/todo/core/ui/TestTagResourceId.jvm.kt`),
    because desktop automation reads test tags straight from the semantics tree.
  - the Android shell's own tags are applied in
    `shared/src/androidMain/kotlin/com/singularity/todo/AndroidShellNav3.kt`
    (`nav_tab_<slug>`, `nav_menu_button`, `tasks_fab`). The JVM tier renders the
    *desktop* shell (`shared/src/jvmMain/.../DesktopShellNav3.kt`), which carries no
    test tags at all — its drawer entries and its FAB are addressed by content
    description. The desktop tier cannot render the Android shell, so it cannot
    assert anything about the bottom bar's tags.
  - a tagged control inside a **window-owning surface** (a dialog, a modal sheet, a
    popup) needs `mapTestTagsAsResourceIds()` on its content or it is invisible to
    UiAutomator — a defect with no desktop counterpart, because on desktop there is
    no resource-id layer to miss.

So `AppStartupTest.the_shell_is_addressable_by_test_tag` is **not** redundant with
the JVM test. It should keep asserting (b): that the Android shell's tag is
findable as a UIAutomator `resource-id`. What changes is where a regression in (a)
is found — in seconds on the JVM, not in ten minutes on the emulator.

## What the JVM tier now answers

`com.singularity.todo.ShellTestTagReachabilityTest` (in
`desktopApp/src/jvmTest/kotlin/com/singularity/todo/`) boots the production `App()`
through `runDesktopAppTest` — the real composable and the real Koin graph, with
fakes only for the platform ports — and asserts:

- the shell rendered: its chrome is on screen and `Today` is the selected tab;
- the shared UI's test tags reach the **merged** semantics tree, which is the tree
  an accessibility layer reads. The merged lookup is the same claim the device
  makes with `findObject(By.res(tag))`, one step before Android turns the tag into
  a resource id;
- a control case: the walk over the **raw** tree reports the shared tags and does
  not report `nav_tab_today` — the Android bottom bar's tag, which cannot be here.

Two of the asserted tags are constant and two are derived from the task's title
(`slug`), so the list covers both a static and a computed tag.

The class is `@Tag("slow")` — it mounts the Compose harness — so it runs in the nightly's
`-Ptest.population=full` selection, and in neither the default local run nor a pull
request's `-Ptest.population=fast` selection:

```bash
./gradlew :desktopApp:test -Ptest.population=full   # includes it
./gradlew :desktopApp:test --tests '*ShellTestTagReachabilityTest*' -Ptest.tags=slow
```

## What the emulator still uniquely covers

- the real entry point: `MainActivity`, the version gate, the auth restore;
- `BuildConfig.TEST_AUTH_BYPASS` — the build flag, and the sign-in screen it
  guards;
- the Android secure storage (`EncryptedSharedPreferences` over the Android
  Keystore) — the desktop actual is a different implementation
  (`shared/src/jvmMain/.../JvmSecureStorage.kt`), so the two tiers never exercise
  the same code;
- notifications: the Android notification manager and the runtime permission
  requester. The desktop actual shells out to `notify-send(1)`, and the test
  harness disables even that;
- the `testTag` → UIAutomator `resource-id` mapping, and the window-owning
  surfaces that need `mapTestTagsAsResourceIds()` to opt into it;
- the Android shell's own tags, which live in `androidMain`.

## What the emulator no longer has to

- be the place where a *shared* tag is looked for. A dropped
  `Modifier.testTag(...)` in `shared/src/commonMain`, or a tag that moved onto a
  node that merges away, is caught by the JVM tier in seconds.

## What the pull request does not prove

The tiers above are test tiers. There is a third question a pull request used
to answer and no longer does: **how much of the application is covered?**

The app coverage floor (INSTRUCTION / BRANCH / LINE) is proved by the
**coverage tier**, which is the trunk's job: the `coverage` job in `ci.yml`
runs the suite under the Kover agent, on a push to the trunk, on the schedule,
and on a pull request that asks for the full population. It does not run on an
ordinary pull request.

The reason is the order of the two facts: a floor describes a measurement, and
a pull request does not take that measurement. The pull-request path runs the
suite **without** the Kover agent — measured, not assumed — so a coverage
number taken there would be about a different build than the one the floor is
declared against. A pull request that failed a coverage floor would be failing
on a measurement it never took, and a pull request that passed one would be
passing on a number nobody computed.

So a pull request proves: the tests it runs are green, the affected tests are
green, detekt is clean, and the gates can still fail. It does not prove the
coverage floor — the trunk does, and the floor's failure there is what makes
it a floor.

The floor itself, and the two populations it is declared for, live in
`settings.gradle.kts`; the decision is ADR
`2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md`.

## The caveat, stated plainly

The JVM tier's shell is the **desktop** shell. It shares the *screens* with
Android, not the shell chrome. So the JVM test proves "the shared UI's tags reach
the semantics tree" — it does not prove "the Android bottom bar's tag reaches the
accessibility tree", and nothing on this tier can.
