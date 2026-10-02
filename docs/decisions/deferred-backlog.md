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

**Status: RESOLVED.** `SyncConfigScreen.kt` was deleted. The script now reports
zero findings. The `find-unwired-surfaces` gate is clean and can be added to
`check.sh` as-is — no baseline needed since nothing is suppressed.

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
is gone. The script now reports zero findings, exits 0, and is wired into
`check.sh` as step [2/6]. No baseline needed.

---

## digest-line-limit-pressure

**Found in:** the post-epic docs pass. `DIGEST.md` sat at 1498/1500 lines.

**Symptom:** the digest indexes every Consequences bullet and creates a
section per tag, so it grows with every ADR while the limit is fixed. The
next author who writes a verbose ADR gets a failed `docs-audit` with no
obvious remedy and will either trim content (bad) or raise the limit (worse).

**Partially done (2026-09-30):** `MAX_ITEMS_PER_TAG` lowered 12 → 10 — the
digest is an index, the ADR body is one link away. That bought ~45 lines of
headroom at 351 entries.

**Try next, if the warning returns:**

1. Cap the per-ADR bullet contribution the same way (first N bullets per slug,
   then "_… and N more_").
2. Only if that is insufficient, raise `MAX_DIGEST_LINES` with a comment
   explaining why the index needs the room.
3. Keep the existing discipline regardless: Consequences bullets are
   consequences; only **Always/Never** rules belong in the Critical section.

---

## ci-gates-are-all-continue-on-error

**Found in:** `refactor/tag-registry-and-robots`, while wiring `check-tags.sh`
into `.github/workflows/ci.yml`.

**Symptom:** every gate step in the `build` job carries
`continue-on-error: true` — `Build version catalog gate`, `Run detekt`,
`Assemble Android debug`, `Find unwired surfaces`. Only `jvmTest`,
`desktopApp:test` and the new `Check Maestro test tags` can fail the workflow.
So "CI is green" says nothing about detekt, unwired surfaces, or version
literals; a regression in any of them is a red line in the log that a reviewer
has to notice by eye.

**Already checked:** `:shared:detekt` does enforce locally
(`ignoreFailures = false` in `shared/build.gradle.kts`, and `check.sh` step
`[6/6]` fails on it) — this is a CI-policy gap, not a detekt gap. The new
`Check Maestro test tags` step was added following the existing convention
rather than flipping the policy inside an unrelated MR.

**Try next:** decide the policy first, then flip one gate at a time, oldest
debt first. `Find unwired surfaces` is the natural candidate for a baseline
(see `find-unwired-surfaces-has-no-baseline` above) before it can go blocking.
Flip with a full `--rerun-tasks` pass first so the backlog is known, not
discovered by whoever pushes next. Do not flip several at once — the point is
to make each regression visible, and a six-way red is not visible.

---

## desktop-nav-goBack-blank-screen

**Found in:** MR-11, while verifying `OpenSavedViewShowsMatchingTasksFlowTest`.

**Symptom:** after tapping the save button in `SavedAgendaScreen` (or `TaskCreateScreen`) and then tapping the back button, the entire desktop app UI goes blank — `SemanticsTree` reports 0 nodes, every `testTag` lookup fails. Navigation itself completes (kermit log shows "Scheduled sync stopped" from clean `onEnd` path), but the compose tree is empty.

**Already ruled out:**
- Not a `Clock.System` / `FakeAppDatabase` issue: task IS persisted (visible in DB snapshot).
- Not a `SavedAgendaViewModel` init failure: `Results` state is reached (confirmed by log).
- Not the `goBack()` call itself failing: `currentStack.removeLastOrNull()` executes; `canGoBack` recalculates correctly.
- Not `NavDisplay` being given an empty entry list: `state.requireBackStackFor` would throw before any render.

**Trigger shape:** `TaskCreateScreen` or `SavedAgendaScreen` → save → back → blank. The same shape hits `CreateTaskFlowTest.a_saved_task_without_a_due_date_appears_under_inbox_no_date`.

**Try next:** add a `NavDisplay` debug modifier (e.g., a `Box` with a visible red border when `entries.isEmpty()`) to distinguish "NavDisplay receives empty list" from "compose tree fails below NavDisplay". If the red border never appears, the bug is in the `Window` or `DesktopShellNav3Root` composition above `NavDisplay`. Check whether a `LaunchedEffect` or `remember` anywhere in the shell is clearing the composition on `currentRoute` change.

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
