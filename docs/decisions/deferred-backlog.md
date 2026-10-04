# Deferred backlog

Findings that were real enough to record and too expensive to fix where they
surfaced. Each entry names where it was found, what was already ruled out, and
what a future attempt should try **first** — so the next person does not repeat
the dead ends.

Rule for adding: an entry needs a number, the MR that found it, and the checks
already performed. "Looks wrong" is not an entry.

**Every open entry is also a GitHub issue**, and the entry names it under
"Tracked as". Two reasons: the backlog is the reasoning, the tracker is the
queue, and a reader who finds one should not have to find the other. And a
backlog entry that describes an *environment* rather than the code decays —
`maestro-smoke-cannot-run-in-this-environment` was filed from here, then
disproven on re-measurement within the hour. Re-run the entry's own "checks
already performed" before acting on any entry whose subject is the host.

---

## sync-config-screen-unwired

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `SyncConfigScreen.kt` is gone (`58c82f80`), and `find-unwired-surfaces.py` runs blocking in `ci.yml:226` with no `continue-on-error`. **Closed #27's sibling bookkeeping at the same time** — the gate is the deliverable here and it is enforced.

**Found in:** MR-1 retrospective, `scripts/find-unwired-surfaces.py`. Pre-existing;
not a regression from the desktop UI work.

`feature/sync/presentation/SyncConfigScreen.kt` was a public `@Composable` with no
call site. `SyncViewModel` was fully built and registered.

**Status: RESOLVED.** `SyncConfigScreen.kt` was deleted. The `find-unwired-surfaces`
gate is now blocking in CI (Phase 1.1, PR-2).

---

## saved-views-crud-flow-selects-a-snackbar-that-does-not-exist

**Status (re-verified 2026-10-04):** PARTIALLY CLOSED, re-tracked 2026-10-04 as #110. The red flow is fixed, but `SNACKBAR_SAVED` is still declared (`TestTags.kt:273`) and its `knownUnapplied` reason (`TestTagsWiringTest.kt:78`) describes a flow that no longer exists. See the entry body for the two residues.

**Tracked as:** #110
**OpenSpec change:** `openspec/changes/notification-routing-must-be-total/`

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

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `LogBootstrap.jvm.kt:18` and `.android.kt:20` both install `RedactingLogWriter(FileLogWriter(...))`; `grep -rn "LogExporter\|LoggerHolder"` returns zero hits, so the dead ports are gone. The residual ("logs have no way to leave the device") is a separate entry, #37.

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

**Tracked as:** #37

**Found in:** the logging epic retrospective (MR-2), when `LogExporter` was
deleted instead of implemented.

**Tracked as:** #37

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

**Tracked as:** #36

**Found in:** MR-4, while deleting dead code. `TaskMutationsUseCase` was on
the deletion list and was **kept** — see the note below.

**Tracked as:** #36

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

**Tracked as:** #38

**Found in:** MR-4. The plan listed two dead symbols in
`core/auth/oauth/OAuth.kt`; the file as a whole is unreachable.

**Tracked as:** #67

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

**Tracked as:** #43

**Found in:** MR-3 retrospective. The redaction decorator catches credential
shapes; it does not catch task titles, note bodies, or AI prompt fragments.

**Tracked as:** #68

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

**Tracked as:** #40

**Found in:** MR-5 final `./check.sh` — the only observation in five runs.

**Tracked as:** #40

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

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `scripts/find-unwired-surfaces-baseline.txt` exists with 11 entries, the script returns 0 only when the baseline filter clears every finding, and `ci.yml:225` runs it blocking. A scan that finds nothing and a scan looking in the wrong place are now distinguishable.

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

**Tracked as:** #52

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
**Tracked as:** #52

**Symptom:** the digest indexes every Consequences bullet and creates a
section per tag, so it grows with every ADR while the limit is fixed. The
next author who writes a verbose ADR gets a failed `docs-audit` with no
obvious remedy and will either trim content (bad) or raise the limit (worse).

Remaining pressure is the "Active entries" index — one line per ADR, 421 lines and
growing by one per decision. It is the lowest-value section in the file (a title
list, one `ls` away). If the warning returns, cut that section before raising the
limit.

Also resolved on this branch: the budget was found **already red** before either
side touched it — the digest sat at 1255 against a 1250 limit and `AGENTS.md` at
253 against 250. The two caps above brought it back under. ADR:
`2026-10-04-doc-size-budget-was-already-red.md`.

---

## ci-gates-are-all-continue-on-error

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04; **#53 closed with the evidence.** `grep -n "continue-on-error" ci.yml` returns exactly two hits — the Maestro tag lint (:249) and the flake-analysis annotation (:166) — both with a documented reason. The version-catalog, assembleDebug and mcp-server gates are all blocking, and mcp-server additionally runs `check-test-runs.py --require mcp-server:test`.

**Status: RESOLVED (2026-10-04).** All four remaining advisory gates are blocking:
`Run detekt`, `Assemble Android debug`, `Build version catalog gate`, and the whole
`mcp-server` job. See ADR `2026-10-04-measurement-integrity`.

The `mcp-server` job was the one that mattered: it holds the profile-bootstrap
identity tests, so the P0 data-corruption fix shipped with the tests that cover it
unable to fail a build.
**Tracked as:** #53

**Symptom:** every gate step in the `build` job carried
`continue-on-error: true` — `Build version catalog gate`, `Run detekt`,
`Assemble Android debug`, `Find unwired surfaces`. Only `jvmTest`,
`desktopApp:test` and `Check Maestro test tags` could fail the workflow.
So "CI is green" said nothing about detekt, unwired surfaces, or version
literals.

`Check Maestro test tags` stays `continue-on-error: true` on purpose, and the
comment says why: it is superseded by `MaestroFlowTagsTest` in `:shared:jvmTest`,
which is blocking and covers the same tag registry. A non-blocking step that is
documented as a convenience for local use is not a hole; one that duplicates a
blocking gate and is *believed* to be the gate is.

---

## desktop-nav-goBack-blank-screen

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04; **#27 closed with the evidence.** `JvmNavEntries.kt:51-67` hoists the per-feature stacks into the shell composition and passes them as `backStack =` into the graphs. `SavedAgendaCreateFlowTest` and `OpenSavedViewShowsMatchingTasksFlowTest` both report 1 test, 0 failures.

**Status:** ✅ RESOLVED (2026-10-04) — fixed by ADR `2026-10-04-navigation-policy`, issue #27 closed.

**Found in:** MR-11, while verifying `OpenSavedViewShowsMatchingTasksFlowTest`.

**Tracked as:** #27

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

**Tracked as:** #42

**Found in:** `refactor/tag-registry-and-robots`, while fixing the
`NoDirectClockSystem` violation that shipped in `2e99b1d0`.

**Tracked as:** #42

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

**Tracked as:** #106
**OpenSpec change:** `openspec/changes/usage-recording-platform-parity/`

**Status: HALF RESOLVED — corrected 2026-10-04.** The line below said
"RESOLVED" and the claim was only true of the JVM. `AiToolsModule.jvm.kt:105-118`
wraps `TextGenPort` in `UsageRecordingTextGen`; `AiToolsModule.android.kt:102`
still binds a raw `KoogAgentService` with no decorator, so no Android LLM call is
ever recorded. Each platform's build is green, which is why it went unnoticed —
the feature looks alive on the development platform and is absent on the one
users run. Tracked as #106.

ADR `2026-10-02-usage-recording-textgen-architecture.md` defines the pattern:
decorator lives in `feature/ai/chat/`, receives `RoomUsageRecorder` and
`ProfileAwareCurrentUser` via Koin DI (feature→core dependency allowed).
`AiToolsModule.jvm.kt` binds `Clock.System` locally; the decorator replaces
the raw `KoogAgentService` binding. `UsageRecordingTextGen` now records
every `TextGenPort.generate()` and `streamChat()` call to `RoomUsageRecorder`.

## log-messages-user-content-sweep-deferred

**Status (re-verified 2026-10-04):** CLOSED as originally scoped, verified 2026-10-04, with one caveat recorded rather than glossed. `Redaction.redactEmail()` exists and is used at `AuthRepository.kt:58,70`; the `${e.message}` interpolations in `ProfileSwitcherViewModel`, `SavedAgendaViewModel` and `SyncBootstrapper` are gone. **Caveat:** `FileLogWriter.kt:102` still logs `${e.message}`, and `QueryParser.kt:163` embeds user query text in an exception message that reaches `SearchViewModel.kt:218` — a user-visible path rather than a log. The wider user-content sweep is #43; this entry covered the credential-shaped exposures and those are done.

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

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. The rule is registered in `META-INF/services`, active in `config/detekt/detekt.yml:368`, and exempts preview code by filename or `/preview/` path (`NoEmptyOnClickLambdaRule.kt:151-157`). The entry's "37 findings" no longer matches the tree — the preview exclusion did the work, not the baseline, which is down to 3 entries.

**Status: RESOLVED** (tech-debt session, 2026-10-02).

The rule now excludes `/preview/` directory via `filePath.contains("/preview/")`
check. 37 findings absorbed into baseline after regen. Rule enabled:
`active: true` in `detekt.yml`. The sweep confirmed all non-preview findings
are intentional empty-lambda patterns that need wiring.
---

## no-direct-dispatchers-rule-one-whitelisted-case

**Tracked as:** #44

**Found in:** MR-B (tech-debt batch). `NoDirectDispatchersRule` bans
`Dispatchers.IO/Default/Main` in production. One legitimate case was
identified: `core/log/FileLogWriter.kt:50` uses
`Dispatchers.IO.limitedParallelism(1)` to guarantee sequential writes.

**Status (corrected 2026-10-05):** the whitelisting is in the rule code. The rule had no
`detekt.yml` block at all, so it never ran; a block was added that day
(`no-direct-dispatchers` / `NoDirectDispatchers`, `active: true`).
**Tracked as:** #44

**Status:** the whitelisting is already done in the rule code
(`isAllowedFile` for `FileLogWriter.kt`). The rule is `active: false`
pending the sweep of any other callers. If no other callers exist, the
rule can stay `active: false` indefinitely — the whitelist is the fix,
not a signal to search for more cases.

**But "0 findings" proved nothing, and this entry previously claimed it proved
something.** The rule could not fire for any input: it required the dot-qualified
selector to be a `KtCallExpression`, but in `Dispatchers.IO` the selector is a
`KtNameReferenceExpression` (`IO` is a property), and in
`Dispatchers.IO.limitedParallelism(1)` the receiver is itself dot-qualified. Both shapes
returned early. It was a registered, packaged, ADR-referenced no-op.

Fixed the same day, together with the scope. The rule is now scoped to **commonMain
production** only, which is what its KDoc always claimed: verified against the tree,
commonMain has exactly one occurrence (`FileLogWriter.kt:50`, the whitelisted line),
while jvmMain has 8 and androidMain has 11 — all inside port implementations, where
choosing the dispatcher is the KMP convention rather than a violation. With the rule
actually working, `:shared:detekt` reports 0 findings, and *this time that means
something*: it was verified by 17 tests, not inferred from a silent rule.

**Try next:** if a new legitimate commonMain call site appears, add it to
`ALLOWED_FILE` in `NoDirectDispatchersPolicy` rather than disabling the rule.

---

## nav-display-debug-border-not-found

**Tracked as:** #45

**Found in:** MR-C (tech-debt batch). The plan proposed adding a red-border
debug overlay to `NavDisplay` when `entries.isEmpty()` as a diagnostic for
`desktop-nav-goBack-blank-screen`. Investigation showed no such modifier
exists in the codebase and no obvious place to add it that would survive
the blank-screen bug (the compose tree is empty at that point, so any
modifier on `NavDisplay` would not render either).

**Tracked as:** #45

**Try next:** this item is closed as "not implementable as described". The
diagnostic approach should instead target the shell layer —
`DesktopShellNav3Root` or `DesktopShellNav3` — where a `LaunchedEffect` or
`remember` on `currentRoute` can be observed before the tree goes blank.
A visible diagnostic there (before the blank) would confirm whether the
route change itself is the trigger.

---

## skill-symbol-clusters-many-fixes-pending

**Tracked as:** #41

**Found in:** Phase 1.7 (`refactor/openspec-adoption`), via
`check-doc-dead-refs.py --skill-symbols` (detector 8). All ~840 findings
in 9 skill files are accepted in `config/docs/skill-symbol-baseline.txt`.
Zero NEW findings at baseline creation.

**Tracked as:** #41

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

**Tracked as:** #39

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

