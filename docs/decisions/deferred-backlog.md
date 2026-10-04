# Deferred backlog

Findings that were real enough to record and too expensive to fix where they
surfaced. Each entry names where it was found, what was already ruled out, and
what a future attempt should try **first** — so the next person does not repeat
the dead ends.

Rule for adding: an entry needs a number, the MR that found it, and the checks
already performed. "Looks wrong" is not an entry.

---

## sync-config-screen-unwired

**Found in:** MR-1 retrospective, `scripts/find-unwired-surfaces.py`. Pre-existing;
not a regression from the desktop UI work.

`feature/sync/presentation/SyncConfigScreen.kt` was a public `@Composable` with no
call site. `SyncViewModel` was fully built and registered.

**Status: RESOLVED.** `SyncConfigScreen.kt` was deleted. The `find-unwired-surfaces`
gate is now blocking in CI (Phase 1.1, PR-2).

---

## saved-views-crud-flow-selects-a-snackbar-that-does-not-exist

**Found in:** MR-2, while cross-checking the constants slated for deletion
against their real consumers. Not a regression — a dormant red flow.

**Status: CLOSED.** MR-10 (`:feat/agenda-test-ratchet`).

**Root cause:** `Notification.Text("Saved", null)` → `ResultDialog(title, text=null)` →
`if (text == null) return` — the dialog is never shown, and there is no snackbar
either. `SNACKBAR_SAVED` is dead code: defined in `TestTags.kt` but applied by no
composable anywhere in `shared/src`. The flow was waiting for a UI element that
never rendered.

**Fix:** Removed the `extendedWaitUntil: id: snackbar_saved` step from the flow.
The save operation completes synchronously from the flow's perspective (the button
re-enables after persist), and the editor stays open without any visible
acknowledgement. A proper "Saved" toast requires `Notification.Undo` (which
routes to `SnackbarHost`) — a separate product decision documented as item #3
below.

**Future (not MR-10):**
1. Product decision: implement a non-undo "Saved" toast via `NotificationHost`
   routing `Notification.Text` to `SnackbarHost` instead of `ResultDialog`.
   Requires ADR + `SNACKBAR_SAVED` applied to the snackbar host.
2. If dialog is the intent instead: re-point the flow at `Dialog.CONFIRM`
   (button currently untagged — see `dialog-buttons-untagged`).
3. Sweep all flows: resolve every `id:` in `Maestro/flows/**` against
   `Modifier.testTag` sources. Add to `Maestro/scripts/check-tags.sh`.

---

## file-log-writer-and-log-exporter-are-never-installed

**Status: RESOLVED** by the logging epic (MR-1 … MR-5, 2026-09-30). The writer
is installed on both platforms, `LogExporter` and `LoggerHolder` were deleted
as dead ports, and the fifth script form (`log-writer`) now catches a
`LogWriter` that never reaches `setLogWriters`. See
`2026-09-30-file-logging-wired.md` and
`2026-09-30-dead-code-deleted-and-oauth-kept.md`.

What remains from this entry is item (1) of the original "try next" list, which
was a product question rather than a refactor: **logs still have no way to
leave the device.** See `log-export-has-no-surface` below.

**Original entry follows.**

---

**Found in:** MR-3, while implementing the plan's "severity per writer" step —
which turned out to have no writers to configure.

**Symptom:** `LogBootstrap.kt`'s KDoc states that both platforms "add
`FileLogWriter` for persistent rolling logs", and that severity filtering is
global (`Verbose` debug / `Warn` release). Neither `actual fun initLogging`
implements the first half. `desktopApp`/`androidApp`/`SingularityApp` never
mention `FileLogWriter` or `LogExporter`; neither symbol has a single call site
outside its own file and its test.

So **the app writes no logs to disk at all**, while documenting that it does.
`FileLogWriter` is fully built — rolling files, size limit, rotation, a
single-threaded `Dispatchers.IO` writer — and `FileLogWriterTest` covers it.
That is the exact shape `find-unwired-surfaces.py` exists to catch: implemented,
tested, never invoked.

**Why the script did not flag it:** the detector recognises four shapes only —
`screen`, `default-noop`, `di-binding`, `navigation`. A fully-implemented class
wired to nothing is not among them. This is a gap in the script, not an
exemption.

**Try next:**

