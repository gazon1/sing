# Test tier ownership

Which tier owns a scenario that is asserted in more than one, and why.

**The rule: one owner per scenario.** The other tiers drop their copy. A scenario that
has to be asserted on two platforms is not two copies of one scenario — it is one
rule with two platform bindings, and the rule is asserted once. Two rows below are
the sanctioned exceptions: the archive-golden row, where one contract runs on two
platforms by construction, and the FAB row, whose device copy duplicates the Maestro
outcome and is named as such.

A copy is only ever kept when its row says why it is kept. "The device tier is the
only one that observes the real thing" is such a reason; "it was written first" is
not.

## The table

| Scenario | Owning tier | Why that tier owns it | The other tier's copy | Tiers it is asserted in now |
|---|---|---|---|---|
| MCP wired toolbox == declared toolbox | desktop JVM | the catalogue is one list, and the desktop graph is the one the shipped desktop app resolves; the check compares the two by resolving the real Koin list | **kept deliberately** — the DI list is hand-maintained per platform (ADR `2026-10-21-the-toolbox-surface-from-the-compiler-down`), because the Koin compiler plugin cannot check a heterogeneous `Tool<*, *>` list and the check is paid in the test suite instead. A tool wired on one platform and forgotten on the other is invisible to a check that reads one list: the Android check is what caught the missing project tools. One rule (the catalogue), two bindings (the two DI lists), each tier asserting its own. | desktop JVM + android host |
| archive golden (crypto, chain, key) | the common rule (`ArchiveCryptoGoldenContract` over the bytes in `ArchiveCryptoGolden`) — and both tiers execute it, which is the exception the rule above allows for | one rule in common code, two platform bindings, two tiers that run it: the four cases live once in a `commonTest` contract and each subclass supplies only its own `ArchiveCrypto`, so a case cannot be added to one platform and forgotten on the other | kept — the sanctioned exception the rule above allows for | desktop JVM + android host |
| record retention == policy | desktop JVM | the window is one common constant (`ToolCallRecordPolicy.RETENTION_DAYS`) that both writers delete on, and the policy is one file; the JVM tier asserts the constant against the file, which covers the JVM writer and the Android one | **removed** — the Android copy asserted the same constant in a second tier | desktop JVM |
| notifier permission matrix | desktop JVM | the matrix is a rule, not a measurement: which cells hold is the same on every device, so the rule lives in common code (`NotificationSupport`) and the JVM tier asserts every cell | **removed** — the Android class keeps only what is about the port: that the grant is read per call, and that a denied permission enqueues nothing | desktop JVM (+ android host, for the port) |
| secure store recovery | desktop JVM | the decision — erase and reopen — is pure, and the JVM tier asserts all three of its outcomes | **kept deliberately** — the device class is not the recovery policy: it is the real `EncryptedSharedPreferences`/Keystore IO, which no fake can stand in for. The recovery in a fresh process is covered by `Maestro/flows/auth/12-unreadable-store-recovers.yaml`, because the keystore caches its keyset per process. | desktop JVM + device |
| create a task from the FAB | desktop JVM owns the *behaviour*; the device class owns the *Android shell wiring* | the desktop flow test drives the desktop shell's FAB and the editor that opens from it, in seconds | **kept deliberately** — the device class is not a copy of the desktop flow: it opens the editor through the **Android shell's** FAB, and `tasks_fab` is a tag only `androidMain` applies (`shared/src/androidMain/.../AndroidShellNav3.kt`), so the desktop copy cannot reach that seam at all. Nor is it a copy of the Maestro flow: `Maestro/flows/tasks/create-task.yaml` drives the *same* FAB → title → save → row-in-the-list path on the same Android app, and Maestro owns that scenario as the whole-user flow — the device class's happy-path test asserts the same outcome in-process, not a new one. That copy is this row's **named exception**: it duplicates the Maestro outcome on purpose, because the class is retained by #3088, whose issue rules out dropping device tests to move the instrumented floor — so the copy stays and is named here rather than reading as a second owner. The **one** guarantee that is the device class's alone is (a) confirming the discard dialog after a back press really drops the task — the outcome `EditorBackGuardTest` asserts only up to the dialog, and no Maestro flow cancels the editor at all (the `dialog_confirm` steps in Maestro belong to delete, archive and profile confirmations). The editor's **empty** draft is a *precondition* the class establishes, not a second guarantee: the class clears the app process's own `DraftStore` in-process — a black-box flow cannot reach the store, which is why `create-task.yaml` has to `eraseText` the restored title instead — and the guarantee behind that clearing, that a cleared draft leaves the editor empty, is owned by `TaskEditorDraftTest` (`the_clearing_touches_the_editors_drafts_and_nothing_else`), which seeds a draft, clears it and asserts the empty title. `NavigationSmokeTest` asserts the FAB's *visibility* per tab, not that tapping it opens the editor; `AppStartupTest` asserts that the shell's tags are addressable as resource ids, not that the editor they open accepts a title and saves. Disposition `investigate` → `keep` in [#3088](https://github.com/gazon1/sing/issues/3088). | desktop JVM + device + Maestro (the happy path only) |
| shell / navigation reachability | desktop JVM | the JVM tier renders the *desktop* shell, and the reachability of that shell is what it asserts | **kept deliberately** — only a device can observe the *Android* shell (`AndroidShellNav3`), which is a different shell with a different set of destinations; the device copy is that observation, not a second copy of the desktop one. The Maestro smoke flow stays as the device-level entry check. | desktop JVM + device + Maestro |
| custom emoji entry: the typed value becomes the project's icon | the rule in common code (`customEmojiFromInput`, asserted by `CustomEmojiInputTest`) | the rule is one function, and it is the same function every platform calls | **kept deliberately** — the desktop test drives the *sheet*: it proves what the field holds is what the button hands back. The device flow drives the *app*: the platform keyboard, the sheet, and the icon the project draws. A value that never leaves the field, or that the app drops on the way to the project, is invisible to the desktop copy — so the device copy asserts the other half, not the same half twice. | desktop JVM + device (instrumentation + Maestro) |
| a project icon crosses the persistence boundaries unchanged | desktop JVM | the boundaries are our own code — the column, the backup DTO and the sync document — and the rule is one string crossing all of them | **kept deliberately** — the boundary test walks the model and the mappers, not a database, so `ProjectIconRoomRoundTripTest` writes the composed sequences into a real SQLite and reads them back. The device flow sees the icon *drawn*, not the bytes. | desktop JVM (mapper + real SQLite) |