**Tracked as:** #35

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

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04 (#46 already closed). The symptom was environmental and disproven on re-probe; what it surfaced was a flow bug, tracked as #50, and the fact that the smoke job had never run, tracked as #87. Keeping this entry open would have re-asserted a host claim that was already shown false.

**Status: CLOSED as disproven (2026-10-04).** Re-probed with the emulator up:
the flow ran to completion and failed on a real assertion, with no
`DeviceServerDiedException`. The environment recovers; the blocker was transient.
The failure it surfaced is a bug in the flow, tracked as #50. Kept below because
the original symptom can return, and the record of what it was is worth more
than a deleted paragraph.

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

---

## agenda-section-add-button-noop

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `AgendaPresets.kt` carries 15 `prefill = SectionPrefill` sites (the entry said 13) and `AgendaViewModel.kt:190` resolves `relativeDueDate` through the injected clock, so `section.prefill ?: return` can no longer fire. Subsumed by #26.

**Found in:** MR-0, при написании тест-плана agenda-views (кодовая разведка).

**Symptom:** кнопка «+» в заголовке секции в `AgendaScreen` при тапе вызывает `AgendaIntent.CreateInSection` → `handleCreateInSection(sectionId)`. Функция делает `scope.launch { handleCreateInSection(intent.sectionId) }`, внутри:
```kotlin
val section = definition.sections.find { it.effectiveId == sectionId } ?: return
val sectionPrefill = section.prefill ?: return   // ← early return, prefill == null
```
Ни один preset в `AgendaPresets` не задаёт `SectionPrefill`; `Section.prefill` всегда `null`. Тап на «+» silently no-op.

**Status: RESOLVED** (MR-1, 2026-10-03). `AgendaPresets` now sets `prefill` on
**13 sections** across every preset (`AgendaPresets.kt:42-202`): `Inbox`/
`Today`/`Upcoming`/custom all carry a `SectionPrefill`, so the early return at
`section.prefill ?: return` no longer fires. The prefill dates are
`RelativeBucket` values, not constants — see `section-prefill-dynamic-date`
below, which this fix depended on.

**Try next:** добавить `prefill = SectionPrefill.Date` в каждую секцию Inbox/Today/Upcoming с `RelativeBucket`-compatible датой.

---

## notification-text-null-invisible

**Tracked as:** #103
**OpenSpec change:** `openspec/changes/notification-routing-must-be-total/`

**Found in:** MR-0, свип `Notification.Text(x, null)` по production commonMain.

**Symptom:** `NotificationHost.kt:72-78` роутит `Notification.Text(title, text?)` в `ResultDialog`, у которого `if (text == null) return` — ничего не рендерится. Три живых сайта:

1. `TaskDetailContent.kt:58` и `TaskDetailViewScreen.kt:87`: `is TaskDetailUiEvent.Saved → Notification.Text(event.message, null)`.
2. `SavedAgendaListScreen.kt:72`: `is SavedAgendaListEvent.CopySuccess → Notification.Text("Copied to ${e.targetProfileName}", null)`.

Копирование view в профиль показывает пользователю **ничего**.

**Status: RESOLVED** (MR-1, 2026-10-03). All three sites were rewritten:

1. `TaskDetailViewScreen.kt:86` — `is TaskDetailUiEvent.Saved → Notification.None`.
   The screen already leaves the editor on save, so a message would be noise.
2. `SavedAgendaListScreen.kt:82` — `CopySuccess` now shows a snackbar through
   `snackbarHostState.showSnackbar("Copied to ${e.targetProfileName}")`, so the
   user sees the profile the view landed in.
3. `TaskDetailContent.kt` no longer exists; its event mapping moved into
   `TaskDetailViewScreen.kt`.

A sweep of production `commonMain` finds four remaining `Notification.Text`
sites (`ArchiveScreen`, `NoteEditorNotifications`, `ProjectsScreen` ×2) and
**all four pass a non-null `text`**, so none of them hits `ResultDialog`'s
`if (text == null) return`.

**Still latent (not a bug, a design hazard):** `NotificationHost.kt:72` still
routes `Notification.Text` to `ResultDialog`, and `ResultDialog` still returns
silently on a null text. Nothing produces that combination today, so there is
nothing to fix — but the next person who writes `Notification.Text(title, null)`
gets silence, not an error. A follow-up would make the routing total (route a
null text to the snackbar host, or make the parameter non-null).

---

## is-saving-clobber

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `SavedAgendaViewModel.emitEditingState()` carries `existingIsSaving` forward off the replaced state, and `onSave()` returns early on `current.isSaving`. Pinned by `SavedAgendaViewModelTest.anEditDuringAnInFlightSaveDoesNotReEnableTheSaveButton` against the `upsertCount`/`upsertGate` fakes.

**Found in:** MR-0, кодовая разведка `SavedAgendaViewModel.emitEditingState()`.

**Symptom:** `onIntent(NameChanged)` вызывает `emitEditingState()`, который делает `setState(Editing(..., isSaving = current.isSaving))`. Если `NameChanged` приходит во время in-flight `save` (пока `isSaving = true`), новый state перезаписывает `isSaving` в `false` — кнопка Save снова enabled, пользователь может нажать повторно и создать дубликат.

**Status: RESOLVED** (2026-10-04, with a pin test). The symptom above describes
the guard as *absent*; the code already had both halves, and what was missing
was anything proving it:

- `SavedAgendaViewModel.emitEditingState()` (`:234`) reads `isSaving` off the
  state it replaces and carries it forward — so no draft intent can clear it.
- `onSave()` (`:247`) returns early on `current.isSaving`.

Neither was pinned, and a map-backed fake cannot pin it either way: a second
`upsert` of the same row leaves the store byte-identical, so the naive
assertion passes whether the guard exists or not. `FakeSavedAgendaViewsRepository`
gained an `upsertCount` counter and an `upsertGate` hook to hold a write open,
and `SavedAgendaViewModelTest.anEditDuringAnInFlightSaveDoesNotReEnableTheSaveButton`
parks a save inside the repository, fires a `NameChanged` at it, and asserts
`isSaving` is still `true` and `upsertCount == 1`.

Teeth verified 2026-10-04: reverting `isSaving = existingIsSaving` to a
literal `false` turns the test red, which is the only way to know the test is
about the guard rather than about the fake.

---

## agenda-views-not-in-backup

**Tracked as:** [#77](https://github.com/gazon1/singularity-clone-kmp/issues/77) · OpenSpec change `backup-include-remaining-tables` (proposed)

**Found in:** MR-0, свип BackupPayload vs Room tables.

**Symptom:** `agenda_views` таблица (Room) не входит в `BackupPayload`. При restore из backup все saved views теряются. Также отсутствуют: `task_reminders`, `project_reminders`, `checklist_items`, `tag_groups`, `project_tag_groups`, `saved_searches`, `time_entries`, `profiles`.

**Status: PARTIALLY RESOLVED** (MR-1, 2026-10-03). `agenda_views` is in the
backup: `BackupPayload.agendaViews` (`:17`), `BackupExporter` reads it
(`:37`, `:49`) and counts it in the manifest (`:65`), `BackupImporter` writes it
back (`:120`), and `BackupFormat.kt:5` records the version bump.

The other eight tables are untouched and remain a real gap: `task_reminders`,
`project_reminders`, `checklist_items`, `tag_groups`, `project_tag_groups`,
`saved_searches`, `time_entries`, `profiles`. Note the two profile tables are
a *different* problem from the other seven — cross-profile restore needs a
decision about which profile becomes active, not just a DTO.

---

## delete-without-confirm-or-undo

**Tracked as:** #104
**OpenSpec change:** `openspec/changes/notification-routing-must-be-total/`

**Found in:** MR-0, свип delete flows по всем screens.

**Symptom:** `ConfirmActionDialog` используется только в 2 местах: `SavedAgendaScreen` delete-view и `ProfileSwitcherScreen` delete-profile. Остальные 10 delete flow'ов удаляют мгновенно и молча:

- `AgendaContent.kt:314` → `AgendaViewModel.kt:98` (`TaskDeleteClicked`, no event)
- `SavedAgendaListScreen.kt:107` → `SavedAgendaListViewModel.kt:78`
- `ProjectDetailBody.kt:111` → `ProjectDetailViewModel.kt:349`
- `NotesListScreen.kt` × 5 мест → `NotesListViewModel.kt:250`
- `ProjectsScreen.kt:80` → `ProjectsViewModel.kt:104`
- `TagsScreen.kt:149` → `TagsViewModel.kt:102`
- `TagGroupsScreen.kt:103` → `TagGroupsViewModel.kt:78` (каскадное!)
- `SearchScreen.kt:143` → `SearchViewModel.kt:314`
- `SettingsScreen.kt:261` → `BackupViewModel.kt:193`
- `AttachmentTile.kt:76` → `AttachmentsViewModel.kt:60`

KDoc `Notification.kt:17-27` предписывает `Notification.Undo` для deletes. Паттерн существует в `TaskDetailViewScreen.kt` для task delete.

**Status: OPEN.** Политика: строчные deletes (tasks, notes, tags, views, searches, attachments) → `Notification.Undo`; каскадные/невозвратные (проект, группа тегов, backup-файл) → `ConfirmActionDialog`. Фиксируется в MR-1.

---

## fake-clock-unused-in-desktop-harness

**Tracked as:** #108
**OpenSpec change:** `openspec/changes/selector-and-tag-identity/`

**Found in:** MR-0, свип desktop harness и FakeClock.

**Symptom:** `FakeClock` существует (`shared/src/commonMain/.../test/fakes/FakeClock.kt`) с API `advance(Duration)`, `setNow(Instant)`, `today(zone)`. Имеет **0 упоминаний** в `desktopApp/src/jvmTest`. `runDesktopAppTest` не принимает clock-параметр. Все desktop flow-тесты используют `todayInSystemZone()` → реальное время хоста → date-dependent тесты флакиют на границах месяца/недели.

`CalendarFlowTest` так уже падал: «passed on September 30th, failed on October 1st».

**Status: OPEN.** Фиксируется в MR-2 (тест-инфраструктура): добавить `fakeClock: FakeClock? = null` параметр в `runDesktopAppTest`, подключать через `overrides = module { single<Clock> { fakeClock } }` (Koin last-wins). Закрыть backlog-пункт `no-direct-clock-system-kdoc-claims-tests-are-exempt`.

---

## vm-without-unit-tests

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `ViewModelTestCoverageTest` fails on any ViewModel without a test unless it is in the `KNOWN_UNCOVERED` set, and the KDoc requires each allowlist entry to name its backlog entry here — so the debt is a reviewable list rather than a silent default. The residual is the allowlist itself and is tracked as #83; this entry closed because the mechanism that keeps the debt visible exists.

**Found in:** MR-0, свип ViewModel vs *ViewModelTest.

**Symptom:** 12 production ViewModel'ов не имеют выделенного `*ViewModelTest`:
`SavedAgendaListViewModel`, `TagGroupsViewModel`, `TagsViewModel`, `SearchViewModel`, `ArchiveViewModel`, `AttachmentsViewModel`, `AuthViewModel`, `CalendarSyncViewModel`, `AccountSettingsViewModel`, `ProfileSwitcherViewModel`, `AppVersionGateViewModel`, `AiUsageViewModel`.

`SavedAgendaListViewModel` относится к agenda-views фиче и будет покрыт в MR-3.

**Status: RESOLVED** (2026-10-04). This entry and `vm-without-test` below were
the same finding, written twice: this one names the plan, the other names the
debt. It is kept as the historical record and is not the entry to read.

What happened: `SavedAgendaListViewModelTest` was written (jvmTest, alongside
`SavedAgendaViewModelTest` and `SavedAgendaViewsRepositoryImplTest`), and the
Konsist idea was replaced by a cheaper JVM arch test,
`ViewModelTestCoverageTest`, which fails on any ViewModel lacking a test unless
it is in an explicit `KNOWN_UNCOVERED` allowlist. The remaining debt and the
next VMs to drain are tracked in **`vm-without-test`**.

---

## docs-rot-agenda-selector-count

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `Selector.kt` declares exactly 14 variants and both `SelectorDescriptor.kt:16` and `SelectorMatcher.kt:25` say "All 14" (`92357b6f`). `grep -rn testIncludes` now hits only this backlog file.

**Found in:** MR-0, кодовая разведка Selector.kt.

**Symptom:** KDoc в `SelectorDescriptor.kt:16` и `SelectorMatcher.kt:25` говорит «All 13 Selector variants». Реальное количество: **14** (Selector.kt: 11 leaf + 3 composite). ADR `2026-09-17-selector-serializer-plain-kserializer.md:10` говорит «15 concrete subtypes» (тоже stale). Docs-decision `2026-10-01-post-mr-10-findings.md:75`, `post-mr-14-findings.md:117`, `post-mr-11-findings.md:63` упоминают `-PtestIncludes` — флаг **не существует** в build scripts (Gradle silently ignores unknown `-P` flags).

**Status: RESOLVED** (2026-10-04). The KDoc now says 14 in both places
(`SelectorDescriptor.kt:16`, `SelectorMatcher.kt:25`), verified against
`Selector.kt`, which declares 14 `data class`/`data object` variants. The ADR's
"15 concrete subtypes" and the three `-PtestIncludes` references were stale doc
claims, not live instructions: `grep -rn testIncludes` over the build scripts
returns nothing, and the only remaining mention of the flag anywhere in `docs/`
is the line quoted above.

**Residue worth keeping in mind:** the count was wrong because it was a hand-
maintained number in prose. The `SelectorTemplate` catalogue added in MR-6
(`feature/agenda/domain/selector/SelectorTemplate.kt`) is the thing to point at
instead — it is code, so a new variant shows up as a compile error at the
catalogue rather than as a number that quietly goes stale.

---

## agenda-reachability-byTags-no-ui-entry

**Tracked as:** [#81](https://github.com/gazon1/singularity-clone-kmp/issues/81) · OpenSpec change `agenda-tags-entry-point` (proposed)

**Found in:** MR-0, кодовая разведка навигации.

**Symptom:** `AgendaPresets.byTags(ids: Set<TagId>)` существует (multi-tag), но UI-входа нет. `byTag(single)` доступен через Search → tag chip → `AgendaStartRoute.Tag`. Multi-tag view (matchAll и any-tag) недоступен через UI.

**Status: PARTIALLY RESOLVED** (2026-10-04, and the entry was out of date).

The premise no longer holds. `SelectorTemplate.ByTags` is in the section
configurator's catalogue and resolves to `Selector.Tags(ids)`, with the user's
tags as a **multi-select** option list (`SavedAgendaScreen.kt`:
`is SelectorTemplate.ByTags -> tags.map { … }`). A multi-tag view is reachable
from the editor today, and the `// TODO: known gap — no UI entry for byTags`
marker this entry referred to is gone from the tree.

What genuinely had no UI was the other half: `ByTags(matchAll = true)` existed
in the engine with `matchAll = false` hardcoded as the only constructible value
from the editor, so "tasks carrying **all** of these tags" could not be built.
`SelectorParameterSheet` now renders a "Match all of these tags" checkbox for
that one template, threads the flag through `onConfirm`, and rebuilds the
template with `.copy(matchAll = …)` before resolving — any other template
returns itself, so the shared path is untouched. Pinned by
`SelectorTemplateTest.by tag matchAll flips the resolved selector semantics`,
which asserts the *semantics* differ and not merely the ids, because both
resolutions carry the same id set.

**Still open:** a first-class "view these N tags as an agenda" entry point
(e.g. from the Tags screen). The editor can build the section; nothing starts
one. That is a product decision, not a gap in the engine.

---

## agenda-editor-no-selector-parameter-configuration

**Tracked as:** #107
**OpenSpec change:** `openspec/changes/selector-and-tag-identity/`

**Found in:** MR-0, кодовая разведка SavedAgendaScreen.kt AddSection sheet.

**Symptom:** `ListPickerSheet<Selector>` в `SavedAgendaScreen.kt:190-198` предлагает **7 жёстко зашитых шаблонов** (Active, Completed, Due today, Overdue, No date, This week, Next week). Редактор **не позволяет** пользователю задать параметры селектора: тег/проект/приоритеты/regexp/диапазон дат. `SectionEditorCard` — read-only display, только Move Up/Down и Delete.

`Selector.Tags`, `Selector.Projects`, `Selector.Priorities`, `Selector.Regexp`, `Selector.DateRange` доступны в движке, но **не в UI**.

**Status: RESOLVED** (2026-10-04). `SavedAgendaScreen.kt` gained
`SelectorParameterSheet` (`:276`), which opens a `MultiSelectSheet` (`:291`)
of live options for the selected template: tags, projects, priorities, status
and date buckets. The engine types listed above — `Selector.Tags`,
`Selector.Projects`, `Selector.Priorities`, `Selector.Regexp`,
`Selector.DateRange` — are now reachable from the editor.

Three things were worth getting right, and each is a trap the naive version
falls into:

1. **Option list = validation list.** The ids submitted are validated against
   the same option list the picker showed. Rebuilding the list at submit time
   produced an empty set, and `Selector.Tags(emptySet())` matches zero tasks —
   a section that silently filters everything away, with no error anywhere.
2. **Unresolvable selection returns `null`,** not an empty selector. A section
   with no valid selection is not created.
3. **Stale ids are dropped from the sheet,** so a tag deleted after the view was
   saved does not leave an unselectable row.

`MultiSelectSheet` exists because `ListPickerSheet` is single-select by
contract — it calls `onDismiss()` on every tap — so widening it in place would
have broken its other callers. ADR: `2026-10-04-multi-select-sheet.md`.
Coverage: `SelectorTemplateTest` (common) plus
`SavedAgendaSelectorConfiguratorFlowTest` (3 desktop flows).

---

## section-prefill-dynamic-date

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. The hardcoded `LocalDate(2026, 10, 3)` is gone from `AgendaPresets.kt`; `SectionPrefill.relativeDueDate: RelativeBucket?` resolves through `todayAt(deps.clock)` at `AgendaViewModel.kt:190`.

**Found in:** MR-1, `AgendaPresets.kt`. `SectionPrefill.dueDate` is `LocalDate` — a
compile-time constant in an `object`. `Today` section uses `LocalDate(2026, 10, 3)`
which matches the AGENDA_SEED but not the actual date.

**Checks already performed:**
- `handleCreateInSection` correctly maps `LocalDate` → `DueDateOption.Custom`
- Draft is saved to `DraftStore` with correct key
- `TaskCreateViewModel` correctly reads the draft back

**Ruled out:** Runtime `Clock` is not accessible from `object` initializer.

**Status: RESOLVED** (2026-10-04). Neither suggested fix was needed — the third
one was. `SectionPrefill` gained a `relativeDueDate: RelativeBucket?` field
alongside the old `dueDate`, so a preset stores a *rule* rather than a date, and
the date is resolved at the moment the user taps «+»:

- `AgendaDefinition.kt` — `SectionPrefill.relativeDueDate`
- `Clock.kt` — `todayAt(clock, zone)`, the one place that turns an injected
  `Clock` into a `LocalDate`
- `AgendaViewModel.handleCreateInSection` resolves through the injected clock

`AgendaPresets` stays an `object` with no constructor parameter, because the
resolution happens at use, not at initialisation — which is exactly the point
the "Ruled out" note above was circling.

Pinned by `SavedAgendaEditFlowTest` — *"create in section prefills a due date
relative to the injected clock"* — which drives the VM with a `FakeClock` set
away from the host's real date. Teeth verified: restoring the hardcoded
`LocalDate` constant turns it red.

---

## undo-restore-failure-notify

**Tracked as:** [#78](https://github.com/gazon1/singularity-clone-kmp/issues/78) · OpenSpec change `delete-safety-feedback` (proposed)

**Found in:** MR-1 retro-gate, `AgendaViewModel.onUndoDelete`.

When `taskRepo.restore(taskId)` fails, `_pendingDelete` is already set to `null`
and the snackbar has dismissed. The user gets no feedback.

**Checks already performed:** `restore` returns `Result<Unit>`, failure is caught
but only logged.

**Fix:** On restore failure, re-set `_pendingDelete` with an error flag and show
an error snackbar; or emit a `AgendaUiEvent.ShowError` event.

---

## task-detail-scaffold-refactor

**Tracked as:** [#79](https://github.com/gazon1/singularity-clone-kmp/issues/79) · OpenSpec change `delete-safety-feedback` (proposed)

**Found in:** MR-1, attempting to add `Scaffold` + `SnackbarHost` to `TaskDetailViewScreen`.
Private composables (`LoadingState`, `ErrorState`, etc.) are defined at file level and
become inaccessible inside `Scaffold.content` lambda.

**Checks already performed:** `Box` structure works. Snackbars via `Notification.None`
pattern (LaunchedEffect) work correctly.

**Fix:** Extract private composables into a separate internal composable function
`private fun TaskDetailLoadedScaffold(...)` that takes `snackbarHostState` as parameter,
or move them to a companion object. Alternative: use a `SnackbarHostState` at the
parent nav-graph level and pass it down.

---

## countdown-snackbar

**Tracked as:** [#80](https://github.com/gazon1/singularity-clone-kmp/issues/80) · OpenSpec change `delete-safety-feedback` (proposed)

**Found in:** MR-1 retro-gate. `LaunchedEffect(pendingDelete)` only re-triggers on
value changes, not on a timer. The snackbar shows no visual countdown.

**Ruled out:** Standard Material3 `SnackbarHost` does not support countdown. Custom
`Snackbar` with `ProgressIndicator` is non-trivial.

**Fix:** Replace `SnackbarHost` with a custom composable that shows a `LinearProgressIndicator`
inside the snackbar, animated from 100% to 0% over 5 seconds using `animateFloatAsState`.

---

## bulk-import-port

**Tracked as:** [#82](https://github.com/gazon1/singularity-clone-kmp/issues/82) · OpenSpec change `bulk-import-port` (proposed)

**Found in:** MR-1, `BackupImporter` class KDoc and architecture review.

`BackupImporter` writes directly to DAOs to bypass `assertCanWrite` guards, targeting
`options.targetUserId` without going through repositories. This is documented
technical debt.

**Fix:** Create a `BulkImportPort` interface that takes an explicit `targetUserId: UserId`
and routes writes through repositories. Replace DAO calls in `BackupImporter` with
`BulkImportPort.import(payload, targetUserId)`. Track in `docs/decisions/2026-09-27-write-layer-soundness.md`.

---

## vm-without-test

**Tracked as:** [#83](https://github.com/gazon1/singularity-clone-kmp/issues/83)

**Found in:** MR-6, while writing `ViewModelTestCoverageTest` (the Phase 6
"every VM has a test" gate). Pre-existing — none of these were introduced by the
agenda work.

Ten ViewModels ship with no test of their own (was written as eleven; see the
correction below). The rule enforces that this
stops growing; this entry is the debt itself.

| ViewModel | Why it is hard to test today |
|-----------|------------------------------|
| `AccountSettingsViewModel` | Thin wrapper over settings read/write; needs a SettingsRepository fake that does not exist yet |
| `AiUsageViewModel` | Reads LLM usage records straight off the DAO; no fake repository for the usage table |
| `AppVersionGateViewModel` | ~~No test drives it~~ — **closed 2026-10-04**, see below |
| `ArchiveViewModel` | Archive/trash reads go through the task repository; the VM's own state machine is untested even though the queries are |
| `AttachmentsViewModel` | File IO behind `FileSystem`; needs a fake filesystem with checksum support |
| `AuthViewModel` | OAuth session transitions; `core-auth-oauth-is-entirely-unwired` (above) means the flow is not reachable, so there is nothing meaningful to assert yet |
| `CalendarSyncViewModel` | Owns a long-lived debounced collector; the virtual-time setup is the hard part |
| `ProfileSwitcherViewModel` | Reads the profile list; needs a `FakeProfileRepository` wired through the same scope discipline as `ProfileAwareCurrentUser` |
| `SearchViewModel` | `activeFilter = null` is a documented signal, not an error, so a naive test asserts the wrong contract |
| `TagGroupsViewModel` | Cascade deletes are the interesting path and they are covered at the repository level (`TagGroupDeleteCascadeTest`), not at the VM level |
| `TagsViewModel` | **A false positive, corrected 2026-10-04** — `TagRenameTest` *does* drive the VM through `onIntent(TagsIntent.Rename…)` and asserts both the stored row and the observed state. Covered; only the *naming* does not match the rule |

**Correction, 2026-10-04.** Two claims above were wrong and are fixed in the
table:

- `TagsViewModel` **is** covered — `TagRenameTest` constructs the VM and drives
  it through the rename intent, with success, boundary and rejection cases. The
  original note ("covers the rename use case, not the VM") was a misread: the
  test asserts `vm.state.value` as well as the stored row. It is on the
  allowlist only because no test class is *named* `TagsViewModelTest`. That is
  the rule being strict, not a coverage hole — a deliberate trade, since a
  looser rule ("any test mentioning the VM") would pass a file that constructs
  it in a fixture and asserts nothing about it.
- `AppVersionGateViewModel` had **no** test at all; the only mention in the tree
  was `FakeRemoteConfigPort`, a *fake* in the desktop helpers. Both branches of
  its version comparison had never run. Now closed:
  `AppVersionGateViewModelTest` (7 cases — below/at/above minimum, code-not-name
  comparison, CheckAgain re-read, and the failed-refresh fallback that otherwise
  strands the user on a permanent spinner).

So the real list is **ten**, not eleven, and one of the two "easy ones" turned
out to be the genuinely dangerous one: it is the only VM whose failure mode
locks every user out of the app.

**Do this first:** `TagGroupsViewModel` and `SearchViewModel` — both have their
hard repositories already tested, so the remaining work is the VM's own state
machine rather than new infrastructure.

**Rule:** the allowlist lives in `KNOWN_UNCOVERED` in
`shared/src/jvmTest/kotlin/com/singularity/todo/arch/ViewModelTestCoverageTest.kt`.
Removing an entry there without adding a test breaks the build; adding a class
that no longer exists also breaks the build, so the two lists cannot drift
silently.

---

## dialog-testtags-do-not-reach-uiautomator

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. The expect/actual helper (`TestTagResourceId.kt` + `.android.kt` + `.jvm.kt`) is applied in seven composables, `UiAutomationSelectorTest.kt:87` enforces the rule, and `profile/02-isolation.yaml` now selects on `dialog_confirm`. ADR `2026-10-04-testtag-visibility-helper` is the record.

**Found in:** MR-6, the Phase 6 Maestro gate. Journey 03 could not find
`id: dialog_confirm`.

`ConfirmActionDialog` tags its confirm button with `TestTags.Dialog.CONFIRM`
(`Modifier.testTag`), and the tag is declared in `TestTags.kt` and asserted by
the desktop Compose tests — where it works, because those read the semantics
tree directly.

On Android it does not work, and cannot: a Compose `AlertDialog` is a separate
window. The `testTagsAsResourceId` semantics property is applied to the main
window's root and is not inherited into it, so no `testTag` inside a dialog
becomes a resource-id. Confirmed against a captured hierarchy — the dialog's
"Delete" and "Cancel" render correctly and carry no resource-id at all.

**Checks already performed:** pulled the failure artifact's
`screen-hierarchy` JSON; every node in the dialog subtree has
`resource-id=""`. The same pattern applies to `ModalBottomSheet` (see the
MR-5 note about the profile picker selecting by label).

**Fix (journey):** superseded — journeys 03 and 07 now select by `id:`
again, because the structural fix below made the tag work.

**Fix (structural): DONE** (2026-10-04). `Modifier.mapTestTagsAsResourceIds()`
is an expect/actual helper (`core/ui/TestTagResourceId.kt` + `.android.kt` +
`.jvm.kt`; a no-op on JVM) applied **inside** each window-owning surface, so
every tagged node in it reaches UIAutomator. Applied to `ConfirmActionDialog`,
`ListPickerSheet`, `MultiSelectSheet`, `MenuBottomSheet` (MR-6) and, on
2026-10-04, to `CreateProfileDialog` in `ProfileSwitcherScreen` — its confirm
button *and* its name field, which the profile flow also selects by id.

The "apply it at each call site" alternative was rejected deliberately: a
`modifier` parameter is discipline, and discipline is what fails silently at
3am. ADR: `2026-10-04-testtag-visibility-helper.md`.

**The latent case is closed too.** `Maestro/flows/profile/02-isolation.yaml`
tapped `id: dialog_confirm` and could not find it. The flow carried a *second*
defect on top of that one, which is the more interesting find: it waited for
`id: saved_agenda_name_input` after tapping the profile-create button, but
`CreateProfileDialog` tags its field `profile_create_name_input`. Both the id
and the tag existed in `TestTags.kt`, so every existing check passed while the
flow could never have worked. Fixed by correcting the selector and exposing the
dialog.

**Why no gate caught it:** `MaestroFlowTagsTest` resolves each `id:` against
the `TestTags.kt` registry, which proves the tag *exists* — not that the right
element on the right screen carries it. A wrong-but-valid id is invisible to a
registry check; only running the flow finds it. The flow was outside the
`agenda` tag, and the new `maestro-smoke` CI job runs the `smoke` set, which
this flow is part of. See `maestro-ci-job-unproven`.

**Note for the next sweep:** `TaskEditorDiscardDialog` was a hand-rolled
duplicate of `DiscardChangesDialog` with zero call sites, untagged for
automation and invisible to `find-unwired-surfaces.py` (which skips
`/components/`). It was deleted rather than fixed. `TaskEditorSheetHost` — the
sheet host that *is* used by eight features — carries no `testTag` of its own
today, so it needs no exposure yet; that will change the moment a sheet puts a
tagged control inside it.

---

## maestro-gate-can-test-a-stale-apk

**Tracked as:** [#84](https://github.com/gazon1/singularity-clone-kmp/issues/84)

**Found in:** MR-6, chasing journey 07's empty profile picker.

The emulator died mid-run; `run-maestro.sh` relaunched it from an AVD snapshot
and, with `SKIP_INSTALL=1`, did not reinstall. The snapshot held an older build.
Every flow that "passed" in that run passed against a binary that predated the
branch's own hotfixes — including the fix for the very bug journey 07 was
reporting.

Verified by dex rather than by inference: the installed APK's `SingularityApp`
class had zero references to `ProfileBootstrapper`; the freshly built one has it.

**Fix (process):** after any device recovery, re-install before trusting a
result. Stated in `docs/plans/2026-10-04-mr6-retro-gate.md` §6.

**Fix (harness, not done):** have `run-maestro.sh` record the installed APK's
size and mtime at the start, compare after a recovery, and fail loudly if they
differ — or simply drop `SKIP_INSTALL=1` on the recovery path. The flag exists
to save time, and it costs correctness exactly when the run is already going
wrong.

---

## kover-full-jvmtest-run-unmeasured

**Tracked as:** [#85](https://github.com/gazon1/singularity-clone-kmp/issues/85)

**Found in:** MR-6, while building the agenda coverage ratchet.

Instrumentation for `:shared:jvmTest` is opt-in behind `-Pkover.jvmTest=true`,
and only a *filtered* run has ever been exercised — a filtered agenda suite,
~90 s, no OOM. The full suite under instrumentation has not been run since the
OOM that motivated disabling it, and that OOM was itself misattributed
(ledger #11 above: it reproduces with Kover off and in isolation).

**Status: MEASURED — the premise was wrong, the entry stayed open anyway**
(2026-10-04). The measurement this entry asks for was run: a full instrumented
`:shared:jvmTest` (every test, no filter, `-Pkover.jvmTest=true`) completed in
**9m27s** at **PEAK_RSS 125MB**, no OOM. Peak RSS is the number that matters
here, and it is nowhere near a memory ceiling — the `gradlew` wrapper process is
what the measurement covers, and the forked jvmTest JVM is a separate process.

So the OOM that motivated disabling instrumentation reproduces with Kover *off*
and in isolation (ledger #11 in `2026-09-27-write-layer-soundness.md`), which
means the flag was a workaround for a workaround. It stays opt-in anyway, for a
reason that has nothing to do with safety: `check.sh` runs on every change, and
instrumenting every test would add ~6 minutes to each of those runs.
`config/coverage-ratchet.json` documents the measured numbers inline so nobody
re-derives them.

**Still open:** merging the `desktopApp` report into the Kover report, which is
what would let the three Compose subtrees come out of the `excluded_subtrees`
list. Their 71.51% → 33.18% cliff is currently explained away in a config note
rather than measured, and a note is a promise, not a proof.

---

## just-name-value-args-are-not-interpreted

**Tracked as:** [#86](https://github.com/gazon1/singularity-clone-kmp/issues/86)

**Found in:** MR-6 follow-up, while adding the Maestro gate recipe.

`just <recipe> name=value` does **not** assign `value` to `name` in this
environment — the whole token arrives as the value. Reproduced on just 1.57.0
with a two-line recipe: `just p tags=agenda` echoes `tags=[tags=agenda]`, while
`just p agenda` echoes `tags=[agenda]`.

**Impact:** any recipe invoked with named arguments silently receives a
malformed value. It did not error — it produced a confusing downstream failure
("No flow files carry tag(s): tags=agenda"), which is the expensive kind.

**Do this first:** use positional arguments (`just gm agenda`), and check the
recipe's `[doc()]` text shows positional usage. If named arguments are wanted
later, verify with a probe recipe first rather than assuming.

---

## cross-user-write-rule-measured-and-rejected

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `feature/agenda/data/CrossUserWriteRegistry.kt` ships the single sanctioned bypass, and `CrossUserWriteRegistryTest` pins that each entry exists, that the count has not grown, and that each documents itself. The rejected PSI rule stays as ledger #18 in `2026-09-27-write-layer-soundness` — deferred, not lost.

**Found in:** 2026-10-04, while trying to mechanise the `assertCanWrite` bypass
that `SavedAgendaViewsRepositoryImpl.duplicateForProfile` documents in its KDoc.

The rule that looks right — "a repository method that takes a `userId` and writes
must call `assertCanWrite`" — was written and measured. It matches **eight**
methods in the data layer, and **seven are legitimate**:

```
ReminderRepositoryImpl::delete(id, userId)
ProjectRemindersRepositoryImpl::delete(id, userId)
TimeTrackingRepositoryImpl::startEntry / createManualEntry / updateNote
ProposalRepositoryImpl::refreshStatus / retract
```

These are user-*scoped* writes: the userId goes into a DAO query that already
reads `WHERE user_id = :userId`. Passing a userId to a scoped DAO is not a
cross-user write. Only `duplicateForProfile` writes a row belonging to somebody
*else*, because that is the operation's purpose.

Nothing syntactic separates them — both take a `userId` and both call `upsert`.
Telling them apart requires knowing what the DAO query does with the value,
which is the PSI-level rule `2026-09-27-write-layer-soundness.md` ledger #18
already deferred as disproportionate. Allowing the seven would make the list
meaningless: it would grow with every new scoped-DAO method and could never fail
on a real violation.

**What was shipped instead:** `CrossUserWriteRegistry` — a named list of the one
sanctioned bypass, with `CrossUserWriteRegistryTest` pinning that every entry
still exists, that the count has not grown, and that each one documents the
bypass in its own KDoc. It does not *catch* a new cross-user write. It makes the
existing hole greppable, and it is honest about being a registry rather than a
gate.

**Do this first:** if the project ever wants the real rule, it belongs in
`detekt-rules/` next to `PassThroughUseCaseRule`, and it needs to resolve the DAO
query, not the call site.

---

## maestro-ci-job-unproven

**Tracked as:** [#87](https://github.com/gazon1/singularity-clone-kmp/issues/87)

**Found in:** 2026-10-04, while adding the `maestro-smoke` CI job.

The job is the first thing in this repository that ever *executes* a Maestro
flow in CI. It has never run — GitHub Actions emulators are a different
environment from the host's AVD, including for the gfxstream crash that
`run-maestro.sh` works around locally.

Specifically unproven:
- `reactivecircus/android-emulator-runner` + `Maestro/scripts/wait-for-boot.sh`
  boots and the APK installs;
- the `smoke` set passes in that environment at all — some flows may depend on
  host behaviour;
- wall-clock cost, which is why `agenda` and `regression` were left out.

**Do this first:** run the job once on a branch and read the log before trusting
it. If the smoke set turns out to be slow or flaky, the `timeout-minutes: 45` and
`MAESTRO_MAX_RETRIES=1` are the first knobs to turn.

**Partly answered on the host, 2026-10-04.** The `smoke` set was run locally for
the first time, so "does it pass at all" is no longer open — it did not, and the
run found six real defects (see `maestro-flows-share-one-app-instance-so-failures-cascade`,
`a-flow-can-be-unrunnable-and-every-check-still-pass`,
`overflow-menu-rows-were-tagged-with-a-nobody-reads-scheme`,
`a-testtag-built-from-a-localised-label-changes-with-device-locale`,
`flows-select-by-localised-text-and-the-device-is-russian`). Four are fixed and
verified on the device.

**What this changes for the CI job:** the smoke set is *not* ready to be a
blocking gate yet, and the reason is now known rather than unknown. `smoke` runs
on a **Russian-locale** emulator here, which surfaced a class of defect no
English-locale run would have found; CI's emulator will have its own locale, and
until every flow selects by id rather than by translated text, "passes in CI" and
"passes on the host" will disagree in ways neither run can explain.

So the order matters: fix the remaining locale-dependent selectors first, then
push the branch and read the log. Doing it the other way round spends the first
CI run as a debugging session.

Still unproven, and unchanged by any of the above:
- `reactivecircus/android-emulator-runner` + `Maestro/scripts/wait-for-boot.sh`
  actually booting and installing;
- wall-clock cost of the `smoke` set;
- whether `agenda` and `regression` fit in the same budget.

---

## an-open-backlog-entry-does-not-mean-the-work-is-still-open

**Tracked as:** [#88](https://github.com/gazon1/singularity-clone-kmp/issues/88)

**Found in:** 2026-10-04, the first iteration of the "what next" sweep — while
asking which recorded findings were still true, instead of which were still
*written down*.

Nine entries carried `Status: OPEN` and described work that had shipped. Not one
had been closed: `is-saving-clobber` (the guard existed, nothing pinned it),
`section-prefill-dynamic-date` (solved by a `relativeDueDate` field, a different
fix from the two either-or options the entry offered), `agenda-section-add-button-noop`,
`agenda-editor-no-selector-parameter-configuration`, `agenda-views-not-in-backup`
(agenda_views only — eight tables really are still missing), `notification-text-null-invisible`,
`docs-rot-agenda-selector-count`, `kover-full-jvmtest-run-unmeasured` (measured,
entry left open), and `vm-without-unit-tests`, which was a **second entry for
the same finding** that `vm-without-test` already tracked.

Two of them were actively misleading rather than merely stale. The kover entry's
"do this first" was a measurement nobody had run, and the flag it defended was a
workaround for a workaround. The prefill entry offered two fixes, both wrong, and
would have led the next person into a `Clock`-injection refactor of an `object`
that never needed one.

**Why this is the expensive failure mode:** an open entry reads as a live
commitment, so the cost is not the stale text. It is that a backlog nobody
trusts stops being read at all — and the findings were real when they were
written. Nine of them were.

**What catches it, and what does not.** Nothing in the toolchain did, because
every gate here answers a different question: does this compile, does the
feature have a test, is the tag in the registry. None of them asks *is this
finding still true*. A status line is only as current as the last person who
remembered to look.

**Do this first, next time:** sweep the backlog as part of the retro-gate, not
as a separate task later — the retro is the only moment when the session that
made the change still knows what it changed. A status line written during the
change costs nothing; the same line reconstructed a week later is archaeology.

---

## two-ci-gates-are-red-and-nothing-local-looks-at-them

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `check-doc-sizes.py` and `check-doc-dead-refs.py` both exit 0, both run in `check.sh:128-135` with a hard `exit 1` and no `|| true`, and both appear in `ci.yml` without `continue-on-error`. The 38 findings are covered by the dead-refs baseline.

**Found in:** 2026-10-04, the "what next" sweep — while adding four lines to
`AGENTS.md` and running `just docs-audit` to see whether they broke anything.

Two blocking CI steps in `.github/workflows/ci.yml` are red, and **both were
red before this branch touched anything**:

1. `Check doc sizes` — `AGENTS.md` was 253 lines against a 250 limit at HEAD;
   `DIGEST.md` was 1255 against 1250. Fixed here (ADR
   `2026-10-04-doc-size-budget-was-already-red`).
2. `Check dead doc references` — `check-doc-dead-refs.py` exits 1. Verified by
   stashing this branch's work and re-running: identical findings at HEAD, so
   none of them are ours. The live ones are `DEAD` references to files that do
   not exist at all — `GLOSSARY.md` in five skills, `NOTES.md`, `package.json`,
   `CLAUDE.md`, `CODING_STANDARDS.md` in `retro` — plus two `DRIFT` entries
   (`AGENTS.md`, `PROGRESS.md` → `openspec/config.yaml`).

**Why nobody noticed:** neither gate runs in `check.sh`, which is what every
local loop uses. They fire only on push, and the push that broke them was a
while back. The same shape as `maestro-gate-can-test-a-stale-apk`: a gate that
only exists in one place is a gate whose failure nobody sees.

**Do this first:** the dead-refs list is the cheap one — six skill files point
at documents that were never created (`GLOSSARY.md` especially, referenced five
times). Either write the files or drop the references; the skill docs are
agent-facing, so a reference to a file that is not there is an agent going
looking for something that does not exist. The two `openspec/config.yaml`
DRIFT entries need the real path, which the script can report with `--strict`.

**Then:** add both to `just gate` (`2026-10-04-one-gate-recipe.md`). They are
fast, they are already CI, and this episode is the argument for putting every
gate somewhere a local run will meet it.

---

## a-green-gate-only-proves-the-gates-you-ran

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `.just/tests/gate.just:124-151` names all four steps inline under `set -euo pipefail` and prints a banner when `SKIP_MAESTRO=1` means the flows were not gated. `justfile:61` exposes it as `just gate`. The smoke-set half is #87.

**Found in:** 2026-10-04, the same sweep. Three separate gates were red, in
three different ways, and each had been red for a different reason that made it
invisible:

- `check-doc-sizes` — over budget by 3 and 5 lines, from growth that no step
  checks at the moment it happens.
- `check-doc-dead-refs` — 12 dead references, from skills that point at
  documents nobody wrote.
- The Maestro `smoke` set — contains a flow that can never have passed (wrong
  but valid id), and was run by no local command and by a CI job that has never
  executed.

None of these is a missing check. Every one of the checks exists, is wired, and
would have failed. The common failure is **coverage of the gates themselves**:
each one only ever ran on push, or only for one tag, or only in one directory.

**The generalisable part:** a green result from a gate is a claim about the set
of gates that ran, and nothing in a normal workflow makes that set explicit. The
fix is always the same shape — name the set, in one place, and run all of it.
`just gate` is that place for this repo.

**Cost note, because this is a trap worth seeing:** the instinct on finding a
red gate is to fix *only* the red one and move on, which is what happened for
`check-doc-sizes` — the digest generator and `AGENTS.md` were trimmed, and the
dead-refs gate next to it was left red on the grounds that it was "not this
task". Fixing one gate while leaving its neighbour red is how a repo reaches a
state where a single `CI is green` claim is worth nothing.

---

## maestro-flows-share-one-app-instance-so-failures-cascade

**Tracked as:** #105

**Found in:** 2026-10-04, the first ever local run of the `smoke` set — the
set the new `maestro-smoke` CI job runs, which had never executed anywhere.

**Result: 9 passed, 10 failed.** All ten failures were this one bug.

`run-maestro.sh` ran every flow against **one long-lived app process**. Whatever
a flow left on screen — a modal bottom sheet, a snackbar, a half-typed editor —
was still there when the next flow started. The captured hierarchy for
`tasks-date-buckets` shows why it looked like that flow's own bug: the tree
underneath was a task overflow sheet reading "Архивировать / Удалить", put
there by `tasks-archive-via-menu`. `nav_tab_today` was genuinely not visible,
because a sheet was covering it.

The corroborating detail: the nine flows that passed are exactly the nine that
run `helpers/launch-clean.yaml` (which does `launchApp: clearState: true`); the
ten that failed are exactly the ten that do not. `seed-task.yaml` runs it
internally, which is why the flows that seed through it mostly recovered.

**Why this survived so long:** the `agenda` tag — the only tag any gate ran —
is 8 flows that all use `launch-clean`. The broken ones are spread across
`smoke`, `tasks` and `system`, none of which had ever been run as a set.

**Fix (harness):** `run-maestro.sh` now force-stops the app before each flow and
relaunches it **without** clearing state. Both halves are load-bearing:

- `force-stop` removes the residue. A data *clear* would be wrong — it would
  destroy the seeded profile or task the flow under test depends on.
- The relaunch is not optional. `DebugSeedActivity` resolves its Koin graph
  from the running process (its own KDoc says "Requires the app to already be
  running"), so a deep link into a stopped app seeds nothing and the flow fails
  at the next assertion.

**Two unrelated defects found in the same run**, both invisible to every
existing check:

1. `Maestro/flows/tasks/09-rename-empty.yaml` used `- longPress:`. The Maestro
   command is `longPressOn` — `longPress` is not a command at all, so the flow
   failed at *parse* time with "Invalid Command". `05-archive-via-menu.yaml`
   uses the correct spelling three lines apart, which is why nobody noticed.
2. `DebugSeedActivity` dispatches on a `when` whose first matching key wins, so
   `todo-debug://seed?task=X&profile=Y` creates the task in the *current*
   profile and silently ignores `profile=Y`. `profile/02-isolation.yaml` used
   exactly that URL, so its task landed in Personal and the flow's isolation
   assertion failed — while the test it was written to guard could not have
   passed either way. Fixed by switching profiles explicitly, then seeding.

**Do this first:** the harness fix covers every future run, but 22 of 58 flows
still do not open with `launch-clean`, so each depends on the harness for
isolation rather than declaring it. That is fine and is the cheaper default —
but any flow that asserts "this does not exist" needs a clean start of its own,
because a previous flow's data will otherwise satisfy or break the assertion by
accident.

---

## a-flow-can-be-unrunnable-and-every-check-still-pass

**Tracked as:** #87

**Found in:** 2026-10-04, the same first `smoke` run. `profile/02-isolation.yaml`
was the regression guard for profile isolation — the bug where all profiles
shared one Room namespace. It could not have passed:

- it waited for `id: saved_agenda_name_input` where the profile dialog tags its
  field `profile_create_name_input`;
- it tapped `id: dialog_confirm` inside an `AlertDialog` that never exposed its
  testTags to UIAutomator;
- it seeded with a URL whose `profile=` parameter was ignored.

**All three defects passed every gate in the repository.** `MaestroFlowTagsTest`
checks that ids are *declared in* `TestTags.kt` — both wrong and right ids are
declared, so it saw nothing. `find-unwired-surfaces.py` does not parse flows. The
desktop Compose tests assert on the semantics tree, where the tag works. And no
gate ran the `smoke` tag at all, so nobody had watched it fail.

**The generalisable point, and the reason it is worth a backlog entry:** a flow
is a test that ships with no compiler. `longPress:` did not fail to compile — it
failed at Maestro's parser, on a run that had never happened. Nothing in CI
turns "the flow file exists" into "the flow was executed", so a file can sit in
the repository for months carrying a typo, and its presence reads as coverage.

This is the strongest argument yet for `maestro-ci-job-unproven` being the first
thing to close: a Maestro job that actually runs is the only check in the
repository that would have caught any of the three defects above.

---

## overflow-menu-rows-were-tagged-with-a-nobody-reads-scheme

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04; **#65 closed with it.** `TaskEditorContent.kt:287-306` applies `Modifier.mapTestTagsAsResourceIds()` and each `DropdownMenuItem` gets `item.testTag`, with items carrying `EditorOverflow.RESTORE/ARCHIVE/DELETE` from `TaskDetailContent.kt:229`. Residual kept deliberately: `PIN`/`UNPIN` remain in the allowlist, and `taskAction` still coexists for the context menu — that is the #109 work, not this defect.

**Found in:** 2026-10-04, the second `smoke` run, chasing why
`archive/01-restore.yaml` failed on `id: overflow_archive` *inside* a single flow
— so not cascade residue, and therefore a real defect.

**Symptom:** the flow taps `task_editor_more_menu` (that tag is real and applied,
`TaskDetailTopBar.kt:56`), the editor's three-dot menu opens correctly, and then
`overflow_archive` is not there. The captured hierarchy shows the menu rendering
"Архивировать / Удалить" — a `DropdownMenu` with **no resource-id on any row**.

**Two causes, either of which alone was fatal:**

1. **The rows were never tagged at all.** `TaskEditorContent.kt:286` built its
   `DropdownMenuItem`s with only `text` and `onClick`. No `Modifier.testTag`.
2. **The `DropdownMenu` had no exposure.** Same structural defect as the dialogs
   in `dialog-testtags-do-not-reach-uiautomator` — a `DropdownMenu` is its own
   window and never inherits the app-root `testTagsAsResourceId`.

**The naming trap, which is the real lesson.** The flows ask for
`overflow_archive` because `TestTags.EditorOverflow.ARCHIVE` is *declared* with
exactly that value. But that constant has **no call site**: the overflow rows
are tagged through a different scheme entirely, `TestTags.taskAction(label)`,
producing `task_action_archive`. The two schemes differ by one prefix, and the
unused one is the one a test author finds first by reading `TestTags.kt`.

`TestTagsWiringTest` knew. Its `knownUnapplied` allowlist already lists all five
`EditorOverflow.*` constants with the reason "the overflow menu renders rows
through `TestTags.taskAction(action)`, so this constant has no call site". So
the registry, the wiring test and the flows disagreed, and the flows were the
only ones nobody ran.

**Fix:** rows now carry `TestTags.taskAction(item.label)`, and both the
`DropdownMenu` and its items get `mapTestTagsAsResourceIds()`. Flows
`archive/01-restore`, `tasks/04-delete` and `tasks/06-delete-undo` were
repointed from `overflow_*` to `task_action_*`.

**Left standing, deliberately:** the five dead `EditorOverflow.*` constants stay
in the registry, because deleting them would make `MaestroFlowTagsTest` fail —
correctly, but for the wrong reason. A flow written tomorrow would hit the same
trap. The honest fix is to delete the constants *and* the flows' dependence on
them in one change, which is what the allowlist entry has been asking for since
`2026-09-30-draft-save-failure-and-testtag-honesty`.

**Do this first:** decide whether the editor overflow should be
`EditorOverflow.ARCHIVE` or `taskAction("Archive")` — pick one, delete the
other, and let the registry shrink. The cost of keeping both is precisely this
class of bug, and it has now cost two debugging sessions.

`TaskContextMenuSheet` was fixed in the same pass: it *is* tagged
(`TASK_CONTEXT_MENU_SHEET`) and its rows do use `taskAction`, but the
`ModalBottomSheet` had no exposure, so the tag was invisible on Android for the
same structural reason.

---

## never-run-gradle-while-a-maestro-gate-is-running

**Tracked as:** [#89](https://github.com/gazon1/singularity-clone-kmp/issues/89)

**Found in:** 2026-10-04, twice, in one session — the second time it destroyed
the run it was supposed to be checking.

**Symptom:** mid-suite, every flow started failing with
`Package com.singularity.todo is not installed`. A concurrent
`./gradlew :androidApp:installDebug` (or any task touching the same APK) had
uninstalled the app as part of its own install cycle, and the 19-flow suite kept
running against a device that no longer had the binary. 16 failures, none of
them a regression.

**Already known, in this session's own notes:** two Gradle runs in one project
must not overlap. That was learned from `NoSuchFileException` on
`in-progress-results-generic.bin`. It is equally true for the device, and the
failure mode there is much worse: a Gradle build does not fail loudly, it
quietly removes the app that a 20-minute gate is in the middle of exercising.

**Why it is not caught:** `run-maestro.sh` has device-death detection for a
*disconnected* emulator. An uninstalled package is a perfectly healthy device.
The first flow to hit it reports "element not found", which is indistinguishable
from a UI regression, so the retry logic re-runs the whole thing and fails again
for the same reason.

**Do this first:** treat a Maestro gate as owning the device. While one runs,
no Gradle — not even `compileKotlinJvm`, which looks read-only and is not (it
shares the daemon and the APK outputs). If something must be checked
concurrently, run it on a second checkout or accept the serial wait; the whole
point of a gate is that its result means something.

---

## a-testtag-built-from-a-localised-label-changes-with-device-locale

**Tracked as:** #109
**OpenSpec change:** `openspec/changes/selector-and-tag-identity/`

**Found in:** 2026-10-04, the third `smoke` run — after the overflow rows were
finally tagged, `tasks/04-delete` still could not find
`id: task_action_delete`.

**Symptom:** the tag was applied, the row was on screen, the assertion still
failed. The captured hierarchy explains it: the emulator runs in **Russian**
(`accessibilityText=Меню` on the overflow button, "Архивировать / Удалить" in
the menu), and the tag was built as `TestTags.taskAction(item.label)`. With
`label = "Удалить"` that produces `task_action_удалить`. The flow asked for
`task_action_delete`.

**Why nothing caught it:** every prior check reasons about the tag *string* —
`TestTagsWiringTest` checks the constant is applied, `MaestroFlowTagsTest` checks
the id is declared, and the desktop Compose tests read the semantics tree, where
the value is whatever it is and nothing compares it to an expectation. The
localised value is perfectly valid; it is simply not the one the flow wants.
Only a run on a device with a non-English locale surfaces it — and this repo's
device defaults to English, so the bug would have shipped.

**Fix:** `TaskEditorMenuItem` gained a stable `action: String` ("archive",
"delete", "restore"), and the tag is built from that. `label` stays localised
and stays for the user. Every construction site passes both.

**The generalisable rule, which is the reason this is a backlog entry rather
than a one-line fix:** *a selector must never be derived from text a user can
translate.* The failure is silent, locale-dependent, and invisible to every
check that only asks whether a tag exists. The same applies to
`TestTags.taskAction` anywhere else it is fed a UI string — `TaskContextMenuSheet`
feeds it hardcoded English labels today, which works by luck of the current
locale, not by design.

**Do this first:** grep for `testTag(` calls built from a `.label`/`.text`/
`.title` field and convert them to a stable id. `find-unwired-surfaces.py` is the
natural home for a check here, alongside the window-owning-surface detector
proposed in `2026-10-04-testtag-visibility-helper.md`.

---

## flows-select-by-localised-text-and-the-device-is-russian

**Tracked as:** [#90](https://github.com/gazon1/singularity-clone-kmp/issues/90)

**Found in:** 2026-10-04, the third `smoke` run, immediately after
`tasks/04-delete` was fixed — and the same root cause as
`a-testtag-built-from-a-localised-label-changes-with-device-locale`, one level
up.

**Symptom:** `tasks/06-delete-undo.yaml` waits for `text: "Undo"` and taps it.
The emulator is Russian, so the snackbar's action button reads "Отменить" and
the flow can never pass. `archive/01-restore.yaml` and `tasks/04-delete.yaml`
were fixed and pass on the same device — the difference is exactly whether the
flow selects by **id** or by **text**.

**Ruled out:** this is not a flow typo. `Notification.Undo` has a perfectly good
stable `actionLabel` field, and the delete-undo feature itself works — a user on
a Russian device gets a working Undo button. Only the *selector* is wrong.

**Why it was never noticed:** the flows were written on an English device, and
every flow that selects by text was therefore correct at the time it was
written. The device locale is not part of any gate's inputs, so nothing
re-checks it.

**Fix, two options, and the second is better:**

1. Change the flow to select the action by whatever text the device shows. This
   makes the flow pass and teaches nothing — it is a flow that only works in one
   locale, which is the bug restated.
2. **Tag the snackbar action button** and select by id. `NotificationHost.kt:59`
   calls `snackbarHostState.showSnackbar(actionLabel = …)`; the rendered button
   belongs to Material3's `SnackbarHost` and cannot be tagged from the call
   site, so this needs a small custom `SnackbarHost` (or wrapping the action in
   one). That is the same shape as the `TaskEditorSheetHost` pattern already in
   the codebase, and it is the only option that makes the selector locale-proof.

**Do this first:** option 2, then sweep every flow for `text:` selectors and
convert the ones naming user-visible chrome. `grep -rn "text:" Maestro/flows/`
is the starting point; the ones worth converting are the labels that appear in
more than one place, since those are the ones a translation will move.

The general rule is now stated twice in this file, in two directions — once for
tags built from localised labels in code, once for flows selecting localised
text. Both are the same defect: **a selector that a translator can move.**

---

## ui-reads-the-system-clock-directly-so-a-fixed-date-cannot-reach-it

**Tracked as:** [#91](https://github.com/gazon1/singularity-clone-kmp/issues/91)

**Found in:** 2026-10-04, while adding a `clock` parameter to the desktop test
harness (`runDesktopAppTest`) — the fix the backlog had asked for since MR-0.

**What was added:** `runDesktopAppTest(clock = …)` binds a `FakeClock` as the
last Koin module, so it wins over `coreModule()`'s `single<Clock> { Clock.System }`.
That part works and is used by `AgendaBadgePolicyFlowTest.overdue_task_shows_overdue_badge`.

**What it cannot reach, which is the actual finding:** most of the UI does not
read the injected `Clock` at all. `todayInSystemZone()` is a top-level function
in `core/platform/Clock.kt` that calls the system clock directly, and it is what
`CalendarContent.kt:49`, `CalendarScreen.kt`, `CalendarPreview.kt` and others
call. `AgendaTabDefinitionFlowTest` and `CalendarFlowTest` use it in the test
body too.

The first attempt at closing this applied the harness clock to
`CalendarFlowTest` and failed with
`Condition (some node with testTag 'calendar_day_2026_09_15' is on screen) still
not satisfied` — the app rendered October (the host's real month) because the
injected clock never reached it. That test was reverted; the harness parameter
stays, because it does work for the VMs that take a `Clock` by injection.

**Why this matters more than the parameter:** the `NoDirectClockSystem` detekt
rule exists to keep production code off the system clock, and `todayInSystemZone`
is the sanctioned escape hatch — which means the escape hatch is exactly where
the untestable UI lives. A `Clock` in the graph is not the same as a `Clock` in
the composition.

**Do this first:** thread an injected `Clock` (or the `LocalDate` derived from
it) into `CalendarContent` / `CalendarScreen` the way `today` is already a
parameter there — it is a `val today: LocalDate = todayInSystemZone()` default,
so the plumbing exists and only the default is wrong. Then `CalendarFlowTest`
can pin a date like every other flow test. Until then, any UI assertion that
depends on "now" is a test that reports the calendar.

---

## six-smoke-flows-still-red-after-the-harness-fix

**Tracked as:** [#92](https://github.com/gazon1/singularity-clone-kmp/issues/92)

**Found in:** 2026-10-04, the second full `smoke` run on the fixed harness.

**Result: 13 passed, 6 failed.** The three runs that day went 9/10 → 8/11 → 13/6,
and the middle dip is the interesting one: it is what the per-flow relaunch cost
before it waited for the activity instead of a duration (see
`2026-10-04-maestro-flow-isolation.md`).

Fixed and now green: `01-restore`, `02-isolation`, `03-cycle-tabs`,
`02-menu-settings`, `menu-sheet`, `04-delete`, `07-cyrillic-title` — seven of the
eleven the first run lost.

**Still red, one line each, with no diagnosis yet:**

| Flow | Known last reason |
|---|---|
| `01-round-trip` | unknown — the `clearState` command is gone and the flow no longer matches nothing |
| `search-finds-task` | `nav_tab_today is visible` after the `todo-debug://seed` deep link |
| `05-archive-via-menu` | unknown |
| `06-delete-undo` | unknown — now selects `snackbar_action`, so the locale bug is out of the picture |
| `08-date-buckets` | unknown — three `openLink` seeds in a row |
| `09-rename-empty` | unknown — `longPressOn` is fixed, the parse error is gone |

**What is honest about this entry:** five of the six are undiagnosed. The
per-flow logs for them were rotated out of `~/.maestro/tests` before the run's
tail was read, so the table above records what is known and no more. Re-running
one flow at a time with `FLOW=… bash scripts/run-maestro.sh` is ~2 minutes each
and yields the answer immediately; doing all six that way is the obvious first
move, and it is not done yet.

**The pattern worth watching:** the flows that still fail are the ones that
reach their state through a `todo-debug://seed` deep link rather than through
`launch-clean`. Four of the six do. The deep link depends on a live, initialised
process, and per-flow isolation forces exactly one more restart in front of it —
so the fix is likely one more wait (after the `openLink`, before the first
assertion) rather than six separate bugs. That is a hypothesis, not a
conclusion, and it is cheap to test: add the wait, re-run, see which of the six
move.
---

## test-doubles-in-commonmain-source

**Found in:** 2026-10-05 spec-governance sweep. `scripts/find-unwired-surfaces.py`
detector 7 reported `MapFileSystem`, `FakeSecureStorage` and `FakeDraftStore` as
symbols with test references and zero production references. The baseline recorded
each as `BacklogRef: none`, which its own header rule defines as a gate failure
("a line without a live backlog reference is a gate failure").

**Tracked as:** #97
**OpenSpec change:** `openspec/changes/unwired-detector-test-double-exemption/`

**Status:** these are not dead code. They are test doubles that live in
`commonMain` production source, so they are reachable from `commonTest` without
depending on a JVM/Android-only source set. The detector cannot distinguish
"unwired production code" from "test infrastructure in the wrong source set",
so it flags them by construction.

**Decision (2026-10-05):** accept them as a known false positive of detector 7 and
give them this entry as a live backlog reference, rather than moving them. Moving
the fakes to a test-only source set would break `commonTest` compilation, which
cannot see `jvmTest` sources.

**Try next:** if detector 7 is ever refined to skip paths under
`test/fakes/` or filenames matching `Fake*`/`InMemory*`, these three lines can be
removed from `scripts/find-unwired-surfaces-baseline.txt` entirely. Until then the
baseline entry is the exemption, and this entry is why it is not `none`.

---

---

---

## detekt-rule-branch-coverage-owed

**Found in:** 2026-10-05 rule-audit. `RuleFiresSmokeTest` gives all 17 custom rules at
least one positive test (two were confirmed no-ops and fixed: see
`2026-10-05-no-direct-dispatchers-rule-was-a-no-op` and
`2026-10-05-positive-tests-for-every-detekt-rule`). That is the minimum, not the job.

**Tracked as:** #98
**OpenSpec change:** `openspec/changes/detekt-rule-coverage-floor/`

**Status:** PARTIALLY PAID (2026-10-05, later the same day). Kover is now on
:detekt-rules (`just tkr`), so this is a number rather than prose: **92.0% line
coverage, 102 tests, 0 failures.** Coverage went 66.2% -> 92.0% when the four
MviViewModel rules — the ones guarding the canonical VM shape, previously 0% and
entirely unverified — got a positive and a negative test each. All four turned out
to work, so no third no-op; the point was that nobody knew until they were measured.

One question is still answered per rule — "can it fire?" Branch coverage remains
uneven, and the gaps are not spread evenly:

- `PassThroughUseCaseRule` has the most untested logic and the least obvious guards:
  `operator`, `private`, block bodies, the `LlmUseCase` exemption, and the `clock` /
  `tool` receiver exemptions. None is exercised. A guard that is wrong here is a
  false-positive machine aimed at legitimate use cases.
- `NoStateInRule`'s `@OptIn(CombineStateInReadThrough::class)` exemption is the one path
  that decides whether the rule is usable on read-through VMs, and it is untested.
- `MviViewModelRulesProvider`'s other four rules (`IntentMethodName`, `VmScopePosition`,
  `VmCloseable`, `ShadowedState`) have no tests at all — the smoke test covers only
  `MviViewModelExt`.
- `NoEmptyOnClickLambdaRule`'s "file name contains preview" exemption is untestable via
  the PSI harness (see the ADR); the repo's real `core/ui/preview/` package is the only
  thing exercising it.

**Try next:** start with `PassThroughUseCaseRule`, because its exemptions are the ones
that decide whether the rule is tolerable in a codebase. Extract each guard to a policy
object in the style of `NoDirectDispatchersPolicy` and cover it directly, which also
removes the PSI-shape coupling that made the original bug possible.

**Try next (cheap alternative):** a differential test. Run each rule over a fixture
containing a known violation and assert the *count*, not just non-emptiness, so a rule
that starts double-reporting fails. Cheaper than full branch coverage and catches the
regression that matters most in practice.

---

---

---

## androidapp-debug-source-set-unlinted

**Found in:** 2026-10-05, while moving `androidApp` off `detekt-minimal.yml`. The module's
`detekt.source` never listed `src/debug`, so `DebugSeedActivity.kt` has never been linted.
It is also the only androidApp source set the module does not scan.

**Tracked as:** #99
**OpenSpec change:** `openspec/changes/androidapp-debug-lint-policy/`

**Status:** OPEN — a decision, not a mechanical fix. Linting it produces 16 findings, and
every one is in `DebugSeedActivity.kt`:
- `NoRunBlocking` (1) and `NoDirectClockSystem` (4) — a one-shot debug seeder blocks a
  background thread and stamps seed timestamps; both are the point of the tool
- `TooGenericExceptionCaught` (1) — a seeding tool that must not crash the app
- `BlankLineBetweenWhenConditions` (5), `ClassSignature` (2), and 3 more formatting
  findings, which are auto-correctable

**Not done deliberately.** Half of these would need a suppression, because the rules are
correct for production and wrong for a debug seeder. Whether debug-only tooling should be
held to production rules — or exempted by source set, or held with a narrower rule set — is
a call for whoever owns the debug tooling, and it generalises to every future
`src/debug` file.

**Try next:** decide the policy first, then wire it. The cheapest policy is to lint it
with a baseline carrying the four intentional suppressions, which keeps the formatting
findings enforced from day one. Do not simply add `src/debug/kotlin` to `source.setFrom`
and baseline the lot: that would accept the 16 without deciding whether debug code should
be governed at all.

**Note:** the `source.setFrom` list also contained `src/androidAndroidTest/kotlin`, a
source set that does not exist — a typo, silently ignored. Removed.

---

---

---

## ci-parallel-split-blocked-by-new-intra-job-coupling

**Found in:** rebase of `fix/doc-governance-and-detekt-audit` onto `main`, 2026-10-05.
Not a pre-existing defect — an interaction between two changes that were each correct
on their own.

**Tracked as:** #100
**OpenSpec change:** `openspec/changes/ci-checks-parallel-split/`

**Status: OPEN.** The split is designed, measured and built, but withdrawn. See
`2026-10-05-ci-checks-run-in-parallel.md`, which is `status: superseded`.

**What happened:** this branch split the 25-step `test-and-check` into six parallel
leaves and measured 24.25m -> 9.2m. `main` had meanwhile added three couplings inside
that job — the `$RUN_STARTED` freshness stamp feeding two count/coverage floors, a
flake comparison that reads this run's `shared/build/test-results/jvmTest` against the
previous run's `junit-results` artifact, and a kover job that must generate its report
from a test run rather than from a cache. The six-leaf shape was valid against the old
job and produces a *wrong* result against the new one: the floors and the flake
analysis would compare across a boundary they were never written to cross.

**Checks already performed:** confirmed all three couplings exist in
`origin/main:.github/workflows/ci.yml` by reading the step bodies, not by inference.
Confirmed the pre-rebase branch's own split was green (run `37212487694`, 10 jobs,
1585 tests, 4 artifacts) — so the failure is not "parallelism is broken", it is "that
specific shape no longer fits".

**Try next, in this order** — each is a real design, not a variation:
1. Publish `RUN_STARTED` as a job output and pass it to the floor-checking leaves, so
   the floors still compare against the run that produced the results.
2. Move `Check executed test counts` and `Check coverage floors` *into* the leaf that
   produced the results, and keep only the assertion in the aggregator.
3. Replace the two-run flake comparison with a stored-baseline one, which has no
   cross-job edge to break.

**Not to do:** re-apply the six-leaf split as written and declare the reduced coverage
an acceptable cost. A faster pipeline that checks less is the exact failure this
backlog exists to prevent, and it would be invisible — a green run is a green run.

**Lever already identified, independent of the split:** `assembleDebug` is ~10.3m of
the original 24.25m and is the tail of the critical path. A cold no-cache profile puts
`:shared:compileAndroidMain` at 28.8s, `:shared:kspAndroidMain` at 27.2s, and
`DexingNoClasspathTransform` on `:shared` plus `:androidApp:mergeExtDexDebug` at 38.2s
together — about a quarter of the build. Trimming the step to `compileDebugKotlin`
would buy most of that back and is **an owner's call, not a cleanup**: the step would
then prove the code compiles, not that the APK packages, and the workflow that installs
and runs the APK is scheduled rather than per-PR.

---

## the-backlog-is-unbudgeted-and-feeds-a-budgeted-index

**Found in:** 2026-10-05, while closing out the audit of the 28 untracked entries and
checking what had moved underneath the numbers. Not a regression — a structural property
of how findings are recorded.

**Tracked as:** #113

**Status: OPEN — a judgement call, not a chore.** Two files, one problem seen from two
ends:

| File | Size | Budget | Headroom |
|---|---|---|---|
| `docs/decisions/DIGEST.md` | 1196 lines / 436 entries | 1250, blocking | 54 lines |
| `docs/decisions/deferred-backlog.md` | 1934 lines / 59 entries | **none** | unbounded |

`check-doc-sizes.py` budgets `AGENTS.md`, `DIGEST.md`, `ARCHITECTURE.md`, `PROGRESS.md`
and `SKILL.md`. It does not budget this file — the one every finding lands in. The digest
indexes every ADR permanently and is at 96% of a hard ceiling (#52 tracks its pressure),
so an unbounded queue feeds a bounded index and the index fails first. The pressure
arrives as an unrelated-looking error on a commit that touched a doc gate rather than the
index.

**Try next, in this order.** Deciding is the work; implementing is mechanical afterwards.

1. **Split closed from open.** 19 entries now carry a dated closure and a reference, so
   they are a record rather than a queue. Moving them to a `deferred-backlog-archive.md`
   halves this file and leaves the live queue legible. An archive without a budget is the
   honest form: history is allowed to grow, a work queue is not.
2. **Budget the remainder** once it is only open entries, at a number low enough that
   hitting it means "triage now" rather than "the build is broken".
3. **Index rather than accumulate**, if the entries are staying whole — but note that
   `MODULE-INDEX.md` already does this for specs, and a second index for findings would
   need its own gate to stay honest.

**Not to do:** raise the digest ceiling to make room. The ceiling is the only thing that
made the pressure visible; a queue that has to be diluted before it can be indexed is not
being managed.


---

## recurrence-parser-is-unwired

**Found in:** 2026-10-04 verifiability audit, via `find-unwired-surfaces.py`
detector 7 (dead-symbol). The baseline line carried a backlog reference to this
entry that did not exist, so the reference was unresolvable.

**Tracked as:** #63

**Symptom:** `RecurrenceParser.kt` is 309 lines with 27 `@see` KDoc references
and zero production call sites. It is the inverse of an unwired forward
operation — the parsing direction is implemented, the *applying* direction
(`TaskRepository` → recurrence expansion) is not, so nothing ever asks the
parser for a recurrence.

**Already ruled out:** not reachable by reflection, DI or route — plain Kotlin,
no Koin binding, no `interface` implementor.

**Try next:** decide whether recurrence is a product feature. If yes, the missing
half is the apply path (an infinite `Task` generator consumed by the agenda or
calendar), and the parser is a reasonable starting point. If no, the 309 lines
are a candidate for deletion. As with `core-auth-oauth-is-entirely-unwired`, a
dead-code sweep should not make the product decision either way.

---

## note-editor-unwired-domain-classes

**Found in:** 2026-10-04 verifiability audit, same detector as above. Three
baseline lines shared a reference to this entry; none existed.

**Tracked as:** #64

**Symptom:** three classes in `feature/notes/domain/` have test references but no
production call sites:
- `NoteEditorState` (52 lines, 18 test refs) — the real editor is
  `NoteEditor` in `presentation/viewmodel/`, which does not use this state class.
- `DailyNoteFactory` (67 lines, 1 ref) — a pure passthrough to
  `NotesRepository.getDailyNote` / `getOrCreateDailyNote`.
- `TemplatePicker` (34 lines, 1 ref) — a pure passthrough to
  `NotesRepository.watchTemplates` / `createFromTemplate` / `saveAsTemplate`.

The two passthroughs would additionally be flagged by the `PassThroughUseCase`
rule's sibling concern if ever promoted to use cases; they are domain classes
today, so no rule fires.

**Already ruled out:** `NoteEditorState` is not an alias — the VM keeps its own
state, and the test refs are the tests written against the unused class, not
against the shipped one.

**Try next:** delete `DailyNoteFactory` and `TemplatePicker` (they add an
indirection with no behaviour) and either delete `NoteEditorState` or move the
editor's real state into it. The last part is a behaviour change and belongs in
its own change, not a sweep.

---

## editoroverflow-test-tag-unused

**Found in:** 2026-10-04 verifiability audit. `EditorOverflow` in
`core/ui/TestTags.kt` has 12 test references and zero production composables
apply it.

**Tracked as:** #65

**Symptom:** the same shape as `SNACKBAR_SAVED`, which was resolved by wiring the
tag. A test tag that no production code emits is a test asserting a state the app
can never reach — so those 12 references are either no-ops or they are skipped
without notice.

**Already ruled out:** not a dynamic lookup — the constant is referenced
statically, and `grep` for `testTag(EditorOverflow` in `commonMain` is empty.

**Try next:** find which screen the tests mean to cover and apply the tag there,
or delete the constant and the 12 references. Confirm which first: if the tests
pass today without the tag being emitted, they are not testing the overflow at
all, which is the more interesting finding.

---

## awt-menubarinstaller-jvm-unused

**Found in:** 2026-10-04 verifiability audit. `AwtMenuBarInstaller.kt` in
`shared/src/jvmMain/` has 3 test references and no call site in JVM main.

**Tracked as:** #66

**Symptom:** a desktop menu-bar installer that nothing installs. Its tests pass
because they instantiate it directly, which proves the class works, not that the
desktop app has a menu bar.

**Already ruled out:** not called reflectively or via a ServiceLoader — the
desktop entry point is `desktopApp/src/jvmMain/.../main.kt`, and the installer
is not referenced there.

**Try next:** either call it from the desktop entry point (the desktop app
currently has no native menu bar, so this is a small UI addition) or delete it
with its tests. Note the JVM/Android split matters here: the Android app has its
own menu, so wiring the AWT installer affects desktop only.

---

## detekt-rules-test-was-never-run-by-any-gate

**Found in:** 2026-10-04 verifiability audit, while proving that the two
unconfigured rulesets could fire. The proof required running
`:detekt-rules:test` — and nothing in `check.sh`, `ci.yml` or the `justfile`
ran it.

**Symptom:** `detekt-rules/src/test/` holds 10 test classes (56 tests) covering
the project's own custom rules. On first execution **4 failed**:

- `NoDirectDispatchersRuleTest > Dispatchers_IO is flagged`
- `NoDirectDispatchersRuleTest > Dispatchers_Default is flagged`
- `NoDirectDispatchersRuleTest > Dispatchers_IO in FileLogWriter is whitelisted`
- `NoEmptyOnClickLambdaRuleTest > onClick with empty lambda consumed via elvis is flagged`

Two of them proved `NoDirectDispatchers` **could not fire on the code it
targets**: the rule required `expr.selectorExpression as? KtCallExpression`,
but `Dispatchers.IO` is a property reference, so every real call site returned
early. The rule had never caught anything, and its own test said so. A third
passed a file path where `compileContentForTest` wants a package name (an
`IllegalArgumentException`, not a failed assertion). The fourth asserted
elvis-default handling the rule never implemented.

**Already ruled out:** not a stale Gradle cache — the failures reproduce from
clean, and the same PSI defect was independently observed in a live detekt run
against a deliberately-violating file.

**Resolved in the 2026-10-04 change:** the rule was fixed to accept both
selector forms, the two broken tests were corrected, the elvis shape was
implemented (empty-lambda *default parameter*, not just call-site argument), and
`:detekt-rules:test` was wired into `check.sh` and `ci.yml`. Kept here because
the general lesson is not yet enforced: a rule class with no test can still be
added, and 9 of the 18 rule classes have no unit test at all.

**Try next:** add a positive-control test for each remaining untested rule
(`PassThroughUseCase`, `NoRunCatchingInSuspend`, `NoRealDelayInTest`,
`NoStateIn`, `NoOpUpdateState`, `NoFactoryViewModel`, `NoViewModelScopeInProduction`,
`MviViewModel*`, `KDocEnforcement*`). The detekt rule-testing guide treats
"every rule has a test" as the baseline expectation; here it was the exception.
A rule is only as trustworthy as the test that proves it fires.

---

## two-rulesets-were-vacuous-52-violations-were-invisible

**Found in:** 2026-10-04 verifiability change, the moment
`NoDirectDispatchers` and `NoEmptyOnClickLambda` were made able to fire.
`find-unwired-surfaces` and the detekt report both said "0 findings" for rules
whose KDoc promised coverage; neither was true.

**Tracked as:** #61, #32 (closed)

**Symptom:** making the rules effective surfaced **52 pre-existing violations**
that no gate had ever seen:

| Rule | shared | desktopApp | total |
|---|---|---|---|
| `NoDirectDispatchers` | 19 | 2 | 21 |
| `NoEmptyOnClickLambda` | 20 | 11 | 31 |

All were baselined in the same change so the build returns to green, and
`check-baseline-ratchet.py` now prevents the counts from growing again.

**Already ruled out:** not false positives from the widened detection. The
`Dispatchers.X` sites are direct references in production code (the rule's
target); the empty lambdas are genuine `onDismiss`/`onClick` placeholders.

**Try next, and treat as two separate pieces of work:**

1. **Dispatchers (21 sites).** Each needs a `CoroutineDispatcher` constructor
   parameter plus a Koin binding change, so it is not a mechanical edit — a
   blind constructor rewrite would break the DI graph that
   `koin-compiler-plugin` validates. Do them one module at a time, running
   `:mcp-server:compileKotlin` (the DI-graph gate) after each. Note the
   existing `FileLogWriter` path whitelist still works and must not be widened.
2. **Empty handler lambdas (31 sites).** The `onDismiss` cluster in
   `WhatsNewScreen` and `ContextMenuHost` suggests sheets/dialogs are given a
   no-op dismiss rather than a real one — often a genuine wiring gap, not just
   style. Check whether each is a preview-only placeholder before changing it;
   the rule already exempts `@Preview` and `*preview*` files, so everything it
   reports is production code.

**Do not** blanket-suppress these to make the count drop. That is the move that
produced this entry.

---

## docs-audit-workflow-was-never-valid-yaml

**Found in:** 2026-10-04 verifiability change, while replacing the `|| true`
steps in `docs-audit.yml` with real exits.

**Symptom:** line 17 read `- 'openspec/**''` — a stray trailing apostrophe.
`yaml.safe_load` rejected the file outright, which means GitHub Actions could
not have run the workflow at all. Every step in it was advisory
(`|| true`, `echo "Warning:"`) *and* the file could not load. Two independent
reasons the documentation audit never happened, neither of them visible from
reading the YAML.

**Already ruled out:** not a GitHub tolerance for trailing quotes — the parser
fails on the unbalanced scalar, the same as any YAML reader.

**Resolved in the 2026-10-04 change:** the quote is fixed and the file parses.
The advisory steps were then made real, and `Enforce DIGEST size budget` now
fails the build.

**Try next, and note the general lesson:** nothing in this repo parses
`.github/workflows/*.yml`. A malformed workflow is invisible — it is not a test
failure, not a lint error, just a workflow that silently does not exist. Adding
`python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]"
.github/workflows/*.yml` to `check.sh` is a three-line fix for an entire class
of dead gate. It has not been added yet; this entry is the reminder.

---

## adr-frontmatter-drift-is-unenforced

**Found in:** 2026-10-04 verifiability change, while making
`docs-audit.yml` steps real.

**Tracked as:** #55

**Symptom:** `normalize-adr-frontmatter.sh --dry-run` exits **2** when any ADR's
frontmatter drifts from the schema, and **8 ADRs** currently do — mostly
`created:` where the schema wants `date:`, plus a few with no `status:` or
`title:`. The workflow step was `... || true`, so the exit code was discarded
and the drift accumulated unnoticed.

**Deliberately still advisory.** Flipping it to blocking in the same change that
makes other gates blocking would fail the build on pre-existing debt that has
nothing to do with those gates, and the failure would be a wall of unrelated
noise. That is the "enabling a gate reddens the build" hazard — real, and worth
absorbing for a gate whose debt is *in scope*, not for one whose debt is a
20-minute mechanical fix sitting next door.

**Try next — this is small and self-contained:**

```bash
./scripts/normalize-adr-frontmatter.sh     # no --dry-run: rewrites in place
git add docs/decisions/
```

Then delete the `continue-on-error: true` from the
`Check ADR frontmatter` step in `docs-audit.yml`. New ADRs are already
compliant — the one written for the 2026-10-04 change passes clean — so this only
ever drains.

---

## kdoc-enforcement-rules-have-no-unit-test

**Status: RESOLVED (2026-10-04).** `KDocEnforcementRulesTest.kt` now exists and covers both rules, including the nested-declaration case that guards the tree-walk fix. The rule suite is 90 tests, 0 failures, and runs in `check.sh` and `ci.yml`.
**Found in:** 2026-10-04 rule-verifiability inventory — the last rule class in
`detekt-rules/` with no test file.

**Symptom:** `KDocEnforcementRules.kt` contains `ViewModelMustHaveKDoc` and
`RepositoryInterfaceMustHaveKDoc`, both active in `detekt.yml`, neither tested.
The audit's own fix in the same change — switching them from `root.declarations`
(top-level only) to a full tree walk — landed without a test to catch it if it
were reverted or half-reverted.

**Already ruled out:** not inert. The rules do fire; the repo is simply clean
against them, which is indistinguishable from "never ran" until a violating file
exists.

**Try next — small and self-contained, roughly 30 lines of test:**
- a top-level `class TaskViewModel` with no KDoc → 1 finding
- the same class nested inside an `object` with no KDoc → 1 finding *(this is the
  regression guard for the tree-walk fix)*
- either of the above with a KDoc block → 0 findings
- a `class XRepository` that is not an `interface` → 0 findings

Assert exact counts. A `> 0` assertion would pass even if the tree walk
regressed to top-level for the nested case only in some configurations.

---

## audit-figures-that-did-not-survive-measurement

**Status: CLOSED — a correction, not a work item.** The three wrong figures were corrected at the source. Kept in this file because the lesson is the reusable part: an audit is a hypothesis list, and a claim that cannot be confirmed cheaply should be labelled unverified rather than counted.
**Found in:** 2026-10-04 rule-verifiability inventory, while re-checking the
audit's claims by execution rather than by reading code.

**Symptom:** three figures in the original audit were wrong, and acting on them
unverified would have caused damage. Recorded so the next reader does not re-import
them from the same source.

- **`NoRunCatchingInSuspend` was listed as a vacuous rule.** It is not. It is
  registered, configured, has a passing test, and is `active: false` *on purpose*
  pending a migration. "Inert" and "switched off" look identical from a distance
  and need opposite responses.
- **"109 long delay sites"** — the repository has **23** `delay(` call sites in
  total, across `shared/src` and `desktopApp/src`.
- **The 500 ms `NoRealDelayInTest` threshold was read as an accident.** It is a
  documented escape hatch for `stateIn(WhileSubscribed(5000))` VMs, which
  `TestScheduler` cannot advance past. It is now a named constant with that
  reason attached, so the next reader sees intent rather than a magic number.

**Lesson:** an audit is a hypothesis list. The value of running the gates was
never that the audit would be right — it was that executing the claims would
settle them. Every claim in a review should carry the command that confirms it,
and a claim that cannot be confirmed cheaply should be labelled unverified rather
than counted.

---

## each-module-needs-a-named-gate-owner

**Found in:** 2026-10-04, immediately after closing the rule-verifiability
inventory. Asked "what is still unwired?" and found `:androidApp:detekt`.

**Tracked as:** #54

**Symptom:** `androidApp/build.gradle.kts` has had a `detekt { }` block with
`ignoreFailures = false` and `androidApp/detekt-baseline.xml` (9 entries) since
the module was added. **No gate ever invoked the task** — not `check.sh`, not
`ci.yml`, not the `justfile`. It runs clean (0 findings, ~16 s).

This is the same defect class as `no-direct-dispatchers` and
`user-scoped-repository`: a check that is fully configured, looks authoritative,
and has never executed. The difference is only that this one happens to be
satisfied, so nothing ever went red to make anyone curious.

**Resolved in the 2026-10-04 change:** `:androidApp:detekt` added to both
`check.sh` and the `Run detekt` CI step.

**Try next — the general form of this problem.** `:mcp-server:detekt` sits in
the advisory `mcp-server-check` job and is the last unwired module-level gate.
A grep for `:detekt` across the build files will find every configured task;
each one needs a name in a gate or it is decoration. Worth doing as a
deliberate sweep rather than waiting for the next instance to be discovered by
accident — the cost of a miss is unbounded, since the check is assumed to be
running.

---

## two-line-length-authorities-detekt-default-120-beats-editorconfig-140

**Found in:** 2026-10-04, while reformatting the lines the `runCatching` →
`runCatchingCancellable` migration pushed over the limit.

**Tracked as:** #60

**Symptom:** `.editorconfig` sets `max_line_length = 140`, and `detekt.yml`
carries the comment "ktlint owns line length via .editorconfig". But ktlint's
`max-line-length` rule is `active: false`, and detekt's own
`style:MaximumLineLength` is **not configured at all** — so it runs on detekt's
built-in default of **120**. The stricter value silently wins while the config
says the project allows 140.

**Already ruled out:** not a stale report. It reproduces from clean, and lines of
121–131 characters are the only ones rejected.

**Deliberately not fixed here.** The 2026-10-04 migration rewrapped its 16
affected lines to 120 rather than relaxing the gate: bundling a gate-relaxation
decision into a correctness fix means the correctness fix cannot be reviewed
separately, and "the limit was wrong" is exactly the claim that has to be
argued rather than assumed.

**Try next — pick one and make it true:**

1. **Keep 120.** Then `.editorconfig` should say 140 → 120, and the detekt.yml
   comment should be corrected. The stricter limit is already the de-facto house
   style, and lowering a documented number to match observed practice is a
   one-line change.
2. **Keep 140.** Then add `style: MaximumLineLength: maxLineLength: 140` to
   `detekt.yml` explicitly. This *relaxes* an active gate, so it needs a reason
   recorded here and ideally a `LongMethod`-style justification.

Option 1 is the lower-risk of the two: it removes a false claim rather than
loosening a real constraint. Whichever is chosen, the other file has to change
too — leaving the mismatch in place is what produced the confusion.




---

## empty-handler-lambdas-were-previews-not-product-gaps-CORRECTED

**Status: RESOLVED (2026-10-04).** The false claim was corrected in place and the empty lambdas in preview helpers were given the project's `noopClick`. No live product defect was ever found. The remaining sweep is tracked in #61.
**Found in:** 2026-10-04 by `NoEmptyOnClickLambda`, which was made able to fire
and reported 31 sites.

**First claim, and why it was wrong.** I wrote that these were product gaps —
"a dead back button and a dead Create backup button in BackupScreen, an
unclickable TaskCard in ArchiveScreen" — and filed them as work to be wired. That
was inferred from the finding *messages*, which name the composable, not from
reading where the lambda actually sits. On reading the files:

- `BackupScreen.kt` — the production composable takes `onBack: () -> Unit` and
  wires it: `IconButton(onClick = onBack, … testTag(BACKUP_TOP_BAR_BACK))`. The
  "Create backup" and navigation buttons are wired to real handlers. The three
  empty lambdas are inside `private fun BackupScreenContentPreview(state)`.
- `ArchiveScreen.kt` — same: the production `LazyColumn` wires
  `TaskCard(onClick = { navigator.navigate(TasksGraph(Detail(task.id.value))) })`.
  The empty `onClick` is in the `ArchiveContentPreview` helper.

**No live product defect was found.** Every one of the 12 `shared` findings is a
preview helper, a test builder, or a documented-intentional case.

**What was actually wrong, and is fixed in the same change:**

1. Preview functions are named `*Preview` and use the project's `PreviewThemed`
   wrapper, but the ones holding empty lambdas carry **no `@Preview` annotation**.
   `NoEmptyOnClickLambda.isPreviewContext` only recognises an `@Preview`
   annotation, a `preview` filename, or a `/preview/` directory — so the rule
   flagged the project's own previews. All 82 `@Preview` uses elsewhere show the
   annotation is available and simply was not applied here.
2. The prescribed migration was never followed: the rule's KDoc says preview code
   should use `noopClick` from `core/ui/preview/PreviewSamples.kt`. That constant
   exists and had zero uses at these sites.

**Resolved 2026-10-04:** every preview / test-builder site now passes `noopClick`,
and `DetailMetaChip`'s `onClick ?: {}` — which is a deliberate nullable API with
the chip disabled when null, documented on the parameter — carries a
`@Suppress("NoEmptyOnClickLambda")` explaining exactly that.

**Lesson, which is the real content here:** a lint finding names a *symbol*, not
a *situation*. I read "BackupScreen.kt" and "onClick" and constructed a product
defect that did not exist, then wrote it down with a user-visible symptom
attached. The cost of that mistake is a backlog entry that would have sent
someone to "fix" already-working code. A finding whose remediation is product
behaviour is exactly the kind that must be read in place before it is recorded.

## direct-dispatchers-mostly-sit-in-platform-ports-where-they-are-correct

**Found in:** 2026-10-04, when `NoDirectDispatchers` was made able to fire. It
reported 21 sites and the plan proposed constructor-injecting a
`CoroutineDispatcher` into each, with a Koin change per module.

**Tracked as:** #61

**Symptom:** sampling the 9 baselined `shared` sites shows most of them are the
**platform port implementations** the `expect`/`actual` section of AGENTS.md
describes:

| File | Nature |
|---|---|
| `AndroidSecureStorage.kt`, `JvmSecureStorage.kt` | `SecureStoragePort` implementations |
| `JvmNotificationPort.kt` | `NotificationPort` implementation |
| `JvmFileRevealer.kt` | `FileRevealer` implementation |
| `BackgroundScope.jvm.kt` / `.android.kt` | `actual fun createBackgroundScope()` — the factory, defined to return `Dispatchers.Default` |
| `AlarmReceiver.kt`, `AndroidCalendarProvider.kt`, `AndroidCalendarAppQueries.kt` | Android platform glue, not ports |

**Why injecting is the wrong fix here.** A port implementation is precisely the
layer that *should* know it does blocking I/O — that is what the port is for.
Making the caller supply the dispatcher pushes threading decisions back up to every
call site, which is the coupling the port boundary exists to remove. The rule
already has the right precedent: it whitelists `FileLogWriter` **by file path**
precisely because ordered writes are a legitimate reason to name `Dispatchers.IO`.

**The two genuinely non-port sites** are `AlarmReceiver`,
`AndroidCalendarProvider` and `AndroidCalendarAppQueries`, and 2 desktopApp entries
that were in *test* files (now excluded — see below). Those three Android classes
are ordinary classes and could take an injected dispatcher, but each is constructed
by the Android framework (`AlarmReceiver` is instantiated by the system, the other
two are Koin singletons), so "inject a dispatcher" means changing how the framework
constructs them. That is a design question, not a mechanical edit.

**Partly resolved in the 2026-10-04 cycle:** the rule's KDoc promised to "skip all
/test/ directories" and no such filter existed, so it flagged
`CoroutineDiagnosticsTest` and `TaskDetailCoordinatorGraphTest` — tests that
legitimately build a scope on a real dispatcher because they drive a real Compose
runtime. The filter now exists and is tested.

**Try next:**

1. **Extend the path whitelist to the port layer**, mirroring the `FileLogWriter`
   precedent: a `Dispatchers.*` reference inside a `*Port` implementation or a
   documented platform factory is the design, not a violation. That removes ~6 of
   the 9 without touching a constructor.
2. **Decide the platform-factory question explicitly.** `createBackgroundScope()`
   is documented in AGENTS.md as returning `Dispatchers.Default`. Either the rule
   exempts platform factories by name, or the KDoc changes. Right now the KDoc and
   the rule disagree.
3. Only then consider the three Android framework classes, and treat each as an ADR
   — "how does a framework-constructed class get a dispatcher" is a real question.

Do **not** do a 21-site constructor sweep. It would touch DI bindings across four
modules to fix sites that are architecturally correct, and the plan's own warning
applies: enabling a rule reddens the build, but so does obeying it literally.

---

## two-largest-baseline-rules-contradict-documented-conventions

**Found in:** 2026-10-04, while sizing up a campaign to shrink the detekt baseline.
The plan proposed attacking the top-3 rules mechanically. Two of them are not debt.

**Tracked as:** #62

**`BackingPropertyNaming` — 53 entries, every one of them correct.**
AGENTS.md's *canonical VM pattern* is:

```kotlin
private val _state = MutableStateFlow<UiState>(UiState.Loading)
val state: StateFlow<UiState> = _state.asStateFlow()
```

detekt's `BackingPropertyNaming` forbids the underscore prefix. The rule is not
configured anywhere in `config/detekt/detekt.yml` — it is running on detekt's
built-in default, and it is flagging the project's own mandated pattern 53 times.
"Fixing" these means renaming `_state` → `stateInternal` in 53 places and
rewriting the canonical example in AGENTS.md, so that a style rule wins over the
documented architecture. That is backwards.

**`PackageNaming` — 43 entries, real but not mechanical.**
Almost all are one package: `com.singularity.todo.feature.calendar_sync`. detekt
wants no underscores in package names. The rename is a mechanical edit but it
touches every import of that package, and the neighbouring question — whether
repositories live in `domain/port/` — is already an open decision
(`C2` in the restore-verifiability plan). Do them together or neither.

**`LongMethod` — 39 entries, genuine, and not a campaign.**
Decomposing 39 long methods is Epic B3-scale work with real regression risk per
method. It wants a per-method decision, not a sweep. `BackupScreen.kt` at 451
lines is the largest and belongs on its own.

**Try next, in order:**

1. **Decide `BackingPropertyNaming` explicitly** (10 minutes, removes 53 entries).
   Either add it to `detekt.yml` with `active: false` and a comment pointing at
   AGENTS.md's canonical pattern, or change the convention and the doc together.
   Option 1 is almost certainly right — the underscore is doing real work, keeping
   the mutable backing property visibly distinct from the `asStateFlow()` public
   face.
2. **Leave `PackageNaming` until C2 is decided**, then do the package rename in one
   commit with its own ADR.
3. **Leave `LongMethod`.** Work it as Epic B, biggest first.

The pattern across all three is the one worth remembering: an unconfigured
detekt built-in default is a rule nobody chose. The same thing happened with
`style:MaximumLineLength` (default 120 silently overriding `.editorconfig`'s 140)
and with the two rule sets that were registered but never configured. **Default-on
is not the same as decided-on**, and a baseline full of entries that contradict
your own architecture is a signal to look at the configuration, not the code.

---

## detektbaseline-caches-its-output-and-cannot-drain

**Found in:** 2026-10-04, while trying to shrink the detekt baseline after fixing
`ViewModelMustHaveKDoc`. Four attempts produced an unchanged file.

**Tracked as:** #58

**Symptom:** `:shared:detektBaseline` is a Gradle task whose output is a tracked
source file. It gets cached like any other task, and two separate traps stack:

1. **It is additive.** Running it against an existing baseline merges rather than
   replacing, so an entry for a violation that no longer exists stays forever. The
   file has to be deleted first for it to shrink.
2. **It is cached.** With the file deleted, the task was still served from the
   build cache (`2 from cache`) and the *old* file was restored. `--rerun-tasks`
   alone was not enough; the combination that actually worked is:

   ```bash
   rm -f config/detekt/baseline-shared.xml config/detekt/baseline-desktopApp.xml
   ./gradlew :shared:detektBaseline :desktopApp:detektBaseline \
       --rerun-tasks --no-build-cache --no-configuration-cache --no-daemon
   ```

`./gradlew --stop` (documented in the detekt-rules-authoring skill for *rule*
changes) does not help here — the trap is the build cache, not the daemon. Two
attempts were lost to this, and the symptom is identical to "the fix did not
work": the entry is still in the file.

**Why it matters beyond the two entries I was chasing:** a baseline that cannot be
made smaller is not a ratchet, it is a high-water mark. `check-baseline-ratchet.py`
verifies the *committed* size, so it cannot detect that regeneration is a no-op.

**Try next:** the delete-plus-flags incantation above is the recipe; consider
putting it in a `just` recipe (`just detekt-baseline-drain`) so the next person
does not rediscover it, and note in the recipe that a plain `detektBaseline` run
only ever grows the file.

---

## gradle-test-cache-silently-skips-the-suite

**Found in:** 2026-10-04, immediately after A1 changed the CI test tag filter. The
verification run reported `> Task :desktopApp:test FROM-CACHE` and
`BUILD SUCCESSFUL` — with no test having executed.

**Tracked as:** #59

**Symptom:** a test task whose inputs are unchanged is served from the build cache
and prints success. After editing configuration (test tags, system properties,
harness code paths) the local result can therefore be a cache hit from a run that
predates the edit. `:shared:jvmTest` and `:desktopApp:test` are both configured with
`forkEvery = 1` and parallel execution, which makes them expensive enough that they
stay cacheable for long stretches.

**Already ruled out:** not a no-op task — the XML reports in
`shared/build/test-results/jvmTest/` were regenerated on a forced run and matched
the expected class count (173 shared classes, 27 desktop classes).

**Try next:** any local run that is meant to *verify a configuration change* needs

```bash
./gradlew :desktopApp:test --rerun-tasks
```

A normal run is fine for "did I break the code". It is not fine for "does the new
configuration select the tests I think it selects" — which is exactly the question
A1 had to answer, and the reason the CI job drops `--rerun-tasks` (CI starts from a
cold cache anyway, so this costs nothing there).

The same trap bit `:shared:detektBaseline` three separate ways; see
`detektbaseline-caches-its-output-and-cannot-drain`.




---

## epic-b-readability-now-unblocked-gates-work

**Status: RESOLVED (2026-10-04).** B1 (formatter merge), B2 (`TaskDetailDeps` split) and B5 (desktop harness split) are all done. B3 and B4 were phantom work and are struck in the entry below rather than carried forward.
**Found in:** 2026-10-04, after the verifiability work. Recorded because the
ordering argument for it changed, not because the items are new.

**Why it is worth doing now.** For the whole first phase of this project the
refactoring backlog was not a symptom of a bad design — it was a symptom of gates
that never ran. `TestTagsWiringTest`, `ArchitectureTest`, `HarnessConventionTest`
and the detekt rule tests all existed and none of them executed. Any
readability refactor was therefore unfalsifiable: the diff passed because nothing
checked it. That is no longer true — 1411 shared + 77 desktop tests, 90 rule
tests, and `check-gate-wiring.py` / `check-rule-intent.py` all run in CI. A
refactor now has somewhere to fail.

**Status: two of four items were phantom work and are struck below.** They were
carried in this backlog through several rewrites without anyone measuring them.
Measuring the *symbols* instead of the *files* is what caught it:

| Item | What was claimed | What is actually true | Verdict |
|---|---|---|---|
| B2 `TaskDetailDeps` split | 25 ctor params, 4 sites, 16 files | **24 params** (counted), 16 referencing files. Confirmed. | Real — **done 2026-10-04** |
| B3 decompose 4 composables | "451 / 270 / 225 / 212 lines" | Those were **file** sizes. The composables are 196 (`TaskDetailViewScreen`), 188 (`BackupScreen`), then ≤62. Nothing is near the `LongMethod` limit of 80. | **Phantom — dropped** |
| B4 `testTask()` fixture | "0 uses today" | 8 calls in 6 files (`CommonFakes`, `TasksRobot`, 3 test classes). | **Phantom — dropped** |
| B5 split `DesktopNavigation.kt` | 510 lines, 29 helpers | Confirmed exactly. | Real — **done 2026-10-04** |

The B3 and B4 numbers were never re-measured after the first draft; they were
copied forward and re-copied. B4 in particular claimed a fixture was unused when
the skill `singularity-todo-desktop-compose-ui-tests` documents it as the standard
seed (`testTask()` defaults to `TestUsers.DEFAULT`) — the two documents
contradicted each other and the backlog won by being written last. **Try this
first when a backlog item survives a rewrite:** `grep` the symbol. If the claim is
a count, re-count it.

**Not done in the 2026-10-04 pass** — B1 landed instead (see
`2026-10-04-…` for the formatter merge, which was self-contained). B2–B5 are
recorded here rather than started, because a 16-file refactor that cannot be run
to completion and verified leaves the tree worse than not starting it.



**B5 — done (2026-10-04).** `DesktopNavigation.kt` (510 lines, 29 helpers) split
into `DesktopNavigation.kt` (135, drawer + `DesktopShell`) ·
`DesktopAssertions.kt` (328, `await*`/`assert*` + `TIMEOUT_MS` + `TAG_PATTERN` +
`explainMissingTag`) · `DesktopInteractions.kt` (61, `click*`/`type*`).
Zero-behaviour move: all 29 signatures diffed identical before/after, and
`jvmTest` **is** covered by `:desktopApp:detekt` (`source.setFrom("src/main/kotlin",
"src/jvmTest/kotlin")`) so the split is linted, not just compiled. Verified by a
full `:desktopApp:test` run — 27 classes / 77 tests, 0 failures.

Note for the next splitter: `TooManyFunctions` excludes `**/jvmTest/**` and
`LargeClass` allows 600 lines, so a 510-line test helper was **not** a detekt
violation. It was split for readability, and detekt staying green through the
split is not itself evidence the split was warranted.

**B2 — DONE (2026-10-04).** `TaskDetailDeps` split into six bundles, and each
slot's constructor was narrowed to the bundles it actually reads:

**Also worth doing, cheap:** `TaskDetailState.kt` contains **no**
`TaskDetailState` — it holds only `TaskDetailDeps` (`grep -rn "class TaskDetailState"`
returns nothing). Either rename the file to `TaskDetailDeps.kt` or restore the
state class it was named for. Do this together with B2, which edits the file
anyway.



| Bundle | Fields | Read by |
|---|---|---|
| `TaskCoreDeps` | 4 | coordinator, draft, entity, completion, children, lifecycle, reminders |
| `TaskChildrenDeps` | 4 | entity, children |
| `TaskSchedulingDeps` | 3 | reminders, lifecycle |
| `TaskCollaborationDeps` | 6 | coordinator, AI, backlinks, logbook, time slot |
| `TaskAiDeps` | 5 (all nullable) | AI only |
| `TaskContextDeps` | 1 | coordinator, draft, completion, children, reminders, AI |

The grouping came from grepping each slot for `deps.X`, not from taste — that is
why `TaskChildrenDeps` merges checklist/attachments/projects/tags while
`TaskSchedulingDeps` stays separate, and why the coordinator is the only holder
of the full aggregate. The old flat class had 24 constructor parameters and every
slot held the whole bag, so `TaskAiSlot` could reach the reminder scheduler and
nothing would have failed if it had.

**The narrowing is the point, and it is checkable:** no slot file mentions
`TaskDetailDeps` any more (the coordinator is the sole holder), and every bundle
is at or under detekt's `allowedConstructorParameters: 8`. The 24-parameter class
had been invisible to `LongParameterList` only because `ignoreDataClasses: true`.

`TaskDetailState.kt` → `TaskDetailDeps.kt` in the same commit: the file held no
`TaskDetailState` at all (`grep -rn "class TaskDetailState"` returns nothing), so
renaming it is the honest fix rather than inventing a class to justify the name.

**Worth recording about the mechanics.** The refactor itself produced 293 detekt
findings — every one formatting, from a scripted edit of 29 call sites. None were
baselined. `--auto-correct` took it to 98, and the last 98 needed hand-fixing for
two reasons worth knowing: ktlint's `indent` rule is configured
`auto_correct: false` in this repo (deliberately, JDK-NPE workaround), and
auto-correct does not reformat a call that mixes named and positional arguments.
The fix that worked was making every argument named. `:shared:jvmTest` stayed
green throughout, which is the point — the gates now have somewhere to fail.

---

## autocorrect-touches-files-outside-the-change

**Found in:** 2026-10-04, during the B2 `TaskDetailDeps` split, immediately
after adding the `check-rule-intent.py` gate.

**Tracked as:** #57

`./gradlew :shared:detekt --auto-correct` rewrote **five files that had nothing
to do with B2**: `BackupMigrations.kt`, `LogbookSection.kt` (unused
`java.util.Locale` import), `TimeTrackingSection.kt` (trailing blank line),
`LogBundleExporterTest.kt`, and `EntityMapperCompletenessTest.kt` (33 lines of
re-indentation). All five are baselined debt that had been sitting there.

They were reverted, because a commit titled "split TaskDetailDeps" that also
silently reformats an unrelated test fixture is a commit nobody can review —
and the next person to bisect it would have no way to tell the two apart.

**Why this is worth recording rather than just doing:** `--auto-correct` on a
module-wide task has no idea what the current change is about. It is correct
individually in every case here — that is what makes it dangerous, since
"obviously fine, why not" is the natural reaction to each individual hunk.

**Try this first:** after any auto-correct run, `git diff --stat` and revert
anything outside the stated scope. Cheaper alternative for a large cleanup: run
auto-correct in its own commit, before the real change, so the formatting churn
is already in history.

Related: `EntityMapperCompletenessTest.kt` carries 2 baseline entries for this
file, and `TimeTrackingSection.kt` is the source of the currently-undeclared
`NoConsecutiveBlankLines` finding that `check-rule-intent.py` reports (verified
present on a clean `HEAD`, not introduced by B2). A cleanup commit should declare
that rule rather than leave it on detekt's default.




---

## no-consecutive-blank-lines-was-never-declared

**Tracked as:** #56

**Found in:** 2026-10-04, immediately after `check-rule-intent.py` was wired into a
run that touched documentation. The gate reported exactly one hit.

**Symptom:** `NoConsecutiveBlankLines` produces one finding
(`TimeTrackingSection.kt`, present in `baseline-shared.xml`) and is not named
anywhere in `config/detekt/detekt.yml`. It is running on detekt's built-in default.

Verified present on a clean `HEAD` — not a regression from the B2
`TaskDetailDeps` work.

**Why one finding is worth a backlog entry.** A rule nobody declared is a rule
nobody chose. If a future detekt release changes that default, the baseline stops
matching and the gate fails for a reason nobody can reconstruct. The
`check-rule-intent.py` gate exists to make that class of invisible decision
visible, and this is the first real hit it produced — which is also the proof
that it is doing its job rather than merely passing.

**Try next:** declare it with `active:` and a reason. The honest answer is
probably to fix the file (it is one trailing blank line) and let the count reach
zero, then delete the baseline entry.

Related: `autocorrect-touches-files-outside-the-change` — the same file is one of
the five `--auto-correct` wanted to rewrite.




---

## an-untagged-test-class-is-invisible-to-a-tag-filtered-run

**Found in:** 2026-10-04, on the first CI run of the verifiability branch — the
run that finally executes `testAndroidHostTest`, which the old
`-Ptest.tags=fast,slow` filter had meant never ran at all.

**Tracked as:** #74 (fixed in the same branch); the open question below is the
gate, not the test.

**Symptom.** `ReadToolsProfileAwareTest` has five structurally identical tests, and
which ones fail changes every run: 2 of 970 on a forced `main` run, 1 of 980 on
this branch, a *different* one each time. A probe of the same scenario in
isolation passes.

**Root cause — a race the test had with itself, not a tool bug.** The tool
correctly returned nothing. `ProfileAwareCurrentUser` seeds `scopedUserId`
synchronously in its constructor, so the construction-time value is right. The
race is one line later: `profiles.switchTo(...)` changes an upstream, and the only
thing that propagates that into `scopedUserId` is a collector on the **injected
scope**. The test injected `createBackgroundScope()` — `Dispatchers.Default` — so
whether the tool's `scopedUserId.flatMapLatest { … }` read the profile-scoped value
or the stale pre-switch one was a race. The task was stored under `profile/user`,
the filter used `user`, and the result was `expected: <1> but was: <0>`.

The class's own KDoc states the contract that was broken — *"In tests, inject a
`TestScope` or `backgroundScope`"* — and the test carried a comment justifying the
violation on a premise that is false: the tools under test **do** subscribe.

**The finding that outlives the fix.** The class carries **no `@Tag`**. A
tag-filtered run skips an untagged class silently, and nothing records the
omission. `TestTagsWiringTest` verifies that every *tag* is applied by a
composable; it does not verify that every test class carries one. So the class
could be arbitrarily broken — as it was — for as long as nobody added a tag.

**Try next — the open question.** Should an untagged test class fail a gate?

A class with no tag is not a test that is deliberately deferred; it is a test that
is *unrunnable* in a tag-filtered build, and the difference is invisible from the
source. If tag filtering is going away, the question is moot. If it stays for the
slow suite, an untagged class is a hole with no marker.

Also worth noting: `koverXmlReport` depends on `testAndroidHostTest`, so the
`kover-report` job was **red on `main`** for this reason. A job that is red for a
reason nobody reads is the same failure as a gate that is green for a reason nobody
checks.





---

## the-dead-refs-gate-was-green-locally-and-red-in-ci

**Found in:** 2026-10-04, on the first CI run of the verifiability branch. The
meta-gate found it, which is the only reason it was found at all.

**Tracked as:** fixed on the verifiability branch; regression test
`scripts/tests/test_check_doc-dead-refs.py`.

**Symptom.** `test-and-check` failed at "Gates are wired and can fail" with:

```
ERROR: gate 'doc-dead-refs' already fails on a clean tree (exit 1)
```

`check-doc-dead-refs.py` passes in every developer checkout and fails in a fresh
`git clone --depth 1`. **A gate whose result depends on the machine is the worst
shape a gate can have**: everyone trusts a signal that is not portable, and the
failure only appears for whoever has no local hook state.

**Cause.** `DIGEST.md` is gitignored on purpose — rebuilt by a post-checkout hook,
absent in a fresh clone, present after a docs refresh. The gitignore handling was
added *for exactly that reason* and it did not cover every form.

A gitignore pattern containing `/` is anchored at the repo root, so
`docs/decisions/DIGEST.md` matches that path and nothing else. Three skills
reference the same generated file by **basename** as plain `DIGEST.md`, and this
same script resolves references by basename elsewhere. `is_generated` tested only
the literal string, so those three were reported dead on a fresh checkout and silent
anywhere the hook had run.

**Fix.** `is_generated` also matches the ref's basename against the basenames of
gitignored *files* — restricted to entries carrying an extension, so a gitignored
directory named `build` cannot make an unrelated `build` look generated.

**The part worth keeping.** The regression test asserts the *negative* direction too:
`GLOSSARY.md` and a real source path must still be reported dead. A basename rule
that is too broad does not fail loudly — it just stops the gate measuring anything,
which is how this repository ended up with sixteen gates that reported success
without testing anything.

**Also fixed, found by noticing it in a staged diff rather than by a gate:**
`.gitignore` had `scripts/__pycache__/`, which does not cover
`scripts/tests/__pycache__/`, so the new test's bytecode staged cleanly. Widened to
`__pycache__/` at any depth.

**Try next — the general form.** A gate that passes locally and fails in CI is
usually assuming a developer-machine artefact: a generated file, a hook, a warm
cache, a local SDK. `check-gate-wiring.py` catches the "cannot fail" direction; this
is the "cannot be trusted" direction, and nothing catches it. Running a gate against
a fresh `git clone --depth 1` is the cheap test, and it is what turned a red CI job
into a one-line fix instead of an afternoon.