1. Decide whether persistent logging is a requirement. ADR
   `2026-09-26-observability-production` describes an export path, so the
   answer is probably yes — but confirm before wiring, because adding a writer
   that writes 20 MB of rotating files on every device is a product decision
   (retention, opt-out, battery) and not a refactor.
2. If yes: add `FileLogWriter` to both `actual fun initLogging` bodies and
   `LogExporter` to whatever surface is meant to hand logs off (a share
   intent? a settings screen? — grep finds no caller, so the trigger point has
   to be identified; it may itself be missing).
3. If no: delete `FileLogWriter`, `LogExporter` and `FileLogWriterTest`, and fix
   the KDoc on `LogBootstrap` that promises them. Carrying a tested but unused
   subsystem is worse than not having it: the next reader assumes the logs are
   there.
4. Either way, **fix the KDoc** — it currently describes behaviour that does not
   exist, which is how this was missed for as long as it was.
5. Widening `find-unwired-surfaces.py` to a fifth shape — a `LogWriter`
   subclass with no `setLogWriters` call — is a natural companion fix.

**Related:** the plan's own §6 assumed "in release there is already a
`FileLogWriter` + `LogExporter`" and built on it. That assumption was wrong,
which is why the step produced a finding instead of a change.

---

## log-export-has-no-surface

**Found in:** the logging epic retrospective (MR-2), when `LogExporter` was
deleted instead of implemented.

**Symptom:** file logging works on both platforms, so a developer can now read
logs off a device — but a *user* cannot. There is no way to attach logs to a
bug report, which is the reason `FileLogWriter` was originally wanted
(`2026-09-23`).

**Already ruled out:** not a wiring bug. `LogExporter` had no implementations
and no consumers, so there was nothing to re-wire — the surface itself does
not exist.

**Try next, in this order:**

1. Decide the trigger surface first. A "Share logs" row in Settings →
   Developer/Debug is the obvious one; grep for what Settings already has
   before assuming.
2. Only then write the port. Android wants `ACTION_SEND` with a `FileProvider`
   over the log directory; desktop wants a copy-to-timestamped-dir plus
   clipboard. Two implementations, one interface — the shape
   `2026-09-23` already specified and that was correctly not built speculatively.
3. Re-check redaction at that point. `RedactingLogWriter` scrubs credentials
   from what is *written*, and the same writers produce the file, so an export
   carries the same guarantees — but an export leaves the device, so a
   deliberate review of what the file contains is warranted before shipping
   it.

---

## bulk-task-operations-have-no-ui

**Found in:** MR-4, while deleting dead code. `TaskMutationsUseCase` was on
the deletion list and was **kept** — see the note below.

**Symptom:** `bulkComplete(ids)` and `bulkDelete(ids)` are implemented, unit
tested, and Koin-bound, but no ViewModel injects the use case. There is no
multi-select in the task list, so the atomicity guarantee those methods exist
to provide is never exercised in the running app.

**Already ruled out:** not dead code. `2026-09-05-refactoring-summary` created
the use case by collapsing five pass-through use cases, and
`2026-09-07-dogfooding-followups` stripped it back while keeping exactly these
two methods because they enforce fail-fast atomicity the repositories do not.
Six tests cover it. Deleting it would have reverted a deliberate decision.

**Try next:** this is a product gap, not a refactor. When multi-select lands,
`TaskMutationsUseCase` is already the correct entry point — wire it rather
than writing a second implementation beside it. Settle the design questions
first (selection model, confirm step, partial-failure UX for a batch where
some ids vanished).

---

## core-auth-oauth-is-entirely-unwired

**Found in:** MR-4. The plan listed two dead symbols in
`core/auth/oauth/OAuth.kt`; the file as a whole is unreachable.

**Symptom:** `OAuthConfig`, `OAuthResult`, `OAuthTokenData` and
`toOAuthTokenData` have zero references outside their own file — no ViewModel,
no repository, no test, no Koin binding. `TokenError` and `RedirectState` were
deleted in MR-4; the rest was left alone.

**Already ruled out:** not reachable through reflection, DI or a route — it is
plain Kotlin with no registration anywhere.

**Try next:** decide whether Supabase OAuth is still planned. If yes, the file
is a reasonable starting skeleton. If no, delete the remaining 90 lines. A
dead-code sweep should not make the product decision either way, which is why
MR-4 stopped at the two symbols it was asked to remove.

---

## log-messages-need-a-user-content-sweep