## The emoji scenarios, by tier

The emoji work (#2917 and the issues under it) has its own rows, because the rule
is one rule and the platforms only differ in what they can *see*.

| Scenario | Shared rule | Desktop JVM | Android device | Maestro |
|---|---|---|---|---|
| the custom field's rule: blank picks nothing, a value is trimmed and taken as it is | `CustomEmojiInputTest` — one function (`customEmojiFromInput`), called by every platform | `IconPickerCustomEmojiTest` — the desktop sheet: what the field holds is what the button hands back | `IconPickerCustomInputTest` — the device sheet and the device's own field | `projects/05-custom-emoji.yaml` — the whole scenario, on a device |
| a composed sequence keeps its code points (ZWJ, flag, skin tone, variation selector, keycap) | the same rule, over `COMPOSED_PROJECT_ICONS` | the picker's own value is handed back unchanged | the device picks one and the sheet draws it as one node | the flow types a ZWJ sequence and the project draws it |
| a value the catalog does not know is still a value | the same rule, over the unknown values | the field hands back what was typed | the device field does the same | — |
| the icon crosses the storage boundaries unchanged | `ProjectIconBoundaryTest`, `ProjectDocumentWriteTest`, `BackupRoundTripTest`, `ProjectIconRoomRoundTripTest` | the mapper and a real SQLite | the device flow sees the icon *drawn*, not the bytes | — |
| the platform's own font draws the sequence as one glyph | — | `ProjectIconEmojiRenderTest` — the desktop font's pixels: the ink fits the box, touches no edge, sits in the middle | `ProjectIconEmojiRenderTest` (Android) — the device font's pixels: ink is there and touches no edge | the flow sees the glyph in the app, not its metrics |

**The platform copies are not copies.** Each one observes something the shared rule
cannot:

- **Android owns its own text input.** The device test drives the sheet on a
  real device and puts the text into the field through the platform's own
  text-input pipeline — the action the device's field implements, not a string
  the test hands to the repository. The **real IME** is the Maestro flow's: it
  types through the platform keyboard on a device, which is the only place the
  keyboard itself is exercised, and it is the flow that owns the whole user
  scenario anyway. The desktop test types with the desktop's own input.
- **Desktop owns Compose Desktop's own drawing.** The desktop font and the device
  font are different fonts, and a font that composes a sequence on one platform can
  draw it as two glyphs on the other — the desktop render test measures the
  desktop's pixels, the Android one measures the device's.
- **Maestro owns the whole user scenario.** The keyboard, the pick, the project
  that draws the emoji and the project that is reopened afterwards. No unit test
  walks that path, and no unit test should.

**The desktop's render observations are not observable on a device, so they are
not ported on purpose.** They are the desktop's own observations — how its font
draws a sequence, how its chip breaks a line — and a device copy would assert
the device's font, not the desktop's. The picker's field contract is the other
way round: the desktop's `IconPickerCustomEmojiTest` and the device's
`IconPickerCustomInputTest` cover the same rule on their own platforms, each
with the platform's own field. The device tier keeps what only a device can
observe: the platform keyboard, the real IME, the system font.

**What no tier can see** is which glyph the font chose: a font without the
sequence draws one tofu box, and the ink rules cannot tell that from a composed
glyph. The bound is stated in the render test's own documentation rather than
left to a reader to discover.

## What was removed, and what the floors say

Three copies went, each because the scenario had an owner that could assert it:

- the Android host's **record-retention** check, now that both writers share one
  constant, and
- the **notifier matrix** out of the Android host class, now that the rule is common
  code, and
- the device tier's original **FAB** flow test was removed as a duplicate. A later device `CreateTaskFlowTest` filled that gap, and #3086 left it as `investigate` until its remaining Android shell/editor/resource-id wiring had explicit owners. **#3088 gave it those owners**: the class owns the discard-dialog outcome — the task does not appear in the list after the discard is confirmed. The empty draft it opens on is a *precondition* it establishes, not a second guarantee: it clears the app process's own `DraftStore` in-process, and the guarantee behind that clearing — the editor opens empty once the drafts are gone — belongs to `TaskEditorDraftTest` (`the_clearing_touches_the_editors_drafts_and_nothing_else`), which seeds a draft, clears it and asserts the empty title. The FAB → editor → save → list path itself is *not* unique to it: `Maestro/flows/tasks/create-task.yaml` drives the same path on the same Android app, and only the desktop copy is barred from it (`tasks_fab` is applied only in `androidMain`). Its disposition is now `keep`, it stays on the device tier, and the instrumented floor does not move.

The instrumented tier lost one class and three tests when that earlier FAB flow was removed; `TaskEditorDraftTest` later added one class and two tests for a scenario only a device can observe (a persisted draft being restored). `config/docs/instrumented-test-floor.txt` currently records the tier's floor as 17 classes and 53 tests. The android host tier's executed baseline is currently 28 tests (`config/docs/test-runs-baseline.txt`: `shared:testAndroidHostTest 28 0`); the source set contains 9 test classes, all 9 of them in the Gradle execution allowlist since #3087 wired `AndroidFileSourceTest`.

## The four features that had no end-to-end flow

| Feature | Flow coverage now | Why |
|---|---|---|
| AI chat | a flow exists — `Maestro/flows/menu/07-ai-chat-entry.yaml` — and its device verification is tracked by #1902 | the entry from the menu is the part a user touches; the chat screen itself is covered by the desktop compose tests |
| Proposals | a flow exists — `Maestro/flows/menu/08-proposals-entry.yaml` — and its device verification is tracked by #1903 | as above |
| WhatsNew | no flow; the gap is tracked by #1904 | the surface only appears when the server ships a payload, which a seeded device run does not produce |
| Alarms | no flow, deliberately | the alarms feature has no user-facing surface — a reminder is scheduled by a task and what the user sees is a notification, which the notifier's own tests cover |

## Where to look next

- `docs/architecture/tests.md` — where a test lives, and the rules that decide it.
- `docs/testing/what-each-tier-proves.md` — what each tier can and cannot see.
- `docs/testing/maestro-guarantees-inventory.md` — what the Maestro suite guarantees, and what it costs (#3105).
- `config/docs/test-runs-baseline.txt` and `config/docs/instrumented-test-floor.txt` —
  the floors that hold the tiers to what they used to deliver.

## Android test inventory (#3086)

Audit-only inventory for issue [#3086](https://github.com/gazon1/sing/issues/3086): no tests, production code, Gradle config, floors, or CI lanes changed. Detailed per-class notes, overlaps, and replacement seams are in [`docs/testing/android-test-inventory.md`](../testing/android-test-inventory.md). Reproducible scope: 22 Kotlin files / 17 test classes / 53 direct `@Test` in `androidApp/src/androidTest`; 9 Kotlin files / 9 test classes / 24 direct + 4 inherited `@Test` in `shared/src/androidHostTest` (executed set: 28, baseline `shared:testAndroidHostTest 28 0` after #3087 wired `AndroidFileSourceTest`).

[#3088](https://github.com/gazon1/sing/issues/3088) re-checked every `keep` and `investigate` disposition in this table against the tree, and re-measured the counts above from it (the host set's direct `@Test` count is 24, not the 22 this line used to carry — the executed set of 28 was already right). The one disposition that was still open, `CreateTaskFlowTest`, is resolved below. No test, production code, Gradle config, floor, baseline or CI lane changed, so the counts, the floor and the baseline all still describe the same tree.

**Nothing was lost in the migration.** The only change to the two source sets since the audit commit `b083992` is `AndroidFileSourceTest.kt` — #3087's wiring of it into the executed set, plus the `readBytes()` coverage review asked for. `git diff --stat b083992 HEAD -- androidApp/src/androidTest shared/src/androidHostTest` names that one file and nothing else: no class was deleted, so no platform guarantee was dropped on the way from the device tier to the host tier.

| Class | Tier | Disposition | Owner / rationale |
|---|---|---|---|
| `androidApp/.../core/security/AndroidSecureStorageRecoveryTest.kt` | device | keep | Real Android Keystore/EncryptedSharedPreferences IO; recovery decision owned by `shared/src/jvmTest/.../SecureStoreRecoveryTest.kt`, fresh-process recovery by `Maestro/flows/auth/12-unreadable-store-recovers.yaml`. |
| `androidApp/.../debug/DebugSeedOrderTest.kt` | device | keep | Android `singleTask`/`onNewIntent` debug-seed ordering; no faster seam found. |
| `androidApp/.../observability/AppTracerInstrumentedTest.kt` | device | keep | Real app startup + CrashReportingPort/SDK no-throw smoke; not equivalent to host graph resolution. |
| `androidApp/.../ui/ActionRecordingTest.kt` | device | keep | Android instrumentation step-recorder contract; infra guard, not app behavior. |
| `androidApp/.../ui/AppStartupTest.kt` | device | keep | Android bypass/build flag, sign-in absence, testTag-to-resource-id accessibility mapping; shared tag reachability owned by `desktopApp/src/jvmTest/.../ShellTestTagReachabilityTest.kt`. |
| `androidApp/.../ui/CreateTaskFlowTest.kt` | device | keep | The device class owns the discard-dialog outcome (the task must not appear in the list). Its empty editor is a precondition, established by clearing the app process's own `DraftStore` in-process; the guarantee behind that clearing — the editor opens empty once the drafts are gone — is owned by `TaskEditorDraftTest`. Its happy path is *not* unique to it: `Maestro/flows/tasks/create-task.yaml` drives the same FAB → editor → save → list path on the same Android app, and `desktopApp/src/jvmTest/.../feature/flows/tasks/CreateTaskFlowTest.kt` owns the behaviour; only the desktop copy is barred from the Android seam, because `tasks_fab` is applied only in `androidMain`. Resolved in #3088; the class stays on the device tier and the instrumented floor does not move. |
| `androidApp/.../ui/EditorBackGuardTest.kt` | device | keep | Android system back + IME dirty-draft contract; desktop `PlatformBackHandler` is no-op. |
| `androidApp/.../ui/FailureBundleDeliveryTest.kt` | device | keep | Device artifact delivery to `/data/local/tmp`; CI artifact-loss guard. |
| `androidApp/.../ui/IconPickerCustomInputTest.kt` | device | keep | Android picker text-input/Compose semantics; rule owned by common `CustomEmojiInputTest`, desktop field by `IconPickerCustomEmojiTest`, real IME by `Maestro/flows/projects/05-custom-emoji.yaml`. |
| `androidApp/.../ui/MenuSheetBoundsTest.kt` | device | keep | Android density/window bounds, rail clearance, menu reachability; no JVM/Maestro equivalent. |
| `androidApp/.../ui/NavigationSmokeTest.kt` | device | keep | Android `AndroidShellNav3` reachability/FAB contract; distinct from AppStartupTest. |
| `androidApp/.../ui/NotificationPermissionRequesterComposeTest.kt` | device | keep | Production Compose permission-requester wiring/disposal/owner swap; observer behavior owned by `shared/src/androidHostTest/.../NotificationPermissionRequesterTest.kt`, rule by `shared/src/jvmTest/.../NotificationSupportTest.kt`. |
| `androidApp/.../ui/ProjectIconEmojiRenderTest.kt` | device | keep | Android font/pixel rendering; desktop render test covers desktop font only. |
| `androidApp/.../ui/SearchBackTest.kt` | device | keep | Android search IME/back/filter-sheet behavior. |
| `androidApp/.../ui/TaskDetailImeInsetsTest.kt` | device | keep | Android IME/window insets layout contract. |
| `androidApp/.../ui/TaskDetailOverflowActionsTest.kt` | device | keep | Android shell top-bar overflow wiring/popup/resource-id; desktop `TaskDetailOverflowActionsTest` covers shared Compose behavior only. |
| `androidApp/.../ui/TaskEditorDraftTest.kt` | device | keep | Device-persisted draft restoration across process; only device can observe. |
| `shared/.../androidHostTest/core/backup/AndroidBackupCodecBudgetTest.kt` | androidHostTest | keep | Android backup codec implementation budget guard; JVM cannot reach androidMain codec. |
| `shared/.../androidHostTest/core/di/AndroidGraphResolutionTest.kt` | androidHostTest | keep | Android Koin graph/platform bindings; distinct from JVM graph tests. |
| `shared/.../androidHostTest/core/files/AndroidFileSourceTest.kt` | androidHostTest | keep | Android `content://` streaming: the system picker hands back a URI that `AndroidFileSystem` cannot open, so this is the `ContentResolver` path and no JVM implementation has it. #3087 found the class compiled but absent from the `testAndroidHostTest` include allowlist (its 3 tests in no run at all) and wired it; the third test's premise was restaged with a real provider because Robolectric answers an unregistered authority with a throwing stream, not `null`. |
| `shared/.../androidHostTest/core/files/AndroidFileSystemMoveTest.kt` | androidHostTest | keep | Android `ATOMIC_MOVE`/replace-target contract for backup writes. |
| `shared/.../androidHostTest/core/notifications/AndroidNotifierTest.kt` | androidHostTest | keep | Android notification permission read-per-call and no-enqueue-on-denied port behavior; rule matrix owned by JVM `NotificationSupportTest`. |
| `shared/.../androidHostTest/core/notifications/NotificationPermissionRequesterTest.kt` | androidHostTest | keep | Lifecycle observer contract; Compose wiring owned by device `NotificationPermissionRequesterComposeTest`. |
| `shared/.../androidHostTest/core/security/ArchiveCryptoGoldenAndroidTest.kt` | androidHostTest | keep | Sanctioned cross-platform golden contract (same cases as `ArchiveCryptoGoldenJvmTest` via `ArchiveCryptoGoldenContract`). |
| `shared/.../androidHostTest/mcp/ToolSurfaceWiringTest.kt` | androidHostTest | keep | Deliberate Android/desktop two-binding MCP wiring check; catalog parity must hold per platform. |
| `shared/.../androidHostTest/test/stubs/TestOnboardingModuleTest.kt` | androidHostTest | keep | AndroidMain E2E onboarding stub contract; JVM wiring covered separately by `E2eOnboardingWiringTest`. |

Support files with no `@Test` (keep as infra): `androidApp/.../testing/InstrumentedTestBase.kt`, `androidApp/.../ui/FailureBundle.kt`, `androidApp/.../ui/ShellProbe.kt`, `androidApp/.../ui/StepRecorder.kt`, `androidApp/.../ui/UiActions.kt`.

## Maestro guarantees inventory (#3105)

Audit-only inventory for issue [#3105](https://github.com/gazon1/sing/issues/3105): no flow moved or deleted, no production code, Gradle config, shard count, suite selection, CI trigger or floor changed. The full inventory — 135 catalogue flows at the time of the audit, each with its suite and shard assignment, its measured cost where one exists, its disposition and its named owner — is in [`docs/testing/maestro-guarantees-inventory.md`](../testing/maestro-guarantees-inventory.md). The 127 automated flows are classified row by row; the eight excluded flows (`manual` / `auth-live`) are listed separately, with no measured cost and no automated owner, because by design no shard runs them. The current catalogue holds 126 flows (`config/docs/maestro-flows.txt`) after the #3119, #3120, #3121, #3122, #3123, #3115, #3142 and #3143 retirements — `nav/02-menu-parity.yaml`, `agenda/09-upcoming-timeline.yaml`, `agenda/04-saved-view-results.yaml`, `profile/01-create-profile.yaml`, `system/menu-sheet.yaml`, `calendar_sync/02-system-calendar-unavailable.yaml`, `search/03-clear.yaml`, `negative/02-empty-notes.yaml`, `negative/03-empty-projects.yaml` and `negative/04-empty-archive.yaml`.

**Scope.** Suite membership was read from the runner itself (`scripts/src/emulator/maestro/_flow.py`), not from the catalogue: 135 flows in `config/docs/maestro-flows.txt`, 30 in `smoke`, 127 in `full`, 8 excluded (`manual` / `auth-live`). Cost was measured from real CI JUnit artifacts of four `full` runs and one `smoke` run — the median of the **passing** samples per flow. The 23 flows that fail in every sampled run carry no number at all; their duration is a time-to-failure, not a cost.

**What the measurement says.** Measured flow time is 78.7 min across the 104 automated flows that have a passing sample; the other 23 fail in every sampled run and therefore carry no measured cost at all. The per-shard overhead on top of it is 9–14 min (full) and 5–7 min (smoke) — AVD restore, emulator boot, install and the per-flow device reset — so the per-shard job wall clock of a full run is 29.3–46.4 min and a smoke run 9.8–13.7 min. The floor is a count of failures, not a duration, so the two numbers are not comparable; see the audit's [measurement gap](../testing/maestro-guarantees-inventory.md#the-measurement-gap).

**Where the guarantees already live.** Classified per row (129 rows for 135 flows — the eight excluded flows share two rows): 24 `keep` (a platform-only guarantee no faster tier owns), 70 `split` (the behaviour half could move to a headless tier), 27 `replace-candidate` (every guarantee is already owned by a named headless test), 8 `investigate`. Of the 24 `keep` rows, **22 are automated flows**; the other two are the aggregated rows of the excluded `capture` and `auth-live` flows, which no shard runs and no automated tier owns. The top of the ranked candidate set, in the audit's order:

| Flow | Cost | Owner that already holds the guarantee |
|---|---:|---|
| `nav/02-menu-parity.yaml` | 35 s | `desktopApp/src/jvmTest/.../feature/flows/menu/MenuParityFlowTest.kt` — model-level parity of the entries and of the project roots, stronger than the flow — **retired 2026-11-23** ([#3119](https://github.com/gazon1/sing/issues/3119)); the flow is gone, the owner stayed |
| `agenda/09-upcoming-timeline.yaml` | 84 s | retired 2026-11-23 (#3120) — `desktopApp/src/jvmTest/.../feature/flows/agenda/UpcomingTimelineScenarioTest.kt` now owns the Upcoming behaviour on desktop; the Android navigation and the feed scroll stay in `androidApp/src/androidTest/.../ui/NavigationSmokeTest.kt` and `androidApp/src/androidTest/.../ui/UpcomingFeedScrollTest.kt` |
| `agenda/04-saved-view-results.yaml` | 68 s | retired 2026-11-23 (#3121) — `desktopApp/src/jvmTest/.../OpenSavedViewShowsMatchingTasksFlowTest.kt` now owns `AGENDA-04` on desktop |
| `archive/02-bulk-archive-confirm.yaml` | 81 s | behavior: `desktopApp/src/jvmTest/.../feature/flows/archive/ArchiveBulkActionFlowTest.kt` (writes/count/eligibility, reads repository). device: `Maestro/flows/archive/02-bulk-archive-confirm.yaml` (long-press context sheet, overflow popup resource IDs, modal result dialog window). The flow is trimmed to that residue. |
| `sync/01-change-sync-interval.yaml` | 80 s | `desktopApp/src/jvmTest/.../feature/flows/sync/SyncSettings01ScenarioTest.kt` (value change + immediate apply + restart/reload survival). The Maestro flow keeps the Android narrow-sheet UI residue and **no longer carries `scenario:SYNC-SETTINGS-01`** — a residue is not the scenario, and the android cell of the scenario is an honest hole. |
| `profile/01-create-profile.yaml` | 61 s | retired 2026-11-23 (#3122); `desktopApp/src/jvmTest/.../feature/flows/profile/ProfileFlowTest.kt` (`PROFILE-01`) owns the behavior |
| `menu/06-tree-live.yaml` | 64 s (pre-split) | behavior: `desktopApp/src/jvmTest/.../feature/flows/menu/ProjectsMenuLiveScenarioTest.kt` owns `MENU-03` creation and rename liveness while the drawer is open. device: `Maestro/flows/menu/06-tree-live.yaml` keeps only Android modal-sheet reopen/resource-id residue after outside changes; trimmed residue verified on Android (2026-11-24), and the flow **no longer carries `scenario:MENU-03`** — the android cell of the scenario is an honest hole. |
| `system/menu-sheet.yaml` | 18 s | retired 2026-11-24 (#3123); `androidApp/src/androidTest/.../MenuSheetBoundsTest.kt` owns the derived tag set by resource id |

Every row is a proposal for a follow-up change issue. Acting on one means naming the owner, dropping the flow and updating the catalogue and the scenario link in the same commit — otherwise the scenario id loses its carrier.

**Nine rows are done.** `nav/02-menu-parity.yaml` was retired on 2026-11-23 ([#3119](https://github.com/gazon1/sing/issues/3119)): the menu's model-level parity — the same entries on both platforms, the same project roots in the same order — is owned by `desktopApp/src/jvmTest/.../feature/flows/menu/MenuParityFlowTest.kt`, which asserts the shared menu model, both drawings of the project section and the real Desktop drawer; the device-only part of the flow (the sheet's own bounds and the Android menu) stays covered by `androidApp/src/androidTest/.../ui/MenuSheetBoundsTest.kt`.

`agenda/09-upcoming-timeline.yaml` was retired on 2026-11-23 ([#3120](https://github.com/gazon1/sing/issues/3120)): every claim it made — the three future rows under their date headers, each listed once, the day/week grouping switch and back, today's and the undated task absent — is owned by `desktopApp/src/jvmTest/.../feature/flows/agenda/UpcomingTimelineScenarioTest.kt`, the tab's Android navigation is owned by `androidApp/src/androidTest/.../ui/NavigationSmokeTest.kt`, which names the Upcoming destination itself (its grouping control) rather than the tab control that is on screen on every destination, and the real feed scroll is owned by `androidApp/src/androidTest/.../ui/UpcomingFeedScrollTest.kt`, which drags the phone's feed until a task six days out is in view — the gesture the desktop class cannot make and the retired flow used to make. The scenario id `AGENDA-09` is deprecated in favour of `AGENDA-10`, which carries the merged claims.

`agenda/04-saved-view-results.yaml` was retired on 2026-11-23 ([#3121](https://github.com/gazon1/sing/issues/3121)): its saved-view result behaviour is owned by `desktopApp/src/jvmTest/.../feature/flows/agenda/OpenSavedViewShowsMatchingTasksFlowTest.kt`, which now carries `AGENDA-04` on desktop; the device-only save-current/IME steps remain covered by `03-saved-views-crud.yaml`.

`profile/01-create-profile.yaml` was retired on 2026-11-23 ([#3122](https://github.com/gazon1/sing/issues/3122)): its behaviour is owned by `desktopApp/src/jvmTest/.../feature/flows/profile/ProfileFlowTest.kt`, which carries `PROFILE-01`.

`system/menu-sheet.yaml` was retired on 2026-11-24 ([#3123](https://github.com/gazon1/sing/issues/3123)): the sheet's derived tag set is owned by `androidApp/src/androidTest/.../ui/MenuSheetBoundsTest.kt`, which addresses the sheet by its resource ids.

`search/03-clear.yaml` was retired on 2026-11-25 ([#3142](https://github.com/gazon1/sing/issues/3142)): the clear-query behaviour is owned by `desktopApp/src/jvmTest/.../feature/search/SearchPendingStateScenarioTest.kt`, which carries `SEARCH-03` on desktop and drives the field's own clear button — the same affordance the flow drove — asserting the emptied field, the results gone and the idle prompt back. The flow's device-only residue (the quick-search entry path `menu_quick_search` → `search_input` and the real IME) is carried by the other search flows in that area.

`negative/02-empty-notes.yaml`, `negative/03-empty-projects.yaml` and `negative/04-empty-archive.yaml` were retired on 2026-11-25 ([#3143](https://github.com/gazon1/sing/issues/3143)): the three empty states are owned by `desktopApp/src/jvmTest/.../feature/flows/notes/NotesFlowTest.kt` (`NEGATIVE-02`), `.../projects/ProjectsFlowTest.kt` (`NEGATIVE-03`) and `.../archive/ArchiveBulkActionFlowTest.kt` (`NEGATIVE-04`), each claiming the scenario through its `@DisplayName` and asserting the empty message the flow asserted. The device-only residue is not the scenario and stays in the device tier: the menu route to Notes and the quick-add field by `Maestro/flows/notes/create-note.yaml`, the menu route to the project list by `Maestro/flows/menu/04-all-projects.yaml`, and the Archive's menu destination by `Maestro/flows/archive/01-restore.yaml`.

The catalogue holds 126 flows now.

**Findings that are not about cost.** Two are worth the next reader's attention, because they are inconsistencies in the tree rather than in the suite: the anonymous gate behind `menu_attach_account` has no Kotlin owner at all; and the explanatory comment in `config/docs/e2e-shards.txt` still describes the smoke suite as 27 flows in shares of 7/7/7/6, while the runner's selection today gives 29 flows in shares of 8/7/7/7 (the file's configuration — four shards each — is still correct; only the prose is stale). Separately, all four sampled `full` runs and the sampled `smoke` run **failed** the repo's own results gate (`scripts/ci/check-maestro-results.py`), and the registry prose in `config/docs/maestro-debt.yaml` still says the shards are skipped in CI, which is no longer true.

**Resolved.** `tasks/13-create-full-task.yaml` no longer points at a nonexistent desktop owner: its closing comment names `CreateTaskFlowTest`, and that class owns the Today+High-priority save outcome and priority-indicator assertion; the flow keeps the device-only IME, restored-draft, and multi-date confirm guarantees ([#3117](https://github.com/gazon1/sing/issues/3117)).

**Resolved.** `agenda/01-smart-lists.yaml` contradicted its own spec `AGENDA-01.yaml` on what the Inbox preset means — the code and `AgendaTabDisjointnessTest` say "undated only", the spec said a dated task stays in Inbox and only changes section. [#3114](https://github.com/gazon1/sing/issues/3114) reconciled the spec with the code: the Inbox holds the tasks that are not scheduled yet, dating a task takes it out of it, and the flow already asserted exactly that.

**Resolved.** `calendar_sync/02-system-calendar-unavailable.yaml` asserted a state the Android platform cannot reach: the panel's "system calendar unavailable" message is gated on `deviceSyncSupported`, and on Android every one of its four seams is real, so the panel takes the available branch instead. [#3115](https://github.com/gazon1/sing/issues/3115) removed the flow instead of repairing it — the guarantee it named is a desktop guarantee, owned by `CalendarSyncSystemScenarioTest.the_system_panel_says_why_it_cannot_work` and `CalendarSyncPlatformHonestyTest`, and no Android device could ever satisfy the assertion. The Android-reachable counterpart is `calendar_sync/03-system-calendar-toggle.yaml`, which is qualified and now proves that the panel's content follows the enable switch in both directions.