**Found in:** MR-3 retrospective. The redaction decorator catches credential
shapes; it does not catch task titles, note bodies, or AI prompt fragments.

**Symptom:** a repo-wide sweep of `log.{d,i,w,e} { "...$var..." }` for
user-derived values has never been done. `ChatViewModel` (AI prompt fragment)
and `ProfileBootstrapper`/`SyncBootstrapper` (profile name, entity id) were
fixed individually; other call sites print whatever they were handed.

**Try next:** one deliberate pass over `commonMain` log call sites,
classifying each interpolated value as id (fine), technical metadata (fine) or
user content (decision needed per site — drop, truncate, or accept). Record
the classification so the next audit is a diff, not a re-derivation. The
severity question rides along: in release, `Warn`+ still writes to the file.

---

## projects-flow-one-time-flake

**Found in:** MR-5 final `./check.sh` — the only observation in five runs.

**Symptom:** `ProjectsFlowTest` failed once with `NullPointerException` from
`ProjectDetailViewModel.getDraftState()` returning null (draft state read
before the init collector seeded it). Not reproducible: three `--rerun-tasks`
runs with the change set, one full rerun at MR-4, and the final `check.sh` all
pass. Suspected ordering interaction with `shared:jvmTest` sharing the daemon.

**Already ruled out:** the change sets at both observation and rerun are
tag-rename only — nothing touches projects or drafts.

**Try next:** if it recurs, capture `--scan` per-test timing before touching
code; the fix is probably an explicit `runCurrent()`/await in the flow test,
not a product change. Do not chase it on one observation — but do not
baseline it either: a draft-state NPE is a real crash shape on a device.

---

## find-unwired-surfaces-has-no-baseline

**Found in:** MR-4, while wiring the script into the workflow.

**Symptom:** the script exits 1 whenever anything is reported, and the one
standing finding (`SyncConfigScreen`) is a known, documented product question.
So the script can never gate a check, and "no new findings" is verified by
reading output manually — which means it will not be.

**Status: RESOLVED.** `SyncConfigScreen.kt` was deleted — the sole standing finding
is gone. Phase 1.2 (PR-2) added `find-unwired-surfaces-baseline.txt` and
wired `find-unwired-surfaces` as a blocking CI gate. The script exits 0 when
baseline is current and new findings exist.

---

## digest-line-limit-pressure

**Status: RESOLVED (2026-10-04).** Two changes, both applied:

1. `MAX_BULLETS_PER_ADR = 3` in `scripts/refresh-decisions-digest.py`. A verbose ADR
   used to fill every tag section it was tagged with, so the digest grew with the
   wordiest author rather than with the number of decisions. The Critical section is
   exempt — `**Always**` / `**Never**` rules are what a reader came for. The digest
   went 1260 → 1190 lines, back under the 1250 budget with headroom.
2. The CI doc-sizes step now regenerates the digest before measuring it. DIGEST.md is
   gitignored, so on a fresh checkout it does not exist and the budget check silently
   skipped the only document whose size is generated. The budget was exceeded locally
   for an unknown stretch precisely because `check.sh` did not run the gate at all.

Remaining pressure is the "Active entries" index — one line per ADR, 421 lines and
growing by one per decision. It is the lowest-value section in the file (a title
list, one `ls` away). If the warning returns, cut that section before raising the
limit.

---

## ci-gates-are-all-continue-on-error

**Status: RESOLVED (2026-10-04).** All four remaining advisory gates are blocking:
`Run detekt`, `Assemble Android debug`, `Build version catalog gate`, and the whole
`mcp-server` job. See ADR `2026-10-04-measurement-integrity`.

The `mcp-server` job was the one that mattered: it holds the profile-bootstrap
identity tests, so the P0 data-corruption fix shipped with the tests that cover it
unable to fail a build.

`Check Maestro test tags` stays `continue-on-error: true` on purpose, and the
comment says why: it is superseded by `MaestroFlowTagsTest` in `:shared:jvmTest`,
which is blocking and covers the same tag registry. A non-blocking step that is
documented as a convenience for local use is not a hole; one that duplicates a
blocking gate and is *believed* to be the gate is.

---

## desktop-nav-goBack-blank-screen

**Status:** ✅ RESOLVED (2026-10-04) — fixed by ADR `2026-10-04-navigation-policy`, issue #27 closed.

**Found in:** MR-11, while verifying `OpenSavedViewShowsMatchingTasksFlowTest`.

**Symptom:** after tapping the save button in `SavedAgendaScreen` (or `TaskCreateScreen`) and then tapping the back button, the entire desktop app UI goes blank — `SemanticsTree` reports 0 nodes, every `testTag` lookup fails. Navigation itself completes (kermit log shows "Scheduled sync stopped" from clean `onEnd` path), but the compose tree is empty.

**Root cause:** the JVM top-level graph stacks were created with an entry-local
`remember { NavBackStack(...) }` inside each graph composable. That `remember` is scoped to the
entry, and an entry that leaves `NavDisplay`'s visible set (a tab switch, or an outer push) has
its composition disposed — so the back stack object the shell later mutated was no longer the
one the graph rendered from. The back navigation therefore popped an entry that was no longer
backed by a live composition, leaving `NavDisplay` with nothing to show.

**Fix:** the six top-level graph stacks are now created in `createJvmEntryProvider` (shell
composition, outliving any single entry) and passed into the graphs through their `backStack`
parameter. See ADR `2026-10-04-navigation-policy`, §B1 companion.

**Verification:** `SavedAgendaCreateFlowTest` and `OpenSavedViewShowsMatchingTasksFlowTest` — the
two tests this bug blocked — now pass, as does
`CreateTaskFlowTest.a_saved_task_without_a_due_date_appears_under_inbox_no_date`. This is also
what unblocked the Agenda epic's desktop matrix (issue #26).

**Try next (historical):** add a `NavDisplay` debug modifier (e.g., a `Box` with a visible red border when `entries.isEmpty()`) to distinguish "NavDisplay receives empty list" from "compose tree fails below NavDisplay". No longer needed — the cause was above `NavDisplay`, in stack *ownership*, exactly the branch the investigation notes pointed at.

---

## no-direct-clock-system-kdoc-claims-tests-are-exempt

**Found in:** `refactor/tag-registry-and-robots`, while fixing the
`NoDirectClockSystem` violation that shipped in `2e99b1d0`.

**Symptom:** the KDoc on `NoDirectClockSystemRule` states "Test sources are
exempt (detekt's standard path filters handle patterns in test directories)".
They are not exempt. Both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts`
put `src/jvmTest/kotlin` in `source.setFrom`, and the rule has no path filter of
its own — so any test helper touching `Clock.System` is a finding, exactly like
production code.

**Already checked:** `isAllowedFile()` in the rule whitelists only
`core/platform/Clock.kt` and `core/di/CoreDiModule.kt`. The exemption the KDoc
describes does not exist anywhere in the implementation.

**Try next:** decide which is true, then make the code match. If tests should be
exempt, add a test-path check to `isAllowedFile()` and a `RuleTest` case proving
a `jvmTest` file no longer fires — that is the cheap reading, and it matches what
`NotesScreenTest` and `TagsRenameUiTest` already do (fixed `Instant`, not
`Clock.System`). If tests should be held to the same standard, delete the
sentence and treat the 47 existing suppressions as the real backlog. Do not
change this while the "47 suppressions" item from
`2026-09-30-test-infra-known-gaps` is still open — the two decisions interact.

---

## usage-recording-text-gen-requires-cross-cutting-architecture

**Status: RESOLVED** (tech-debt session, 2026-10-02).

ADR `2026-10-02-usage-recording-textgen-architecture.md` defines the pattern:
decorator lives in `feature/ai/chat/`, receives `RoomUsageRecorder` and
`ProfileAwareCurrentUser` via Koin DI (feature→core dependency allowed).
`AiToolsModule.jvm.kt` binds `Clock.System` locally; the decorator replaces
the raw `KoogAgentService` binding. `UsageRecordingTextGen` now records
every `TextGenPort.generate()` and `streamChat()` call to `RoomUsageRecorder`.

## log-messages-user-content-sweep-deferred

**Found in:** MR-D (tech-debt batch). The redaction decorator scrubs credential
shapes; it does not catch task titles, note bodies, or AI prompt fragments.
**Status: RESOLVED** (tech-debt session, 2026-10-02).

All `e.message` exposures fixed: `ProfileSwitcherViewModel` (lines 99, 106),
`SavedAgendaViewModel` (line 325), `SyncBootstrapper` (line 143) — `${e.message}`
removed from error logs. `AuthRepository` (lines 57, 69) now uses shared
`Redaction.redactEmail()` helper. `Redaction.kt` created in `core/log/` with
`redactEmail()`. Remaining 33 interpolation sites use only ids and technical
metadata.

---

## no-empty-onclick-lambda-rule-findings-sweep-pending

**Status: RESOLVED** (tech-debt session, 2026-10-02).

The rule now excludes `/preview/` directory via `filePath.contains("/preview/")`
check. 37 findings absorbed into baseline after regen. Rule enabled:
`active: true` in `detekt.yml`. The sweep confirmed all non-preview findings
are intentional empty-lambda patterns that need wiring.
---

## no-direct-dispatchers-rule-one-whitelisted-case

**Found in:** MR-B (tech-debt batch). `NoDirectDispatchersRule` bans
`Dispatchers.IO/Default/Main` in production. One legitimate case was
identified: `core/log/FileLogWriter.kt:50` uses
`Dispatchers.IO.limitedParallelism(1)` to guarantee sequential writes.

**Status:** the whitelisting is already done in the rule code
(`isAllowedFile` for `FileLogWriter.kt`). The rule is `active: false`
pending the sweep of any other callers. If no other callers exist, the
rule can stay `active: false` indefinitely — the whitelist is the fix,
not a signal to search for more cases.

**Try next:** confirm no other `Dispatchers` calls in `commonMain` production
code outside `FileLogWriter` and the existing test/fakes whitelists. If
clean, the rule is a documentation asset rather than an active gate.

---

## nav-display-debug-border-not-found

**Found in:** MR-C (tech-debt batch). The plan proposed adding a red-border
debug overlay to `NavDisplay` when `entries.isEmpty()` as a diagnostic for
`desktop-nav-goBack-blank-screen`. Investigation showed no such modifier
exists in the codebase and no obvious place to add it that would survive
the blank-screen bug (the compose tree is empty at that point, so any
modifier on `NavDisplay` would not render either).

**Try next:** this item is closed as "not implementable as described". The
diagnostic approach should instead target the shell layer —
`DesktopShellNav3Root` or `DesktopShellNav3` — where a `LaunchedEffect` or
`remember` on `currentRoute` can be observed before the tree goes blank.
A visible diagnostic there (before the blank) would confirm whether the
route change itself is the trigger.

---

## skill-symbol-clusters-many-fixes-pending

**Found in:** Phase 1.7 (`refactor/openspec-adoption`), via
`check-doc-dead-refs.py --skill-symbols` (detector 8). All ~840 findings
in 9 skill files are accepted in `config/docs/skill-symbol-baseline.txt`.
Zero NEW findings at baseline creation.

The top clusters identified:

1. **`ai-tool` + `llm-usage-tracking` + `cli-tool-surface` + `mcp-server`**:
   `UsageRecorder` (interface, exists), `RoomUsageRecorder` (class, exists),
   `ModelPricing`, `LlmUsageEntity`, `UsageExtractor` — describe an architecture
   that was partially built; the AI usage screen was never completed.
2. **`nav3-nested-graphs` + `cross-feature-navigation`**:
   `AppNavHost.kt` (file does not exist), `AgendaNavGraph` (exists but
   described differently), `NavKey` vs `AppNavKey` (same concept, inconsistent
   naming), `NavDisplay` (exists in `desktopShellNav3`).
3. **`icon-registry`**:
   `TagIconRegistry`, `PriorityIconRegistry`, `NoteColorRegistry` (none exist;
   only `ProjectIconRegistry` is real).
4. **`task-callback-groups`**:
   `NoteCardActions` (should be `NotesActions`), `TaskDetailActions`
   (check if this file actually exists in `feature/tasks/components/`).

**Status:** OPEN. These skills describe an architecture that no longer matches
the code. Fixing them requires reading the actual code and rewriting the
skills — too large for a single PR. They are guarded by the baseline:
if an agent adds a NEW dangling symbol reference in any of these skills,
CI will fail. The backlog owner should prioritize `nav3-nested-graphs`
(first referenced by `wayfinder`) and `ai-tool` (most complex).

---

## task-detail-coordinator-graph-test-times-out-under-parallel-load

**Found in:** 2026-10-04, while verifying the identity-derivation change across three
modules in one Gradle invocation (`:shared:jvmTest :desktopApp:test :mcp-server:test`).

**Symptom:** `TaskDetailCoordinatorGraphTest.coordinator_built_from_di_graph_leaves_loading`
fails with `TimeoutCancellationException: Timed out waiting for 10000 ms` on
`coordinator.state.first { it is Loaded }`. It passes 3/3 when run alone (7-20s per run).

**Not the identity change.** `CurrentUser.currentSession` is a `StateFlow` in both the
interface and `FakeAuthRepository`, so `liveScopedUserId` emits on first collection exactly
as the cached `scopedUserId` did; the only difference is one `combine` operator. The failure
reproduces only when three modules build and run concurrently on this host.

**Why the test uses real time at all:** the coordinator's scope is
`createBackgroundScope()` — real `Dispatchers.Default` — so a `withTimeout` on
`runTest`'s virtual clock would expire instantly instead of waiting for the real worker.
The 10s real-time budget is therefore a deliberate, documented choice, not an oversight.

**Real issue:** a wall-clock budget makes the suite's correctness depend on host load.
The project rule (see `singularity-todo-test-flaky-prevention`) is that tests must not
depend on real elapsed time.

**Try next:** inject a `TestScope`/background scope into `TaskDetailCoordinator` for tests
so the wait becomes virtual-time and instantaneous; failing that, replace the single 10s
budget with a bounded poll that reports the observed wait on failure, so a slow host fails
loudly with data instead of looking like a hang. Do NOT simply raise the number.

---

## baseline-write-pipeline-verification-was-asserted-not-checked

**Found in:** the OpenSpec backlog pass, 2026-10-04, while closing out
`navigation-open-policy` and noticing that `openspec/changes/archive` was empty
while four changes sat active.

`baseline-write-pipeline` is a *baseline* spec — it documents behaviour the system
already has, with a 13-item verification checklist. Every item named a covering
test. Checking the names against the suite: `FakeRepositoryFidelityTest` contains
no reference to the outbox, `enqueue` or an affected-row count (all 14 of its tests
are about read isolation and soft-delete), and `EntityMapperCompletenessTest` never
reads a `@Query` at all — it compares mapper field access against a hand-maintained
table. **Five attributions were wrong**, and REQ-WP-050's premise had quietly
stopped holding: its `FIELD_ALLOWLIST` is empty and `BackupImporter` appears in
neither the entity table nor the mapper table.

The checklist is now rewritten with two states instead of one — `verified` with the
asserting test quoted, and `not covered` with the gap named. Seven requirements
have no assertion at all: REQ-WP-002 (affected-row return values), 012 (narrow
updates re-read before enqueueing), 020/021 (outgoing-link persistence and
atomicity), 030 (note tool routing — the existing test would also pass against a
DAO bypass using the same id), 031 (canonical HTML storage), 041 (id-only writes
rely on the DAO layer).

**Already ruled out:** not an OpenSpec process problem. The change is correctly left
unarchived — a baseline spec whose verification is 6/13 is not finished work, and
ticking the remaining boxes without assertions would recreate the defect.

**Try next:** close them in `ScopedWriteQueryIsolationTest` (new, 2026-10-04 — it
already owns the SQL-level write invariants and has an allowlist that requires a
reason per entry) rather than in a new file. REQ-WP-002 and REQ-WP-041 are the two
worth doing first: a DAO mutation that returns zero rows silently is exactly the
shape of defect that survives every other gate in this repo.

---

## maestro-smoke-cannot-run-in-this-environment

**Found in:** B5 verification of `navigation-open-policy`, 2026-10-04.

Every Maestro flow fails with `DeviceServerDiedException` on `deviceInfo` (~130ms),
including the untouched control flow `04-delete.yaml`, so it is not the branch under
test. The emulator is alive (`adb shell echo ok` responds) and
`scripts/ensure-emulator.sh` finds the AVD already running, so the usual cold-start
path is not involved.

**Already ruled out:** not a tag problem (`MaestroFlowTagsTest` passes 106/106), not
an APK problem (`:androidApp:assembleDebug` is green), not a device problem.

**Try next:** this is a host/driver problem, so it is not a refactor. See ADR
`2026-09-28-emulator-gfxstream-colorbuffer-segv` for the gfxstream history — the
standing instruction is not to pass `-gpu` flags, because the default host GPU path
fails periodically and has no cure. The next useful step is a fresh boot with
`adb emu kill` + `ensure-emulator.sh` and a re-run of the control flow alone; if that
still fails, the fix belongs to the emulator image, not to this repository.
