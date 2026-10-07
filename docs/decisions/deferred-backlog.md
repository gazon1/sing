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

## log-export-has-no-surface

**Status: OPEN**

**Tracked as:** #37

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

## flow-has-tag-drops-a-flow-whose-tag-has-a-trailing-space

**Status: CLOSED.** 2026-10-05.

**Tracked as:** [#148](https://github.com/gazon1/sing/issues/148)

**Found in:** the gate audit in
`2026-10-05-gate-audit-text-shape-vs-fact` (0A.5), which asked every test gate
"what input passes silently?". Reproduced by probe the same day, not inferred.

**Symptom:** `flow_has_tag` (`scripts/run-maestro.sh:166`) compares a flow's
header tag to the requested tag with awk string equality. A tag with one
trailing space does not match, so `TAGS=smoke scripts/run-maestro.sh` omits that
flow and says nothing. Nothing asserts that every flow is reachable by some tag,
so a flow dropped this way disappears from every suite while every gate stays
green — the same shape as the D1 defect, where two classes were reported clean
by the gate that exists to report them.

**Already ruled out:** not a Maestro behaviour. `--include-tags` is ignored when
a single file is passed, which is exactly why the filter is applied in the
script; the comparison is the script's own.

**Fix, and why the first deferral was the wrong call.** The tag matching moved
out of `run-maestro.sh` into `scripts/maestro-flow-tags.sh`, a sourceable file
with no adb or emulator dependency, and both ends of the comparison are now
trimmed. This was filed rather than fixed alongside the other gate repairs
because the change could not be exercised in that environment — which turned out
to be the wrong reason. The matching is pure text and is now unit tested
(`scripts/tests/test_maestro_flow_tags.py`, 16 cases) with no device at all.

Three further defects surfaced while writing those tests, none visible before:
the tag block was never terminated, so a step like `- tapOn: 'x'` under
`commands:` was read as a tag and `TAGS=tapOn:` would have selected every flow;
`tags:  # comment` did not open the block; and a CRLF-edited flow kept a `\r`
in its tag and matched nothing.

The tests add the invariant that closes the loop and which nothing in CI
asserted before: every flow declares at least one tag, every declared tag
selects its own flow, and every `TAGS=` value CI asks for resolves to at least
one flow. Measured over the current tree: 58 flows, none untagged, no
unreachable tag, `TAGS=smoke` selects 19.

---

## thirteen-scenario-slices-queued-not-yet-written

**Status: OPEN**

**Tracked as:** [#170](https://github.com/gazon1/sing/issues/170)

**Found in:** the plan `Ремонт измеримости и сценарии покрытия` (срезы 2-14), and
then re-confirmed by code on 2026-10-05 once the traceability machinery landed.

**Situation:** the scenario layer exists and holds exactly one scenario,
`TASK-REC-01`, delivered as the pilot. The other thirteen are written nowhere — not
here, not in an issue, not in an OpenSpec change. The matrix says
"1 scenarios · 2/2 claimed cells automated · 0 holes", which is true and says almost
nothing: one scenario is not a matrix.

**Measured 2026-10-05, per area, so the queue is facts rather than suspicion:**

| Area | Test files | Production files | Reachable in UI |
|---|---|---|---|
| `feature/checklist` | **0** | 6 | yes — `ChecklistEditorSheet` via `TaskEditorSheetsHost.kt:140` |
| `feature/timetracking` | **0** | 11 | yes — `TimeTrackingSection` at `TaskDetailViewScreen.kt:218` |
| `feature/statistics` | 1 | 3 | yes, no tag on the chart |

The first two are why slices 2-4 come first rather than being an arbitrary order: they
are areas with **zero tests in any layer** that a user can reach. Filling the matrix
from the areas that already have tests would produce a full matrix that mostly means
"what was already covered is now also a scenario" — the same illusion the class-count
floor was created to remove.

**Deliberately not slices.** Cloud sync has no host screen (ADR
`2026-09-29-sync-config-screen-has-no-host`), bulk task operations have no multi-select
(#36), the 7 tables outside the backup payload are #77, and `Regexp`/`DateRange` agenda
templates are JSON-only. Each is a gap with its own issue, not a scenario to write, and
they are reported as `unreachable` with a link to an OPEN record — an audit that treats
an honest gap as a failure gets its gaps filled with fiction.

**Try next:** `TASK-SUB-01` first (it guards a bug that actually shipped), then
`TASK-CHK-01` and `TASK-TT-01`. Each slice is one PR carrying its spec, its test, and
the seed or tag *it* needs — not a pre-paid batch of tags for every reachable control,
because some will turn out unnecessary.

---

### Which of them a Maestro flow could close: none, measured 2026-10-07

Asked to tag flows so the matrix would stop standing at `holes: 32 / dark_scenarios: 16`,
the answer was **zero tags**, because zero could be placed honestly. The 32 holes
decompose as follows, and every branch is a structural reason rather than an
unfinished job:

| Holes | Why no flow can close them |
|---|---|
| 16 (8 SYNC specs × 2 tiers) | the sync specs are *defined* by a second device; `sync/01-offline-create-survives-reconnect.yaml` is single-device and does not verify any of them |
| 6 of those | already counted as `unreachable` in the ratchet — same reason, recorded |
| 6 AUTH specs | **there is no auth flow in `Maestro/flows/` at all**; six specs, zero candidate flows |
| `TASK-CHECK-01` | its spec claims `targets: [desktop]`, so an Android flow structurally cannot close it — it needs a JVM Compose test |
| `CAL-FILT-01` | its `expected` records that the feature **does not exist**: "Nothing happens, and nothing ever did: there is no `CalendarFilterPanel` in the tree". A carrier would assert absence, which is a different claim from the one the spec makes |
| `TASK-TIME-01` (2 cells) | the UI exists (`feature/timetracking/presentation/components/TimeTrackingSection.kt`) but no flow drives it; the `pomodoro/*` flows are a different feature. Its `○` is not a tagging gap, it is a missing flow |

**Already ruled out:** not an argument that tagging is wrong in general. `TASK-REC-01`
is tagged and legitimately so — one flow, one honest carrier.

**Why this matters more than the number.** The ratchet's own note for `TASK-TIME-01`
records the failure mode this avoids: "a spec narrowed to match a bug is neither honest
nor a coverage claim". A tag on a flow that does not exercise the scenario would make
`holes` fall while coverage stays exactly where it was — the matrix would stop being a
measurement and become a decoration. Same defect class as a gate that reports green
without running, and worse, because the number would look like progress.

**Order of work, given the table above:**

1. **Write flows, then tag them.** The order is the whole point. A new flow for
   `TASK-TIME-01` that starts and stops a timer, verified on a device, earns its tag;
   a tag on `pomodoro/02-start-focus-task.yaml` does not.
2. **`CAL-FILT-01` first, because it needs no flow.** Its spec says the feature does
   not exist. Either delete the spec or implement the filter — both are smaller than a
   test, and both stop the scenario layer carrying a permanent fiction.
3. **`TASK-CHECK-01` is a desktop carrier problem**, not a Maestro problem: its spec
   claims `[desktop]`, and the checklist section is JVM-testable today.
4. **The AUTH specs need a whole flow family** — six specs, zero flows, which is the
   largest single block of uncovered product behaviour in the matrix.

## debug-seed-cannot-build-a-related-graph

**Status: OPEN**

**Tracked as:** [#171](https://github.com/gazon1/sing/issues/171)

**Found in:** the same plan, 0C.1, and re-confirmed on 2026-10-05 while checking
which of the queued scenarios have a prerequisite rather than only a missing test.

**Situation:** `DebugSeedActivity` dispatches on a single key and the first matching
branch wins, so one invocation seeds **one object**. Three queued scenarios need a
graph: a subtask attached to its parent, a task with time entries, a populated
database to round-trip. Adding branches does not reach that — the branches cannot
reference each other and nothing is atomic, so a failure halfway leaves a half-seeded
database that the next assertion reads as real data.

**Already ruled out:** seeding by writing a backup and restoring it. Seven tables sit
outside the backup payload (#77), so that route silently omits them and the scenario
tests a subset of the database while claiming to test all of it.

**Try next:** a JSON payload describing the object graph, deserialised into a
`sealed interface SeedItem` and applied through the existing use cases in one
transaction. Through use cases, not DAOs: a scenario must not be able to construct a
state the app itself could not produce, because that is the property that makes it
worth having. The same model should serve the desktop Compose harness, with the shared
part in the test support module. Do not build it speculatively — `TASK-SUB-01` is the
first scenario that needs it, and its requirements are the honest ones.

---

## class-body-scanning-is-not-string-aware

**Status: OPEN**

**Tracked as:** [#173](https://github.com/gazon1/sing/issues/173)

**Found in:** the gate audit in
`2026-10-05-gate-audit-text-shape-vs-fact`, while asking what input passes the
runnable-test predicate silently.

**Situation, measured rather than suspected:** the class-body scanner counts braces
line by line and is not string-aware, and **97 lines** in the current test tree carry
an unbalanced literal brace inside a string. So the trap is set on 97 lines. Comparing
the naive counter against a string-aware one across **all 269 real test classes**
produced **zero** differing verdicts, so nothing has fallen into it — the braces that
matter are balanced `${...}` templates, and the unbalanced ones sit after the last test
member in their class.

**Why it stays open rather than being fixed:** a real lexer for a defect with zero
measured impact, in a source set (`commonTest`) that has no parser dependency today. A
gate that needs a new build dependency is a gate that gets removed the first time that
dependency is inconvenient.

**Try next:** nothing, unless a test file puts a bare `}` in a literal *above* a test
member in its class. It is already covered one layer up — the by-results check in
`check-test-runs.py` reads the run rather than the source, so a class the predicate
mis-scopes and a genuinely untagged class produce the same symptom and are caught
either way. That is the argument for keeping the structural check above the text one,
not an argument that this one is fine forever.

---

## bulk-task-operations-have-no-ui

**Status: OPEN**

**Tracked as:** #36

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

**Status: OPEN**

**Tracked as:** #38

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

**Status: OPEN**

**Tracked as:** #43

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

## desktop-flow-tests-share-one-jvm-and-one-fails-only-in-the-batch

**Status: OPEN — re-measured 2026-10-07; the bundle narrows it to a state, not a write**

**Tracked as:** #40

**Re-measurement (2026-10-07).** The failure bundle settles the first question — is the
value saved? — and it is:

```
+0.98s  awaitTag(priority_option_high)    OK    0.65s      <- the click landed
<then the label assertion fails>
```

`db-state.txt` for the same attempt:

```
TaskEntity(id=robot-task-0, title=Buy milk, …, priority=High, …,
           updatedAt=1789552800000, sync=SyncColumns(…))
```

So the write completed, `updatedAt` moved, and only the *rendered label* stayed at
"No priority". That rules out the double-fire and the lost-click hypotheses outright, and
narrows the defect to: the slot's `mutate()` writes through `core.updateTask { … }` and
never publishes the result back to the slot, so `TaskEntitySlot` keeps serving the task it
loaded until some *other* flow re-emits it.

The subscription exists — `TaskDetailCoordinator` collects
`core.taskRepo.observe(taskId)` and sets `taskLoad` — which is why this is a **race** rather
than a dead path, and why it passes in isolation: the emission arrives, the batch just
asserts before it does. `waitUntil` pumps the Compose clock, so a genuine 5-second absence
is a genuine absence; what varies between runs is which flow got there first.

**What is ruled out, measured rather than argued.** Not the click (the bundle records it
succeeding). Not the write (`priority=High` with a moved `updatedAt`). Not a lost
`@Tag` — `assertTagDisplayed` and `assertTextDisplayed` fail differently and this one
fails on *text*. Not host contention: a run under load 40 reproduced it and a run at load
16 did not, which is the definition of ordering-dependent rather than resource-dependent.

**Try next, in this order.**

1. **Publish from `mutate()` rather than waiting for the repository flow.** `mutate` is
   `core.updateTask(id) { … }.onFailure { … }`; the fix is to apply the same transform to
   the slot's own state on success. That removes the race for *every* slot that uses
   `mutate` — priority, due date, estimate, recurrence — rather than for one symptom.
   The trade-off is real and should be written down: the repository flow remains the
   authority, so this must be a cache update, not a second source of truth.
2. **Assert through the state, not the render.** If the race is inherent, the test should
   await the label with a bounded poll instead of asserting once. Cheaper, and it hides a
   real user-visible lag, so it is second and not first.
3. **Find what makes the batch late.** 182 active coroutines at failure, including leaked
   `CurrentUser` collectors, points at a scope outliving its class; that is a leak worth
   fixing on its own merits but it is not this test's cause.

**Found in:** MR-5 final `./check.sh` — the only observation in five runs.

**Original observation (2026-07):** `ProjectsFlowTest` failed once with
`NullPointerException` from `ProjectDetailViewModel.getDraftState()`. Not reproducible in
three `--rerun-tasks` runs. Filed as a one-time flake, and was.

**Re-measured 2026-10-07.** `:desktopApp:test` had 8 failures across 6 classes. Because
`desktopApp/build.gradle.kts` sets `parallel.mode.classes.default = same_thread`, the
classes run *sequentially in one JVM* — so a first guess of "load" was wrong and a second
guess of "state leaking between classes" was wrong too: dumping the result files in
completion order showed the failures interleaved with passes, which rules out anything
monotonic. The harness's own failure bundle (`desktopApp/build/diagnostics/<Class>/`) is
what actually placed it, and six Gradle runs had not.

**Seven of the eight were one binding, now fixed.** `testPlatformModule()` binds
`single<UnitOfWork> { RoomUnitOfWork(get()) }` against `FakeAppDatabase`. That fake
*extends the generated `AppDatabase`*, so it is a `RoomDatabase` by type, but Room never
opened it and Room's `coroutineScope` is a `lateinit` only Room's own initialisation
assigns. So the first write of any test that saved a task threw
`UninitializedPropertyAccessException: lateinit property coroutineScope`, surfaced on screen
as a generic "Save failed", left the editor open, and wrote nothing — which reads exactly
like a timeout, and is why it was misdiagnosed twice. `FakeUnitOfWork` is the pass-through
the comment there always described; it is now bound instead.

**What remains is one test.** `SetPriorityFlowTest > choosing_high_updates_the_row_label`
fails in the full suite and **passes in isolation**. Unlike the others it is not a broken
double: the DB snapshot shows `priority=High`, so the value persisted and only the row
label is stale. So the class of defect here is narrower and different — a UI that did not
re-render for a state change that demonstrably happened.

Two things worth keeping in mind when reading any other failure in this suite:

- `coroutines.txt` in the bundle reported 182 active coroutines at failure, including
  leaked `CurrentUser` collectors. A leaked scope outlives the class that made it, which
  is the most likely home for a batch-only failure.
- `DraftMviViewModel.save()` had three failure arms and only the `throw` arm logged. A
  draft rejected by validation, or refused by the use case, left no trace in the bundle —
  and `CreateTaskFromDraft` dropped the `cause` when wrapping into `AppError.Persistence`,
  so the stack died at the boundary. Both now carry it. That is the difference between
  this taking six runs and the next one taking one.

**Try next:** for the remaining test, assert the priority row's own state rather than its
rendered label, to separate "the click did not reach the handler" from "the handler ran
and the composable did not observe". The DB snapshot already says the latter.

---

## no-direct-clock-system-kdoc-claims-tests-are-exempt

**Status: OPEN**

**Tracked as:** #42

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

## no-direct-dispatchers-rule-one-whitelisted-case

**Tracked as:** #44

**Found in:** MR-B (tech-debt batch). `NoDirectDispatchersRule` bans
`Dispatchers.IO/Default/Main` in production. One legitimate case was
identified: `core/log/FileLogWriter.kt:50` uses
`Dispatchers.IO.limitedParallelism(1)` to guarantee sequential writes.

**Status: OPEN.** Corrected 2026-10-05: the whitelisting itself is in the rule
code. The rule had no `detekt.yml` block at all, so it never ran; a block was
added that day (`no-direct-dispatchers` / `NoDirectDispatchers`, `active: true`).
What remains open is the sweep in #44, not the whitelist.

**Note:** the whitelisting is already done in the rule code
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

**Status: OPEN**

**Tracked as:** #45

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

**Status: OPEN**

**Tracked as:** #41

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

**Status: OPEN**

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

**Status: OPEN**

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

## notification-text-null-invisible

**Status: OPEN**

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

## agenda-views-not-in-backup

**Tracked as:** [#77](https://github.com/gazon1/sing/issues/77) · OpenSpec change `backup-include-remaining-tables` (proposed)

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

**Status: OPEN**

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

## agenda-reachability-byTags-no-ui-entry

**Tracked as:** [#81](https://github.com/gazon1/sing/issues/81) · OpenSpec change `agenda-tags-entry-point` (proposed)

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

## undo-restore-failure-notify

**Status: OPEN**

**Tracked as:** [#78](https://github.com/gazon1/sing/issues/78) · OpenSpec change `delete-safety-feedback` (proposed)

**Found in:** MR-1 retro-gate, `AgendaViewModel.onUndoDelete`.

When `taskRepo.restore(taskId)` fails, `_pendingDelete` is already set to `null`
and the snackbar has dismissed. The user gets no feedback.

**Checks already performed:** `restore` returns `Result<Unit>`, failure is caught
but only logged.

**Fix:** On restore failure, re-set `_pendingDelete` with an error flag and show
an error snackbar; or emit a `AgendaUiEvent.ShowError` event.

---

## task-detail-scaffold-refactor

**Status: OPEN**

**Tracked as:** [#79](https://github.com/gazon1/sing/issues/79) · OpenSpec change `delete-safety-feedback` (proposed)

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

**Status: OPEN**

**Tracked as:** [#80](https://github.com/gazon1/sing/issues/80) · OpenSpec change `delete-safety-feedback` (proposed)

**Found in:** MR-1 retro-gate. `LaunchedEffect(pendingDelete)` only re-triggers on
value changes, not on a timer. The snackbar shows no visual countdown.

**Ruled out:** Standard Material3 `SnackbarHost` does not support countdown. Custom
`Snackbar` with `ProgressIndicator` is non-trivial.

**Fix:** Replace `SnackbarHost` with a custom composable that shows a `LinearProgressIndicator`
inside the snackbar, animated from 100% to 0% over 5 seconds using `animateFloatAsState`.

---

## bulk-import-port

**Status: OPEN**

**Tracked as:** [#82](https://github.com/gazon1/sing/issues/82) · OpenSpec change `bulk-import-port` (proposed)

**Found in:** MR-1, `BackupImporter` class KDoc and architecture review.

`BackupImporter` writes directly to DAOs to bypass `assertCanWrite` guards, targeting
`options.targetUserId` without going through repositories. This is documented
technical debt.

**Fix:** Create a `BulkImportPort` interface that takes an explicit `targetUserId: UserId`
and routes writes through repositories. Replace DAO calls in `BackupImporter` with
`BulkImportPort.import(payload, targetUserId)`. Track in `docs/decisions/2026-09-27-write-layer-soundness.md`.

---

## vm-without-test

**Status: OPEN**

**Tracked as:** [#83](https://github.com/gazon1/sing/issues/83)

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

## maestro-gate-can-test-a-stale-apk

**Status: OPEN**

**Tracked as:** [#84](https://github.com/gazon1/sing/issues/84)

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

**Status: OPEN**

**Tracked as:** [#85](https://github.com/gazon1/sing/issues/85)

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

**Status: OPEN**

**Tracked as:** [#86](https://github.com/gazon1/sing/issues/86)

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

**Reproduced 2026-10-05, in a recipe written to prevent exactly this.** `coverage-ratchet` gained an
environment flag and its own documentation said `just cr RERUN=1`. That form does not assign here:
the flag was silently absent, `just cr` ran, exited 0, and measured less than asked — the expensive
kind again, and on the one gate whose entire subject is whether a measurement measured anything.

The recipe now rejects a `NAME=value` argument outright (`exit 64`) with a message naming the
environment form. The general fix is still unbuilt: **this is a `just` behaviour, not a repository
one, so every recipe that documents a flag is exposed to it.** A guard that lives in one recipe
protects one recipe. A guard in the shared test module, or a convention that recipes read flags from
the environment with an explicit `case` on `{{args}}`, would protect all of them.

**Try next.** Grep the recipes for `=` in `[doc()]` strings and in comments — any that tell a reader
to type `just <recipe> NAME=value` is documenting a form that does not work here. That is a
mechanical sweep and a mechanical fix.

---

## maestro-ci-job-unproven

**Status: OPEN**

**Tracked as:** [#87](https://github.com/gazon1/sing/issues/87)

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

**Status: OPEN**

**Tracked as:** [#88](https://github.com/gazon1/sing/issues/88)

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

## maestro-flows-share-one-app-instance-so-failures-cascade

**Status: OPEN**

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

**Status: OPEN**

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

## never-run-gradle-while-a-maestro-gate-is-running

**Status: OPEN**

**Tracked as:** [#89](https://github.com/gazon1/sing/issues/89)

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

**Status: OPEN**

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

**Status: OPEN**

**Tracked as:** [#90](https://github.com/gazon1/sing/issues/90)

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

**Status: OPEN**

**Tracked as:** [#91](https://github.com/gazon1/sing/issues/91)

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

**Scope, measured 2026-10-05** (the plan that produced this entry called the
class "wider than believed"; these are the counts, so the next attempt starts
from facts rather than from the suspicion):

- 17 call sites of `todayInSystemZone()` across 10 files under `commonMain`:
  `feature/agenda/domain/logic/RelativeBucket.kt`,
  `feature/calendar/CalendarPreview.kt`,
  `feature/calendar/presentation/screen/{CalendarContent,CalendarScreen}.kt`,
  `feature/nav/AppDestination.kt`, `feature/search/query/SearchQueryResolver.kt`,
  `feature/tasks/domain/usecase/CreateTaskFromDraft.kt`,
  `shell/FabActionResolver.kt`, `core/observability/RoomUsageRecorder.kt`,
  and `core/platform/Clock.kt` itself.
- 17 direct `Clock.System.now()` calls under `feature/**`.

Two of those reach the core of what a scenario matrix would assert:
`CreateTaskFromDraft` (creating a task with `due = today`) and
`SearchQueryResolver` (searching by date ranges around today). Neither is
deterministic today, so a `TASK-*` or `SEARCH-01` scenario written against
either would be non-deterministic by construction — which is why the fix is a
class-level injection plus a rule, not a point fix in the calendar.

---

## six-smoke-flows-still-red-after-the-harness-fix

**Status: OPEN**

**Tracked as:** [#92](https://github.com/gazon1/sing/issues/92)

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

**Attempted 2026-10-05, not completed — the device would not boot.**
`scripts/ensure-emulator.sh` started `Medium_Phone`, but `adb devices` went
`emulator-5554 offline` and then dropped the device from the list entirely while
the emulator process was still alive; 12 polling attempts over ~4 minutes never
reached `device`. No flow was run, so **no new diagnosis was produced and the
table above is unchanged**. This is the host-side gfxstream instability recorded
in `2026-09-28-emulator-gfxstream-colorbuffer-segv.md` and in `AGENTS.md` ("Maestro
currently fails in this environment"), showing up as a device that never
registers. Re-run the six one at a time on a host where the emulator boots; the
per-flow `FLOW=…` recipe in the paragraph above is still the right first move.

Related: a debug APK now carries its git sha in `versionName`
(`0.1.0+g<sha>`, see `androidApp/build.gradle.kts`) and `run-maestro.sh`
refuses to run when the installed binary's sha disagrees with the checkout.
That check is why the next run cannot silently report a result for a
snapshot-restored APK.
---

## test-doubles-in-commonmain-source

**Found in:** 2026-10-05 spec-governance sweep. `scripts/find-unwired-surfaces.py`
detector 7 reported `MapFileSystem`, `FakeSecureStorage` and `FakeDraftStore` as
symbols with test references and zero production references. The baseline recorded
each as `BacklogRef: none`, which its own header rule defines as a gate failure
("a line without a live backlog reference is a gate failure").

**Tracked as:** #97
**OpenSpec change:** `openspec/changes/unwired-detector-test-double-exemption/`

**Status: CLOSED (#97).** These are not dead code. They are test doubles that live in
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

**Status: CLOSED.** 2026-10-05, by `openspec/changes/androidapp-debug-lint-policy` and
ADR `2026-10-05-debug-source-set-is-linted.md`. `src/debug` is now scanned; 9 findings
were auto-corrected and 6 are baselined with a reason each. **The recorded split below was
wrong** — measurement found 15 findings, not 16, and 6 to baseline rather than 4 — which is
why the change's first task was to measure before changing anything. The policy was not:
lint it, rather than exempt the source set, because "not linted" and "linted with
everything suppressed" are the same hiding place with a different badge.

The original entry:

> **Status:** OPEN — a decision, not a mechanical fix. Linting it produces 16 findings, and
> every one is in `DebugSeedActivity.kt`:
- `NoRunBlocking` (1) and `NoDirectClockSystem` (4) — a one-shot debug seeder blocks a
  background thread and stamps seed timestamps; both are the point of the tool
- `TooGenericExceptionCaught` (1) — a seeding tool that must not crash the app
- `BlankLineBetweenWhenConditions` (5), `ClassSignature` (2), and 3 more formatting
  findings, which are auto-correctable

**Now resolved.** Half of these needed a suppression, because the rules are correct for
production and wrong for a debug seeder — and each suppression says so in terms of the tool
rather than of debug code, so a seventh finding has to be a decision instead of joining the
pile. The decision generalises to every future
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

**Status: CLOSED 2026-10-06.** The coupling was designed around rather than waited
out. `ci.yml` now runs four jobs — `static`, `tests`, `android`, `ci-gate` — and
`tests` deliberately stays a single job holding the run stamp, both count/coverage
floors, the kover report and the flake comparison, so none of the three couplings
this entry describes can be broken by the split. See
`2026-10-06-ci-single-gate-registry-and-leaf-split.md`.

**What is still open** is the narrower question this entry's "try next" list asked:
whether `tests` itself can be split further. That is not needed for correctness and
is not tracked as a defect. See the `tests-job-still-a-monolith` entry.

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

**Status: OPEN**

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

**Status: OPEN**

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

**Status: CLOSED (65 closed)** — the tracked issue is closed,
so this finding is no longer an open commitment.

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

**Status: OPEN**

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

**Status: OPEN**

**Tracked as:** #135

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
the general lesson is **not** enforced: a rule class with no test can still be
added, and nothing notices.

**Corrected 2026-10-05.** The "9 of the 18 rule classes have no unit test at
all" in this entry is **stale and was measured wrong**. It counted dedicated
`XxxRuleTest.kt` files. `RuleFiresSmokeTest.kt` gives a positive control to every
rule that had no dedicated file — all 20 rules are covered, and
`:detekt-rules:test` is 146 green tests. The counting mistake is itself the
subject of #135: a check that matches less than intended is indistinguishable
from a check with nothing to match.

**Try next (unchanged, now the only part that is open):** add a check that
**fails when a rule class has no positive control**, and prove that check can
fail by deleting one. Do not re-implement it as "grep for a test file named
after the rule" — that is the check that produced the wrong number above.
A rule is only as trustworthy as the test that proves it fires.

---

## two-rulesets-were-vacuous-52-violations-were-invisible

**Found in:** 2026-10-04 verifiability change, the moment
`NoDirectDispatchers` and `NoEmptyOnClickLambda` were made able to fire.
`find-unwired-surfaces` and the detekt report both said "0 findings" for rules
whose KDoc promised coverage; neither was true.

**Status: OPEN**

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

## adr-frontmatter-drift-is-unenforced

**Found in:** 2026-10-04 verifiability change, while making
`docs-audit.yml` steps real.

**Status: OPEN**

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

## each-module-needs-a-named-gate-owner

**Found in:** 2026-10-04, immediately after closing the rule-verifiability
inventory. Asked "what is still unwired?" and found `:androidApp:detekt`.

**Status: OPEN**

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

**Status: OPEN**

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

## direct-dispatchers-mostly-sit-in-platform-ports-where-they-are-correct

**Found in:** 2026-10-04, when `NoDirectDispatchers` was made able to fire. It
reported 21 sites and the plan proposed constructor-injecting a
`CoroutineDispatcher` into each, with a Koin change per module.

**Status: OPEN**

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

**Status: OPEN**

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

**Status: OPEN**

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

**Status: OPEN**

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

## autocorrect-touches-files-outside-the-change

**Found in:** 2026-10-04, during the B2 `TaskDetailDeps` split, immediately
after adding the `check-rule-intent.py` gate.

**Status: OPEN**

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

**Status: OPEN**

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

## a-kiwi-case-named-after-a-file-hides-the-class-inside-it

**Found in:** 2026-10-05, while fixing the tag gate above.

**Status: OPEN**

**Tracked as:** #147

**Symptom.** `sync.py` derives a Kiwi case id from the *file path*:
`NoopSubscriptionProviderTest.kt` becomes the case `NoopSubscriptionProviderTest`, but the
class the file declares is `PurchaseStateTest`. Three files are affected:

| File | Class(es) actually declared |
|---|---|
| `core/billing/NoopSubscriptionProviderTest.kt` | `PurchaseStateTest` |
| `core/sync/TaskToJsonProbeTest.kt` | `TaskSyncSerializationTest` |
| `core/observability/CrashReportingTest.kt` | 7 test classes |

JUnit reports results by class name, so these cases can never be matched to a run: the
binding is by FQN, and the FQN Kiwi recorded does not exist as a class. They sit in
"never run" forever, indistinguishable from a real gap.

**Partial fix applied 2026-10-05.** The file is no longer dropped entirely — the previous
filter looked for a test member *under the class named like the file*, found none, and
excluded the file, losing a genuine test. The scan now accepts a file when **any**
concrete test class is declared in it (260 cases instead of 259), and reads the tag from
the class that matches. The consequence is a visible `junit_tag=untagged` on a case whose
name does not match any class — an honest signal rather than a silent miss.

**Try next.** Make the case id follow the *class*, not the file. That is the correct
direction, and it is deliberately not done in the same change: `source_path` is the
idempotency key for `sync.py --plan`, so re-keying orphans the 259 cases already in a
local stand and needs a migration. Options, cheapest first:

1. Re-key to `source_path#ClassName` and let `gaps.py` report the old cases as orphans
   (that report already exists and deliberately does not delete).
2. Keep the file-based id, and add a `declared_class` property so a case records which
   class it stands for — no migration, and the mismatch becomes queryable.

Option 2 is the smaller change and loses nothing today; option 1 is the destination.
A third possibility — renaming the three files to match their classes — was rejected: it
touches unrelated source to make a database field line up, and Kotlin does not require
the two to agree.


## the-dead-refs-gate-was-green-locally-and-red-in-ci

**Status: RESOLVED (2026-10-04).** Fixed on the verifiability branch; the
regression test is `scripts/tests/test_check_doc-dead-refs.py` (7 tests).

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

---

## a-stale-detekt-classpath-makes-the-gate-green-with-no-custom-rules-running

**Found in:** 2026-10-05, three times in one session, while adding the two rules that became
`NoUnreportedFailurePathRule` and `AppErrorCodeRule`.

**Status: OPEN**

**Tracked as:** #136
**OpenSpec change:** `openspec/changes/detekt-tooling-honesty/`

**Symptom.** `:shared:detekt` reported success while no custom rule was running. Once as a hard
failure — `ServiceConfigurationError: Provider …NoUnreportedFailurePathProvider not found`, for a
provider that `unzip -p` showed in the jar's services file, that `javap` resolved, and that a
standalone `ServiceLoader` probe over that exact jar loaded alongside all eighteen others. Twice as
a **false pass**, the second time after `./gw :shared:detektBaseline` had left a baseline with
zero custom-rule entries and `:shared:detekt` then reported a clean tree.

**Ruled out.** Not the config: `config/detekt/detekt.yml` had the blocks. Not the services file:
it listed the provider. Not the jar: verified three ways. Not the configuration cache:
`--no-configuration-cache` did not help.

**Workaround, and it is not a fix.** `./gw --stop`. Every time.

**Why it is here and not just in the issue.** The lesson generalises past detekt, and this
repository now has several instances of it: `2026-09-26-pr-0-3-retro.md` records a baseline
regenerating "without all rules registered", #58 records the same task being cached, and this
records the rules not being loaded at all. Three records of the same class from three directions.
The general form — *a measurement that cannot be distinguished from its own failure* — is what the
proposed guard targets: plant a violation, assert it is reported, run that before trusting a green.

**Try next.** Reproduce deliberately: `./gw --stop`, add one rule, re-run without `--stop`. Then
find where the plugin classpath is cached. `--no-configuration-cache` already fails, which points
at the daemon's classloader or a Gradle transform keyed on a stale hash — dev.detekt 2.0.0-alpha.3
builds its plugin classloader in the worker.

---

## detektbaseline-drops-every-custom-rule-entry

**Found in:** 2026-10-05, immediately after the entry count of
`config/detekt/baseline-shared.xml` fell from 357 to 338 without anyone deleting an entry.

**Status: OPEN**

**Tracked as:** #137
**OpenSpec change:** `openspec/changes/detekt-tooling-honesty/`

**Symptom.** `./gw :shared:detektBaseline` runs **without the custom rule set**. Regenerating
silently removes every custom-rule entry; the surviving file contains built-in rules only. The
`git diff` shows only removals, and nothing else reports it.

**Why it is separate from #58.** That entry is about entries that never leave — the baseline as a
high-water mark. This one is about entries that leave — the baseline as a lossy record. They share
a file and an incantation, so whichever lands first must consider the other or it will reintroduce
it.

**The part that actually matters.** The lost entries are recoverable by hand. The dangerous part is
that **regenerating the baseline is a way to turn the custom rules off and have the gate agree with
you.** Someone clearing debt, or absorbing a new rule's findings, silently converts `just lint` from
"the project's rules ran" to "only the built-in rules ran".

**Ruled out.** Not a path problem: `:shared:detekt` in the *same daemon* still caught a planted
`runCatching` violation, so the two tasks are demonstrably not sharing a plugin classpath. Not a
stale daemon: reproduced after `./gw --stop`.

**Try next.** `shared/build.gradle.kts` sets `baseline = …` inside the `detekt { }` extension and
declares `detektPlugins(project(":detekt-rules"))` in a separate `dependencies { }`. Confirm
whether the `detektBaseline` task family picks up the `detektPlugins` dependency at all, by
bisecting that file.

**Until then.** Never run `detektBaseline` without `git diff` on the baseline immediately after,
and treat a *shrinking* custom-rule section as a red flag rather than progress. `just cr` does not
run `detektBaseline`, so the gate itself is safe; this bites a human at a keyboard.

---

## a-koin-definition-body-stays-uncovered-after-being-resolved

**Found in:** 2026-10-05, when the coverage ratchet charged this work a 0.40pp drop in
`feature/calendar_sync` and the obvious fix did not fix it.

**Status: OPEN**

**Tracked as:** #138
**OpenSpec change:** `openspec/changes/detekt-tooling-honesty/`

**Symptom.** `CalendarSyncDiModuleKt` reads 4/18 lines covered. The four covered lines are the
`module { }` block; the fourteen uncovered ones are the bodies of the `single { … }` and
`viewModel { … }` definitions. `KoinGraphValidationTest` now resolves both definitions for real,
the test passes, and the number does not move.

**Three hypotheses, in order of how cheap they are to rule out.** (a) `jvmTest` was served
`FROM-CACHE` or `UP-TO-DATE` after `just cr`'s kover wipe, so the class contributed no fresh
coverage data — which would make the number a property of the measurement rather than of the code.
(b) Line attribution: the lambda's lines may not reach the file-level LINE counter. (c) The
resolution is satisfied without executing the body.

**Why it is here.** A coverage number that does not describe execution is worse than no number,
because somebody will make a decision on it. In this case the decision was whether a floor drop was
acceptable, and the floor was adopted to the measured value with a `note` in
`config/coverage-ratchet.json` recording that the movement is unexplained. **That note should not
outlive the explanation** — whoever closes this should delete the note in the same commit.

**Related, and older.** #59 records the Gradle test cache silently skipping the whole suite, one
level up and with worse consequences. `gradle-test-cache-silently-skips-the-suite` in this file is
the same finding from the previous session.

**Try next.** Rule out (a) first, with `--rerun-tasks` on the single class, because it invalidates
the other two. If it is genuinely 4/18 after a forced re-run, plant a side effect inside the
`single { }` body and assert it happened when the definition is resolved.

---

## a-viewmodel-scope-and-its-reporter-can-be-different-ports

**Status: CLOSED** (2026-10-05) — fixed, and the count above was wrong twice. See the correction.

**Tracked as:** [#143](https://github.com/gazon1/sing/issues/143) ·
`openspec/changes/scope-reporter-agreement/`

**Found in:** 2026-10-05, the sweep that followed the crash-reporting migration — asking what
structural gap the migration left, rather than what it fixed.

**Symptom.** `MviViewModel`'s default scope is derived from the ViewModel's own reporting port,
so the common case cannot diverge. The default is bypassed when a component supplies a scope
explicitly, and then the port `catchTo` reports to and the port the scope's failure handler
reports to are two independent arguments that nothing correlates.

**The count was wrong, and the rule found the difference.** The first count said two sites. It was
four, and the two extra ones were found by the rule written to close this, not by reading:

- `SearchViewModel` — classified here as "consistent by construction" because its *secondary*
  constructor derives the scope from the reporter. The secondary is what Koin resolves, so the
  binding is fine. The **primary** still required a scope, so any caller reaching it directly
  chose one independently of the reporter. A reviewer reading only the binding — which is what I
  did — sees nothing wrong.
- `SettingsViewModel` — constructor correct, and its **binding** replaced the derivation with a
  graph-supplied `scope = get()`. Correct class, divergent wiring, invisible to a constructor-only
  check.

Both are the shape a class looks safe in. The general lesson is the one #135 already records about
matching less than intended: a check that covers the case you happened to look at is
indistinguishable from one that covers the case you did not.

**Fix.** Option (a) from the issue — the scope default moved onto the primary constructor in both
ViewModels, and the three bindings stopped passing one. One destination by construction, nothing to
correlate. The `factory { reportingScope(get()) }` in `CoreDiModule` stays: `SyncRunner` and
`SyncRepositoryImpl` hold no reporter and legitimately take a scope from the graph.

**The rule that keeps it.** `NoDivergentScopeAndReporter`, third rule in
`no-unreported-failure-path`, two findings — the constructor's scope default, and a binding passing
both arguments. The second is not redundant: it is what caught `SettingsViewModel`, whose
constructor was already correct. Proven on the real tree by planting the old `scope = get()` into
the settings binding and confirming `:shared:detekt` went red, then green again on revert.

**Try next.** Nothing. Recorded because the *count* is the reusable part, and because the next
person auditing this class of gap will be tempted to stop at the binding.

---

## version-gate-breadcrumb-has-no-test

**Status: CLOSED** (2026-10-05) — the missing test found a real defect on its first run.

**Tracked as:** [#144](https://github.com/gazon1/sing/issues/144) ·
`openspec/changes/failure-visibility/` (REQ-3)

**Found in:** 2026-10-05, re-reading `openspec/changes/failure-visibility/tasks.md` against the tree
while deciding whether that change could be archived.

**Symptom.** `a068b432` added the fail-open breadcrumb to `AppVersionGateViewModel` — when a
remote-config read throws, the gate admits the user on defaults and leaves a record saying it did.
The code shipped. The test did not, and `tasks.md:12` says so in its own words: *"a breadcrumb
asserted only by reading the code is not a breadcrumb."*

**Why it matters.** The report and the breadcrumb are two separate calls emitted from one `catchTo`
block. A regression that drops the report reinstates #126; one that drops the breadcrumb makes the
gate fail open invisibly, which is the original defect wearing the fix's clothes; one that swaps the
keys leaves both halves present but no longer greppable together.

**Already ruled out.** Not a missing harness — `AppVersionGateViewModelTest` had 7 cases and already
drove the failure path. This was an addition to a suite that existed.

**What the test found.** A recording port exposes the ordered log, not just the two lists, and that
is the whole point: the backend attaches the breadcrumb buffer to a report **as it stands when the
report is made**. The shipped code wrote the bypass from the funnel's error handler, which runs
*after* the report — so the record rode on the next event, and during a config outage there is no
next event. The gate failing open was exactly as invisible as it had been before the record was
added; the record was merely attached to the wrong event.

Two tests failed on the first run, one per route. The returned-failure route (`refresh()`) had the
same defect and had been hand-rolled around it, because a returned failure never reaches the
funnel's error arm — the ViewModel reported and breadcrumbmed it itself.

**Fix.** An explicit pre-report step in the funnel, defaulted to empty so every other call site keeps
"an error handler's breadcrumb cannot jump ahead of its report". The returned-failure route now goes
through the same funnel, so the two cannot drift apart again. 5 new cases, 12 total.

**Try next.** Nothing. Kept for the reusable half: a fake that records *one ordered log* rather than
two lists, because a reversed pair and a correct pair produce identical two lists.

---

## the-generated-rule-inventory-enforces-nothing

**Status: CLOSED** (2026-10-05) — `--check` now fails on a rule with no positive control.

**Tracked as:** [#145](https://github.com/gazon1/sing/issues/145) ·
`openspec/changes/detekt-tooling-honesty/`

**Found in:** 2026-10-05, running `python3 scripts/gen-detekt-rule-table.py --check` to confirm the
inventory still matched the source. It reported `OK — 25 rules, table matches source`.

**Symptom.** That `OK` proved the table had not drifted. It said nothing about the `Test` column, and
it passed just as happily when a rule had no positive control and the cell was empty. The column was
added so an untested rule is *visible* in the file a rule author opens. Visible is not enforced.

**Already ruled out.** Not a gap in the inventory — it is generated, so it cannot go stale, and it
makes the "which rule lacks a test" question answerable without a grep. #135's correction about
matching is what the enforcement had to honour: a dedicated-filename-only check reports five untested
rules and should report zero, because `RuleFiresSmokeTest` covers them. So both shapes count as
tested, and the column says which one it found.

**Fix.** `--check` now fails and names the rules. Proven by deleting the new rule's dedicated test
class and confirming red — which took two runs, because the first failed on *staleness* instead: the
table had to be regenerated before the empty `Test` cell was visible to the check. Worth recording,
because a sabotage test that fails for the adjacent reason looks like a passing one.

`just cr` now also refuses to compare floors against a run in which the test tasks did not execute.

---

## the-ratchet-wipes-the-evidence-it-measures

**Status: CLOSED** (2026-10-05) — the ratchet now refuses to report a floor from a run that
executed nothing. The underlying anomaly is #138's and stays open.

**Tracked as:** [#146](https://github.com/gazon1/sing/issues/146) ·
`openspec/changes/detekt-tooling-honesty/`

**Found in:** 2026-10-05, while reasoning about #138's leading hypothesis — that `jvmTest` came back
`FROM-CACHE` after `just cr`'s kover wipe.

**Symptom.** `coverage-ratchet` wipes every module's `kover` directory and then runs `koverReport`.
Wiping stale `.bin` is right. But it does not invalidate the *test task*, and `:shared:jvmTest` can
be served `UP-TO-DATE` — re-executing nothing, contributing no fresh `.bin`, and producing a thinner
report than the truth. **A measurement gate that deletes the evidence it is about to measure can be
right for the wrong reason.**

**Why it is not just #138.** #138 asks why one file shows 4/18 and names the cache hypothesis as the
cheapest to rule out. This is the finding that the ratchet can *manufacture* the condition it is
investigating, and it applies to all nine floors. The live part: the `feature/calendar_sync` floor
was adopted to the measured 23.54% with a note saying it is a loan against #138 — if the measurement
came from a cache-served run, the floor was adopted from a number the code never produced.

**Already ruled out.** Not a claim that any current floor is wrong. The nine floors all *rose* when
re-baselined, which is the right direction; the point is that a cache-served floor would have looked
identical in the same run.

**Fix.** `scripts/check-coverage-measurement.py`, wired between the Gradle run and the ratchet
comparison. The recipe captures the build log and reads it back: if a test task in that log was
`UP-TO-DATE`, `FROM-CACHE` or `NO-SOURCE`, or if **no test task appears at all**, the ratchet refuses
to compare floors. 25 self-tests, no Gradle.

**The first version was wrong in both directions, and the run on the real tree is what showed it.**
`.*[Tt]est$` matches AGP's resource-processing tasks — `convertXmlValueResourcesForJvmTest`,
`generateResourceAccessorsForAndroidHostTest`, `javaPreCompileDebugUnitTest` and five more — none of
which runs a test. On the first real `just cr` it reported **19 findings, one of them real**. The
match is now anchored (`test`, `jvmTest`, `test<Variant>UnitTest`).

The same run showed `NO-SOURCE` was also wrong as a *failure*. `:androidApp:testDebugUnitTest
NO-SOURCE` is a module with no unit tests, and failing the whole ratchet over it blocks everyone
forever over a fact the coverage report already states honestly as 0%. It is a warning now. The
distinction that survives: a **cached** task was in the graph, should have contributed, and did
not — that is #146; a task with **no sources** never had anything to contribute.

This is the repository's own lesson applied to my own work, and it is worth stating plainly: a gate
that fires on correct code is the same failure as one that fires on nothing, wearing the opposite
mask. The fixture tests all passed while the gate was unusable in practice — the log I had
imagined was tidier than the log Gradle prints. **The gate was proven on a real run, not on the
examples I wrote for it.**

**It took three passes against real logs, and each pass found a different wrong answer:**

1. `.*[Tt]est$` matched AGP's resource tasks — 19 findings, 1 real.
2. `NO-SOURCE` as a failure blocked on `:androidApp:testDebugUnitTest`, a module with no unit tests.
   And `UP-TO-DATE` on a *sibling* task in the same module is the same fact spelled differently:
   `:androidApp:test` is `UP-TO-DATE` for the same reason `testDebugUnitTest` is `NO-SOURCE`. The
   gate judged a module that has no tests as though it had tests that failed to run.
3. The "module has no tests" exemption needed a second condition, or it becomes a standing pass for
   any module with a `NO-SOURCE` sibling. The condition is that no task in the module may be
   `FROM-CACHE`: a cached task *proves* the module has tests, because it ran once and produced
   outputs worth restoring. `UP-TO-DATE` alone is ambiguous and reads as benign only next to a
   `NO-SOURCE` sibling.

The final rule is scoped per module rather than per task for that reason. 29 self-tests, and the
two that matter most are the real logs: a `RERUN=1` run passes, and a cached run fails.

The override is also honest about what it did: `--allow-cached` prints *passed with warnings*, not
*the test tasks executed*. The first version printed the latter, which is false and is the same lie
one level up.

**Issue #86 then reproduced itself, one line away.** The recipe documents `just cr RERUN=1` — and
that form does not assign in this environment; the token arrives as a positional argument, the flag
is silently absent, and the run still succeeds while measuring less. Which is precisely the failure
this change exists to prevent, delivered by the very syntax used to prevent it. The recipe now
rejects any `NAME=value` argument with a message naming the environment form, and both are written
`RERUN=1 just cr`. It is worth noting how cheap this was to miss: the run *looked* fine.

The recipe also learned that a literal double-brace pair inside a comment is a `just` parse error,
because `just` interpolates it. That is a two-minute trap with an error message that points at the
comment rather than the rule.

**Deliberately not chosen:** making the wipe invalidate the test task. Forcing `--rerun-tasks` on
every ratchet run would cost a full recompile, and a gate that is slow enough to be skipped is a gate
that gets skipped. Refusing to report is the cheaper half of the same honesty.

**Try next.** #138, with `just cr RERUN=1`. The gate now makes the cache case loud; whether it was
the cause is still #138's to answer, and the `feature/calendar_sync` note must be deleted or
re-adopted in the same commit, as that note already requires.

---

## scenario-result-missing-for-a-claiming-commit-is-not-a-failure

**Status:** OPEN

**Tracked as:** #149
**OpenSpec change:** `openspec/changes/scenario-results-are-authoritative-in-ci/`

**Found in:** the scenario traceability layer
(`2026-10-05-scenario-test-cases-in-kiwi.md`), while wiring its result matrix into
CI. Not a regression — a requirement that was never written down.

**Why it is deceptive:** everything about this looks finished. The layer has a
result matrix, four outcomes, a green gate and a committed coverage matrix. The
matrix even renders the outcome that matters — a claimed target with no result at
this commit — and *nothing acts on it*. A reader scanning the table cannot tell
whether a cell means "verified" or "nobody looked".

This is the same failure class as `-Ptest.tags=fast,slow` selecting 16 of 218 test
classes for months, and as the three faces already pinned in
`openspec/specs/test-execution-integrity`. It is the fifth.

**Already ruled out:** a floor in `config/docs/kiwi-gaps-baseline.txt` is the
wrong instrument. Polarity differs — for a class, *never run* is the failure; for
a scenario, *never run* is normal (it may be new, or its target may be a different
CI job) and *missing from a commit that claims it* is the failure.

**Try first:** pin what "this build claims the target" means before writing the
check, because the two requirements are unimplementable until that is decided.
Emitting "attempted, not merely present" per target is the enabling change; the
current signal cannot tell "ran and produced nothing" from "was never run".

## maestro-results-are-produced-and-discarded-in-ci

**Status:** OPEN

**Tracked as:** #150, #151
**OpenSpec change:** `openspec/changes/scenario-results-are-authoritative-in-ci/`

**Found in:** the same layer, while asking why the Android column of the result
matrix is permanently empty.

**Why it is deceptive:** the Android flows *do* run in CI, and they *do* produce
the JUnit XML the normaliser reads — `scripts/run-maestro.sh` now passes
`--format=JUNIT --output=build/maestro-results`. The output is then discarded:
the only artifact that job uploads is Maestro's own debug report, and only
`if: failure()`. Twenty of 59 flows carry the `smoke` tag, so the scenario flow is
among them.

So the Android column of the result matrix is not a coverage hole. It is a hole
in the plumbing, and it is indistinguishable from the former when you look at the
table.

**Already ruled out:** simply uploading the directory with `if: always()`. The
flows run in a *different workflow* from the job that builds the matrix, so making
the data available does not put it in the matrix, and cross-workflow artifact
handoff is its own problem. The honest minimum is for the matrix header to state
which targets this build covered.

**Try first:** run one scenario flow on a host with a working emulator and check
whether the result reaches the matrix at all. That single run settles the join
(`file` attribute, its base, whether the sheet's confirm is reachable) and every
remaining question here is downstream of the answer. Note that no flow has yet
produced a result in this environment, so the Android tier is unverified end to
end — the reporter emitting a `file` field is confirmed in the Maestro 2.10.0 jar,
but the join is inference until a real run exercises it.

## untested-has-two-answers-per-class-and-per-scenario

**Status:** OPEN

**Tracked as:** #157

**Found in:** the same layer, while writing its README and skill and noticing
that the new matrix answers the question the old one already answered.

**Root cause:** `gaps.py` measures *never-run per test class* over the
`Automated/*` plans; the scenario matrix measures *claimed targets per user
scenario* over the `Scenarios` plan. Both are correct about different things, and
both are presented as authoritative. A reader arriving cold has no rule for which
to trust, and the two will drift in vocabulary — "hole" means a claimed target
with no automation in one layer and something closer to "never run" in the other.

**Already ruled out:** merging them. The polarity is opposite, so a single
instrument cannot express both: for a class, *never run* is the failure; for a
scenario, *never run* is normal and *missing from a claiming commit* is the
failure. The per-class floor stays correct for what it measures.

**Try first:** write the decision down before adding more scenarios. Growing the
layer without settling this is how two authoritative-looking answers to one
question get created. The decision owed: does the scenario layer eventually
replace the legacy per-class reporting, with Kover keeping code coverage and
`Automated/*` retired?

---

## sync-auth-repository-is-still-a-stub

**Status:** RESOLVED (2026-10-04). Phase 8 replaced the stub; `AuthRepositoryTest` (20)
covers it. Kept in the file rather than deleted because the shape of the defect is
worth remembering, and because the section below it is the reason it was tracked at
all.

The class was bound in the DI graph and its methods validated their arguments and
did nothing else. It was not dead code and the dead-symbol detector did not flag it:
a bound class has a production call site. That is exactly why it needed an entry of
its own — **the gate that would otherwise have caught it passed.**

`SupabaseConfigResolver` had sat in the same table while wired by nothing, and the
detector was right to flag it. But the moment `SupabaseClientProvider` appeared and
started resolving configurations, the flag cleared, because the binding *is* a
production reference. A binding proves that something can reach the class; it says
nothing about whether the class does anything. Reachability is not behaviour, and a
gate that measures reachability cannot tell a wired class from a working one.

The general form: a DI container makes a whole category of stub invisible to
reachability checks, because a stub is bound and called and simply returns. Catching
those needs a different signal — a test asserting the *effect* — not a better
reachability rule.

## an-archived-gate-repair-left-the-step-it-was-fixing-broken

**Status: OPEN**

**Tracked as:** [#174](https://github.com/gazon1/sing/issues/174)

**Found in:** 2026-10-05, merging 11 upstream commits and running the gate afterwards.

**Symptom.** `local-gate-repair` was archived on 2026-10-05 as complete. It was about
`just gate` being unable to complete: `coverage-ratchet` invoked a task that did not exist, so
the gate died at **step 3/4** before reaching the flows. That call was fixed and the gate now
reaches step 4 — and step 4 dies with `justfile does not contain recipe 'gate-maestro'`.

**Why it is the same class as #58 and #137.** A change archived as done is a claim that the
defect it names is gone. Here the claim and the gate disagree, and the only reason to notice is
to run the gate. The archived `tasks.md` lists the `just cr` fix and the two-module detekt list;
it never mentions the fourth step, so the gap was not introduced by the archive — the archive
simply inherited it. **The step after the one that was fixed was never in the list.**

**Already ruled out.** Not a resolution gap: the recipe `gate-maestro` exists, in this module,
and `just --list` shows it. The `gate` recipe calls it by bare name, and a bare name does not
resolve across a module boundary in `just` — the same property that made `just cr` fail, fixed
in this change and missed here.

**Try next.** One line, and then the change that was already archived has actually landed.
Whether an archived change can be reopened, or whether the fix belongs in a new change, is a
question about the archive's policy — it does not belong in an archived file, and it is why
this is filed rather than patched into the archive.

---

## an-arch-test-lives-in-the-module-whose-conventions-it-does-not-own

**Status: OPEN**

**Tracked as:** [#186](https://github.com/gazon1/sing/issues/186)

**Found in:** 2026-10-05, closing #153 — the duplicated-test-helpers issue whose
premise turned out to be false, so the real defect had to be looked for.

**Situation.** `ViewModelTestCoverageTest` lives in `:shared` and reads
`:desktopApp`'s test sources through `System.getProperty("desktopAppJvmTest.root")`,
passed from `shared/build.gradle.kts`. Unlike the test that was moved out in
`08f6cd1a`, this one is *legitimate*: it matches a production ViewModel against
every test class that mentions it, so it genuinely aggregates across modules and a
relative path is not available to it.

**What is left.** The property is a string path between modules that neither
Gradle nor the compiler knows about. A rename breaks it at runtime with
`desktopAppJvmTest.root is not set`, and the comment naming the one remaining
reader is the only thing keeping that honest. `check-test-task-inputs.py` covers
the *staleness* half of the hazard (the tree is a declared task input, so a
desktopApp edit invalidates `:shared:jvmTest`) — it does not cover the *naming*
half.

**Already ruled out.** `testFixtures` is not the answer, for the same reason it was
not the answer in #153: this is a file scan, not a shared declaration. Making the
property a Gradle-projected value would be the alternative, and it is real work
for one remaining reader.

**Try next:** only if a second cross-module scan appears — which #154's tag
unification would likely produce. Then a small shared scan-root provider in
`jvmTestFixtures` pays for itself. With one caller it is ceremony.

---

## dark-calendar-palette-is-hand-authored-against-a-generated-scheme

**Status: OPEN**

**Tracked as:** [#197](https://github.com/gazon1/sing/issues/197)

**OpenSpec change:** `openspec/changes/calendar-palette-follows-the-theme/`
(capability `app-theming`, REQ-THEME-001/002/003)

**Found in:** 2026-10-05, while replacing `SingularityTheme`'s hand-written
palettes with MaterialKolor seed generation (ADR
`2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag`).

**Situation.** `CalendarPalette` has sixteen fields, and
`darkCalendarPalette` fills all sixteen with hex literals — `0xFF0B1220`
background, `0xFF101A2C` surface, `0xFF1C2740` grid lines, `0xFFE7ECF5` text.
It was tuned against a *previous* dark theme (`0xFF121212` / `0xFF1E1E1E`),
and the light twin already derives from `MaterialTheme.colorScheme`. The two
halves of the same data class now have different provenance, and neither is
checked against the seed-generated scheme the app actually ships.

Three of the sixteen fields — `taskSelected`, `todayBadge`, `nowIndicator`,
`accent` — are set to `accent.color`, the **raw seed**, in both palettes. The
seed is a vivid user-picked hue; it is not a role colour. With
`SingularityAccents.Yellow` (`0xFFFFEB3B`) those are near-illegible as a
surface on the light background. MaterialKolor makes the fix free
(`scheme.primary` is tone-adjusted and contrast-checked against `onPrimary`),
but it changes the calendar's look, so it is a design call rather than a bug
fix and was not made unilaterally.

**Why it was not fixed here.** The dark palette encodes a deliberate aesthetic
("tuned for the deep navy/blue-grey aesthetic of the reference screenshots").
Re-deriving it is a visual-design decision with no single correct answer, and
this change was scoped to making the seed actually drive the theme. Bundling a
redesign into a bug fix is how a review loses the ability to see the bug fix.

**Checks already performed.** Confirmed all sixteen fields are literals in the
dark branch and scheme-derived in the light branch; confirmed the seed-derived
slots are identical expressions in both branches; confirmed no test asserts any
`CalendarPalette` value, so there is no behavioural guard to update.

**Try next:** re-derive `darkCalendarPalette` from `MaterialTheme.colorScheme`
exactly as `lightCalendarPalette` already is, then replace the three raw-seed
slots with `scheme.primary` / `onPrimary`. Do it as its own change with
before/after screenshots on all nine accents, and check the yellow and orange
accents first — they are the ones that fail contrast. Consider collapsing
`CalendarPalette` itself: if both branches end up derived from the scheme, the
sixteen-field data class may be redundant with `ColorScheme` and the screen
could read scheme roles directly.

---

## tasks-feature-pins-its-own-dark-palette-and-ignores-the-theme-entirely

**Status: OPEN**

**Tracked as:** [#198](https://github.com/gazon1/sing/issues/198)

**OpenSpec change:** `openspec/changes/tasks-tokens-follows-the-theme/`
(capability `app-theming`, REQ-THEME-004/005/006)

**Status update 2026-10-05: PARTIALLY CLOSED.** The two token objects are gone
and all 27 literals now live in `theme/TaskSemanticColors.kt`; 19 consumer files
read the active scheme. What remains is not this entry but two follow-ups found
while doing it: #199 (the other four screens that carry the same defect, which
this entry's "counted 47 literals" figure under-reported because it was scoped to
`feature/*/presentation` and three of those files sit outside that segment), and
the duplicate `priorityColor` noted below.

**Found in:** 2026-10-05, immediately after the MaterialKolor seed-palette
change (ADR `2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag`),
while auditing what else hardcodes colour now that the app *has* a generated
palette to read from.

**Situation.** The Tasks feature — the core of the app — has two token objects
that are **dark-only by construction**, with no light branch anywhere:

| Object | File | Literals | Anchor |
|---|---|---|---|
| `TaskColors` | `feature/tasks/presentation/theme/TaskTheme.kt` | 12 | `Background = 0xFF0F1115`, `TextPrimary = 0xFFE2E4E9` |
| `TaskListColors` | `feature/tasks/presentation/theme/TaskListTokens.kt` | 15 | `Background = 0xFF0B0E14`, `Surface = 0xFF161A22` |

Both are `object`s of `val`s, not functions of a `ColorScheme`, so they cannot
respond to anything at runtime. They are consumed by **11 production files**:
the task list, the whole detail/editor surface (`TaskEditorContent`,
`TaskTitleRow`, `TaskEditorDueDateRow`, `TaskEditorEstimateRow`,
`TaskEditorPriorityRow`, `TaskDescriptionField`, `TaskAttributeCard`,
`LinkedBacklinksCard`, `LogbookSection`), `PriorityMeta`, and
`TimeTrackingSection` in the timetracking feature.

Consequences, all pre-existing:

1. **A light-theme user gets a dark task screen.** `darkTheme` defaults to
   `false`, so this is the shipped default, on the app's primary screen.
2. **The accent picker does nothing here.** `AccentBlue = 0xFF4A90E2` is a
   literal, so choosing Pink leaves the task editor blue. This is the same
   defect class the seed-palette change just fixed for the app at large.
3. The KDoc on `TaskListTokens.kt` claims it gives "один источник правды при
   смене темы" — one source of truth *when the theme changes*. It is the
   opposite: a single hardcoded truth that cannot change with the theme.

**Why it was not fixed here.** Thirteen token files' worth of colour, across
the most-used surface in the app, is a redesign with nine accents × two modes
to review by eye — and there is no screenshot baseline in this repository to
catch a mistake. Attempting it inside a change that was scoped to the theme
root would also have made that change unreviewable. This needs visual review
per accent, which is a human-in-the-loop task.

**Checks already performed.** Counted every `Color(0x…)` literal under
`feature/*/presentation` (47 total, in 5 files: the two token objects, the
14-literal `CalendarPalette`, `PriorityChip`'s four priority hues, and two in
`NotesListScreen`). Confirmed neither token object is a function or reads
`MaterialTheme`/`LocalAccentColor`/`LocalIsDarkTheme`. Enumerated all 11
consumers. Confirmed there is no light-mode counterpart object.

**Try next:** convert both objects into functions of `MaterialTheme.colorScheme`
returning a small token data class, resolved inside a `CompositionLocalProvider`
at the screen root — the same shape `CalendarPalette` already uses. Keep the
four priority hues as literals: priority is a *semantic* scale, not a theme
role, and `PriorityChip`'s green/amber/red/pink is correct as data. Then verify
by eye across all nine accents in both modes, starting with the light theme on
the task list, because that is the largest visible delta in the app.

A Konsist rule now exists and is live in `ArchitectureTest`
(`colour literals live in theme files, not in feature code`), validated with a
negative control: a literal injected into a non-theme feature file makes it fail.
Its scope is the **whole `feature/` tree**, not `feature/*/presentation` — three
screens keep their composables directly under `feature/<x>/`, so the narrower
scope would have passed while 22 literals sat outside it. The allowlist that
keeps it green names six files; #199 tracks the four that still need converting.

---

## four-worktrees-hold-uncommitted-work

**Found in:** 2026-10-05, while assessing branch and worktree hygiene before
re-deriving the publication history. The proposed cleanup was "prune dead
worktrees and consolidate 84 branches"; measuring first showed that framing
was wrong.

**Status: OPEN**

**Tracked as:** #202

**Symptom:** 17 worktree directories exist, and four of them carry work that
exists nowhere else:

- `singularity-todo-business-01` — **an interactive rebase stopped mid-flight**
  (`UU docs/testing/coverage-matrix.md`, 7 changed files, 2 completed picks of 7
  pending). Its branch content is not reachable from `origin/main`.
- `singularity-todo-notes-body` — 50 changed files on
  `refactor/notes-canonical-body`.
- `singularity-todo-pallete` — 35 changed files on `feature/pallete`.
- `singularity-todo-test-scences` — 52 changed files on `feat/test-scences`.

Also: 42 local branches are unmerged into `origin/main`; 21 of those are checked
out in a worktree and 21 are not. A `git branch -D` sweep or a `git worktree
remove` without reading `git status` in each would destroy the four blocks above
and 21 unmerged branch tips.

**Already ruled out:** none of this is garbage. The rebase in `business-01` is
interrupted mid-sequence, not failed — the completed picks are recoverable with
`git rebase --continue`. The other three are ordinary in-progress work on
branches that were never merged.

**Try next:** decide per worktree, not per rule. `business-01` first: finish or
abort the rebase deliberately, because an interrupted rebase is also a trap for
the *next* person — `git status` in that directory reports a conflict rather than
the feature it looks like it is working on. Then triage the three dirty branches
by whether their work is superseded by `origin/main` or still wanted. Only after
that, prune: the 21 unmerged branches without worktrees are the cheap case, and
even they should be listed for a human first.

The generalisable lesson: "clean up the repository" is not a safe instruction to
hand to an agent, and neither is "there are 84 branches and 46 worktrees". Both
numbers invite a bulk delete, and the interesting content is in the four
directories that are not clean.

---

### Two things the conversion found that this entry did not

**1. Priority was three scales, not one.** `PriorityPalette` always documented
two coexisting palettes, and the divergence between them is deliberate — the
editor is a form, the list is a list, and the reds are tuned differently on
purpose. But `PriorityChip.kt` held a **third** set of four values
(`4CAF50` / `FF9800` / `F44336` / `E91E63`) that was never registered in the enum
and never documented, in a function also named `priorityColor` — shadowing the
canonical one in a sibling package with different values. The compiler will not
flag that and a reviewer will not notice; the unit test covers the list one only.
The chip's values are now preserved exactly as a documented third palette, and
its function is renamed `priorityChipColor`.

**2. The two palettes were near-duplicates.** `TaskColors.Surface` and
`TaskListColors.Surface` were both `0xFF161A22`; their backgrounds differed
(`0xFF0F1115` vs `0xFF0B0E14`) and their text primaries differed
(`0xFFE2E4E9` vs `0xFFF2F3F5`). Two hand-maintained copies of one palette, which
is why they had drifted. There was no need to unify them by hand — both now
resolve from the same scheme, so they are identical by construction.

---

## a-dependency-usage-gate-needs-resolved-artifacts-not-the-catalog

**Status: OPEN**

**Tracked as:** [#205](https://github.com/gazon1/sing/issues/205)

**Found in:** 2026-10-05, while trying to close the gap that let MaterialKolor
sit declared-but-unimported in the catalog and on the `commonMain` classpath
while nothing referenced it.

**Situation.** `scripts/find-unwired-surfaces.py` counts symbols. A declared
dependency has no symbol to count until something imports it, so a library that
is vendored, resolved onto the classpath and called by nobody passes every
current gate. The obvious gate — "every `[libraries]` entry has at least one
import" — was assumed cheap in planning and is not.

**Why not.** A Gradle module coordinate does not determine the import package.
Mapping `org.jetbrains.compose.material3:material3` to the package a source file
imports is not a prefix operation; that one is `androidx.compose.material3`.
Measured on this tree: a first two-segment heuristic over all 98 library entries
reports **45 of 98 as unused**, and every one of those 45 is used. The heuristic
is wrong in nearly half the catalog, and a gate with 46% false positives is worse
than no gate — it trains everyone to ignore it.

**Checks already performed.** Ran the heuristic across `shared/src`,
`androidApp/src`, `desktopApp/src` and `mcp-server/src`; counted the false
positives by hand for the whole result set. Confirmed the mapping is the problem,
not the source sets (the failing entries are widely used: `koin-core`,
`compose-material3`, `kotlinx-coroutines-core`, `coil-compose`).

**Try next:** stop mapping coordinates to packages and read the packages out of
the resolved artifacts instead. A Gradle task that prints, per source set, the
resolved files with their originating coordinates gives a coordinate → artifact
map; scanning each artifact's entries for its package roots yields the real
mapping, including the KMP case where one coordinate contributes several
artifacts. The gate then compares that map against the catalog and needs no
guessing. Budget it as a small Gradle task plus a Konsist check, not a grep.

**Do not** re-attempt the prefix heuristic and "just allowlist the false
positives": a 45-entry allowlist of libraries that are definitely used is
indistinguishable, to the next reader, from a 45-entry list of libraries that
genuinely are not.

---

## billing-entitlement-is-a-port-without-a-caller

**Found in:** 2026-10-05, while assessing what stands between the tree and the
first paid feature.

**Status: CLOSED — 2026-10-05. Both defects fixed; the port is still unconsumed, which is now a deliberate state rather than an oversight.** Closed by
`docs/decisions/2026-10-05-entitlement-is-scoped-by-sync-scope.md` and
`openspec/changes/entitlement-belongs-to-a-sync-scope/`.

The cast that denied paying customers is gone: `entitlement(scope): StateFlow<Entitlement>`
is read-only in its signature, so there is no mutable supertype to downcast to.
`hasAccount` is independent of subscription, `Unknown` is separate from a denial, and
`refresh` returns `Result` so an outage cannot present itself as a paywall. Entitlement is
scoped by `SyncScope` — the pair sync state belongs to — and the positive case that was
named-but-never-written now exists against a fake shaped like a real provider.

What remains true and is not a defect: no real billing provider is connected, so every
scope reads `Unknown`. That is the honest state of a project that has not shipped a paid
feature. Wiring the first paid feature is now a consumer question rather than a
port-correctness one.

**Tracked as:** #204

**Symptom:** `core/billing` is five files — `SubscriptionProvider`,
`SubscriptionInfo`, `PurchaseState`, `purchaseStateFor`, `NoopSubscriptionProvider` —
registered in `CoreDiModule` and injected nowhere. `hasPro` gates nothing; there
is no second `if (hasPro)` anywhere in the tree.

**The defect inside it, which matters more than the missing caller.**
`purchaseStateFor` reads the provider's flow by downcasting it:

    (flow as? MutableStateFlow)?.value

`NoopSubscriptionProvider` exposes a `MutableStateFlow`, so the cast succeeds and
the tests pass. A real provider — Google Play Billing, RevenueCat — will expose a
read-only `StateFlow` or a `SharedFlow`, and `asStateFlow()` returns a
`ReadonlyStateFlow` that is **not** a `MutableStateFlow`. The cast then yields
`null` for a paying user, and the derived state says "no subscription": a customer
who has paid is denied. No exception, no log line, no crash.

The existing test is named `hasPro true when subscription is present` and
asserts the opposite, with a comment saying the positive case needs a real
provider. That is an accurate description of why the case is unwritable today,
but it leaves the defect invisible: a test named for the behaviour it does not
check reads as coverage in any inventory.

**A second defect in the same function, found while writing the first one up.**
`hasAccount` is derived as `info != null` — that is, "the user has an *active
paid subscription*". Its own KDoc says "the user has a linked account (even free
tier)". A free-tier user with a signed-in account therefore reads as
`hasAccount = false`. The two fields cannot both be right: with a single
`SubscriptionProvider` source, `hasAccount` is not a function of entitlement at
all, and deriving it from a paid subscription is what makes the triple look
independently meaningful when it is not.

This one is also inert today, for the same reason as the first, and it is worth
naming separately because it will not be fixed by fixing the cast: the KDoc and
the body disagree about what the field *means*, and that is a decision about the
entitlement model rather than a type error.

**Already ruled out:** not reachable today. Nothing injects the port, so the
function is not called in production and the bug cannot yet deny anyone. It is
recorded now because it becomes a *revenue* defect the moment the first paid
feature is wired — which is the one moment nobody is re-reading this code.

**Try next:** decide the paid feature first, then fix the read. The fix is
mechanical — `subscription.first()` in a `suspend` function, or expose a
`currentSubscription` property on the port — but choosing it means deciding
whether entitlement is a *snapshot* (a suspend read) or *state* (a Flow the UI
observes), and that choice belongs with the feature, not with a bug report. Write
the missing positive-case test with a fake provider exposing a read-only
`StateFlow` before shipping anything that charges money.

---

## gate-wiring-runs-before-the-tests-it-depends-on

**Found in:** 2026-10-05, while re-running the full gate after adding tests to
`:pro`.

**Status: OPEN**

**Tracked as:** #206

**Symptom:** `check.sh` invokes `check-gate-wiring.py` at step 7 and
`:shared:jvmTest` at step 9. Part B of the wiring check proves each registered
gate *can fail*, and one of those gates — `check-test-runs.py` — reads the JUnit
XML produced by those test tasks. On a tree where the XML is absent or stale (a
fresh clone, or after any `--tests`-filtered run) the check reports

    ERROR: gate 'test-runs' already fails on a clean tree (exit 1)

and `check.sh` exits 1 before reaching the step that would have produced what it
wants. Verified against a clean tree with this session's changes stashed, so it
is not caused by the new tests.

**Already ruled out:** not a false alarm. The gate is correct — it genuinely
cannot demonstrate that `test-runs` fails, because on this tree `test-runs` fails
for an unrelated reason.

**Try next:** move the wiring check after the test tasks. Nothing before step 7
depends on it, and it does not need to run early. The tempting alternative —
having the wiring check skip the `test-runs` control when the XML is absent — is
worse: it teaches the reader that "no results yet" is an acceptable state, which
is the exact reading this project keeps eliminating. Not done here because it
changes what the local gate's exit code means, and that deserves its own commit
rather than arriving as a drive-by.

---

## forty-clock-reads-landed-in-shared-with-the-lint-fix

**Found in:** 2026-10-05, running the full gate after rebasing onto `6899121a`.

**Status: OPEN**

**Tracked as:** #208

**Symptom:** `:shared:detekt` reports 40 `NoDirectClockSystem` violations, all in
`shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`
(lines 625, 645, 693, 721, 1149 and others). detekt is `ignoreFailures = false`, so
`check.sh` cannot reach step 20.

**Why it is not a regression of the work that found it.** `6899121a` removed a
blanket `@file:Suppress` that was switching the rule off for 38 clock reads, which
is exactly the defect `check-suppression-intent.py` now prevents. The fix is
correct on its own terms; what was missed is bringing the file back to green after
it. Verified by stashing the session's work and running `:shared:detekt` on the
clean tree at `bce4873a` — the same 40 findings.

**Try next:** inject a `kotlin.time.Clock` through the fake's constructor and let
each test pass the clock it already has. A fake reading the wall clock is a source
of both test flakiness and the "green here, red on a slow host" class of bug, so
this is the rule working rather than the rule being inconvenient. The alternative
— a `@file:Suppress` with a written reason, now legal under the new gate — accepts
the debt, and 40 is a large amount to accept in one commit. Not fixed here because
it touches every test that constructs one of these fakes, which is not a change to
carry inside a commit about crash reporting and a repository rename.

---

## google-oauth-cannot-be-proved-in-ci

**Found in:** MR for Google Calendar 2-way sync, while verifying KMPAuth 3.0 before
building on it.

**Status: OPEN**

**Tracking:** the manual acceptance list lives in
`docs/decisions/2026-10-05-google-oauth-hybrid-kmpauth-plus-own-token-exchange.md`.
Deliberately *not* a GitHub issue: the remaining work is one person with one Google
account running a checklist on real hardware, and an issue only ever closed by that same
person is a tracker entry rather than a commitment to anyone else. It graduates to one
the moment a second person has to coordinate it.

**What it is.** The OAuth half of Google Calendar sync has a real manual gate and no
automated one. Three independent reasons, all in the dependency:

1. KMPAuth `3.0.0-alpha03` states it is *"Verified by compilation, API checks and unit
   tests. **Not yet exercised on real devices**"* — including Google sign-in on Android
   and on Desktop.
2. The library exposes **no refresh token** (a scan of every class in `kmpauth-core` for
   a member mentioning "refresh" returns zero hits) and its OAuth flow requests **no
   `access_type=offline`**. So the durable credential has to be obtained by this project's
   own code, and that code's first real exercise is against a live Google account.
3. Its Desktop `signOut()` is `currentLogger.log("Not implemented")`, so disconnect has to
   be implemented locally too — which is exactly the kind of path that compiles and then
   does nothing.

**Ruled out.** A "release build only" workaround does not apply: the upstream R8/ProGuard
consumer rules are present in the alpha, so the known silent-stripping failure is already
addressed, and the remaining gap is real-device sign-in rather than minification.

**Checks already performed.** Confirmed the artifact resolves and that
`:shared:compileKotlinJvm` succeeds on the project's `JVM_11` target — so there is **no**
Java 17 requirement, contrary to what the 3.0 migration guide's wording suggests (bytecode
61 as *input* is fine on a Java 11 *target*; the setting controls what we emit). Read the
JVM OAuth implementation and confirmed the absent offline-access parameters. Enumerated
the JVM classes for any refresh-token API and found none.

**Try next:** run the manual acceptance list in the change's spec on a real device and on a
packaged desktop build, with the grant actually revoked at the end. Specifically: sign in,
disconnect, and confirm `SecureStoragePort` is empty afterwards; then revoke access in
Google account settings and confirm the app says "reconnect" rather than retrying forever.
Only then build the pull engine on top of it.

**Do not** treat "it compiles" or a green unit test as evidence for this item. The unit
tests cover the token-refresh *request*, not Google's response to a real grant.

## task-detail-view-screen-is-deliberately-unwired

**Found in:** the auth-and-sync plan, when `find-unwired-surfaces.py` reported
`TaskDetailViewScreen()` as having no call site. It is the last such finding on
`origin/main`, and it has been there through three separate commits.

**Status: OPEN — the owner decided on 2026-10-07 to keep the screen and record it,
rather than delete it.**

**Tracking:** #216.

The screen has zero production call sites. That is not an accident and not an oversight,
and the two commits that made it so each recorded why:

- `dad11e6b` — "one task detail screen, because a merge had quietly made two". A merge
  had left the project with two detail screens, and the decision was to keep one. That
  commit also recorded the reason this file exists: **deleting another branch's
  deliberate carrier is the owner's decision**, and it declined to make it.
- `f8e38643` — "the three screens read the wall clock, and one of them is not wired".
  This added the screen back as what that commit calls a *clock-suppression carrier*,
  and said in its own subject line that it is not wired.

So the screen is kept because deleting it may remove behaviour that exists nowhere else,
and the open question is not "is it dead" but "is the clock-suppression it carries still
needed, and if so, where does it live". Deleting 633 lines to make a gate green would
have answered that question by accident.

**Exempted** in `scripts/find-unwired-surfaces-baseline.txt` as
`TaskDetailViewScreen`. The exemption is only honoured because
`check-unwired-backlog-refs.py` resolves this heading — which is the point: the entry is
what makes the exemption honest, and the gate fails if it ever stops resolving.

**To close this,** decide one of:

1. Move the clock-suppression behaviour into `TaskDetailScreen` and delete the screen.
2. Wire it, if two detail screens is after all the intent — and say so, since that
   reverses `dad11e6b`.
3. Keep it unwired and accept it as long-lived debt, in which case this entry stays and
   the exemption stays.
## android-scenario-matrix-is-not-illuminated-by-ci

**Found in:** 2026-10-06, while restructuring the CI workflows.

**Tracking:** `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md` — the
decision to declare rather than fix, and why, live there. No issue filed yet:
both fixes are scheduled work rather than a defect, and this file already
flags the unbounded-queue problem, so an issue filed now would be filed
into the same place 67 other open entries already sit.

**Status: OPEN — accepted as declared debt, not as a defect.**

18 of 19 scenario specs claim the android target. Exactly one Maestro flow carries
a `scenario:` tag (`TASK-REC-01`), no workflow passes `--maestro` to
`traceability results`, and `:androidApp:connectedDebugAndroidTest` runs in no CI
job. So the android column of the result matrix renders as ⌛ on every commit.

**Why it was left declared rather than fixed here:** both honest fixes are not CI
changes. One is a device-backed instrumentation job on an emulator, which is the
10-minute `assembleDebug` plus a boot, on every PR. The other is tagging 18 flows
with scenario ids and keeping their run non-partial, so every claimed scenario with
a carrier must produce a result. Neither belongs in a restructure whose subject is
which workflow runs what.

**What was done instead:** CI normalises `--targets desktop` only, so the matrix no
longer implies an android measurement it did not take, and the limitation is
recorded in `config/docs/traceability-ratchet.json` under `known_gaps` where a
reader of the artefact will meet it.

**Try next, in this order:** (1) run `connectedDebugAndroidTest` on the E2E
emulator and feed its JUnit into `traceability results`; (2) tag the flows that
already have carriers and switch the nightly to a non-partial android run. Do (2)
without (1) and every claimed android scenario without a tagged flow fails the
nightly, which is the exit-2 rule working correctly rather than a new bug.

**Not to do:** pass `--targets android,desktop` without one of the above. That is
the current state wearing a measurement's clothes.

---

## release-apk-is-unsigned-and-unminified

**Found in:** 2026-10-06, while writing `release.yml`.

**Tracking:** `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md` — the
ordering argument (minify before signing) is recorded there. No issue filed yet.

**Status: OPEN — the release pipeline ships what the build can actually build.**

`androidApp/build.gradle.kts` has no `signingConfigs` block at all and sets
`isMinifyEnabled = false`. `assembleRelease` therefore produces an unsigned,
unminified APK, and there are no `appVersionName`/`appVersionCode` properties to
inject a version — `versionName` is hardcoded to `0.1.0`.

**Order matters, and it is not the obvious one.** R8 breaks Koin, Room and
kotlinx-serialization on their reflection, and nothing in CI exercises a minified
build today: every CI job assembles debug. So "does the shipped binary work" is a
larger risk than "who receives the file", and minification lands first.

**Try next, in this order:** (1) set `isMinifyEnabled = true`, write the
`proguard-rules.pro` entries for Room/Koin/kotlinx-serialization/Compose, and add
`assembleRelease` to the `android` matrix so R8 breakage surfaces on a PR; (2) add
a `signingConfigs` block reading `ANDROID_KEYSTORE_*` from the environment and a
`:androidApp:versionName`/`versionCode` pair fed from the tag; (3) upload
`mapping.txt` as a private artifact, which is meaningless until (1) exists.

**What exists meanwhile:** `release.yml` names its artifact `-unsigned.apk`, refuses
to run `apksigner verify` on a build with no signature, and fails when the
embedded `versionName` does not match the tag. The last one matters most: without
it a `v1.2.3` tag ships a binary that declares `0.1.0`, and nothing notices.

---

## desktop-msi-and-dmg-are-not-packaged

**Found in:** 2026-10-06, while writing `release.yml`.

**Tracking:** `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md` — the
what-this-does-not-do list is there. No issue filed yet.

**Status: OPEN.** `desktopApp/build.gradle.kts` declares
`nativeDistributions.targetFormats(TargetFormat.Deb)` and nothing else, so
`packageMsi` and `packageDmg` do not exist and there is no `main-release` directory
to look in — Compose Desktop has no build variants. `release.yml` publishes the
Linux `.deb` only.

jpackage cannot cross-compile, so each format needs its own runner: `windows-2025`
and `macos-15`. Unsigned installers also trip SmartScreen and Gatekeeper, so
adding the formats without signing ships something users must click through.

**Try next:** add `Msi` and `Dmg` to `targetFormats`, add the two runner legs, then
add signing and notarization — in that order, because an unsigned installer is
strictly worse than no installer.

**Not to do:** guess the task names from a different Compose version. The
authoritative list is `./gradlew :desktopApp:tasks --all`, and
`main-release` does not exist in this tree.

---

## mainactivity-anr-makes-every-instrumented-test-fail

**Status: OPEN**

**Tracked as:** [#219](https://github.com/gazon1/sing/issues/219)

**Found in:** setting up `android-device-tests.yml` (2026-10-07), while reading
the KDoc on every class in `androidApp/src/androidTest/` before wiring them into a
CI job.

**Symptom:** all four instrumentation classes (`AuthFlowInstrumentedTest`,
`NavigationFlowInstrumentedTest`, `CreateTaskFlowInstrumentedTest`,
`CreateNoteFlowInstrumentedTest`) carry the same warning: `MainActivity` ANRs on
emulator startup, and "all tests will fail on emulator until the Koin/Startup ANR
is resolved". Traced to `koinInject<AppearanceSettingsRepository>()` being called
in the App composable during cold start, per
`docs/decisions/2026-09-28-androidApp-smoke-tests-enabled.md`.

**Already ruled out:** not a stale comment. The cause is still in the tree —
`shared/src/androidMain/kotlin/com/singularity/todo/App.kt:50` reads
`val appearance: AppearanceSettingsRepository = koinInject()`. Two other
`koinInject()` calls sit in the same composable (lines 134, 136). Not ruled out:
whether the ANR still reproduces — that needs a device, and this host had none up.

**Do not fix by deleting the assertion.** All four classes currently assert only
that a `ComposeView` is attached; two of the four KDocs say outright that they are
placeholders that do not exercise the flow in their name. If they are red because
of the ANR, the honest state is red.

**Try next, in this order:**

1. **Run the workflow once on a real runner** and read the actual failure. This
   entry is a claim in a KDoc repeated four times; the first nightly run of
   `android-device-tests.yml` either confirms it or disproves it. Do not spend time
   on the ANR before that measurement.
2. If it reproduces: the question is why `koinInject()` on the composition thread
   at cold start blocks. `AppearanceSettingsRepository` reads a DataStore-backed
   preference, and a suspend read on the main thread during composition is the
   shape that produces `ANR: FocusEvent`. The likely fix is hoisting the read out
   of the composition or making the initial value synchronous.
3. Either way, replace the four placeholder KDocs with the measured outcome. Four
   copies of the same unverified warning is the arrangement
   `2026-10-05-gate-audit-text-shape-vs-fact` was written about.

**Related but separate:** `androidApp/src/androidTest/` contains no test that
exercises the flow in its class name. That is a coverage gap independent of the
ANR, and `thirteen-scenario-slices-queued-not-yet-written` (#170) is the same gap
one tier up.


## every-gate-is-reachable-was-measured-not-assumed

**Status: OPEN**

**Tracked as:** none — the work landed with
`docs/decisions/2026-10-07-branch-protection-is-unavailable.md`; this entry is the
remainder, not the whole.

**Found in:** auditing what actually enforces anything on 2026-10-07, while
recording that branch protection is unavailable on the current plan.

**Situation.** Parts A–G of `scripts/check-gate-wiring.py` all start from a gate
somebody already decided to run. A `scripts/check*` file that can fail and is named
by nobody is invisible to every one of them: not "invoked" (A), not "registered" (F),
not asymmetric (E). Part H now covers that gap.

**Measured, not assumed — and the measurement corrected the assumption.** A first
pass reported three unreachable gates: `check-adr-references.py`, `check_adr_status.py`
and `check_skill_frontmatter.py`. All three were reachable:

- `check-adr-references.py` and `check_adr_status.py` are named in `.just/tests/mod.just`
  (lines 253 and 283), which a scan of `justfile` alone never opens.
- `check-flaky-tests.py`, which a second pass also flagged, is named in `ci.yml:191`.
- `check_skill_frontmatter.py` is the target of `check-skill-frontmatter.sh`, which
  `exec`s it. It is reached through the shim, not through surface text.

**Result: 29 candidates, 29 reachable, 0 unreachable.** Part H therefore passes
immediately, which makes it a ratchet against the next one, not a fix for anything
existing.

**Already ruled out:** not a claim that nothing is wrong. Two derivations that
reported a short list and called it complete were the actual defect, and both are
now encoded as tests: `test_a_gate_reachable_only_from_a_just_recipe_is_reachable`
(a non-recursive `.just/*` glob misses every nested recipe) and
`test_a_shim_delegated_gate_is_reachable` (reading surface text alone calls a
delegated gate an orphan).

**Try next, in this order:**

1. Nothing to fix. The gap is closed. Keep the gate green rather than lowering it.
2. If a future `scripts/check-*.py` is added and CI is red on Part H, the answer is
   to name it in `scripts/ci/static-gates.sh` or delete it as superseded — not to
   widen the scan. A gate that a scan cannot find is usually a gate that is genuinely
   not run.
3. If a legitimate third spelling of a gate name appears, add it to `_GATE_CANDIDATE`
   *and* add the test that proves the new spelling is a candidate.

---

## the-versioned-sync-schema-is-never-applied-or-verified

**Status: RESOLVED 2026-10-07 — structurally.** `scripts/check-supabase-schema-integrity.py`
is now a blocking gate with a positive control and ten self-tests. It proves the header and
the body still describe the same set of functions, in both directions. It cannot verify an
md5: those are `pg_proc.prosrc` values in a live database and reading them needs
credentials, so the live half stays manual and is why this entry was not closed outright.

**Tracked as:** #221 (closed; the live half is recorded there as what remains)

**Found in:** 2026-10-07, while landing #184 part 2 — the first time the server's sync
schema was written into the repository at all.

`supabase/migrations/2026-10-07-sync_schema.sql` is 43 KB: ten tables, their indexes, ten
RLS policies, the grants, and twelve function bodies, captured by reading the live
project's catalog rather than retyping it. It is the only description of the server
schema that is reviewable, diffable, and available to an agent without credentials.

Nothing applies it and nothing checks it. `grep -rn "supabase" scripts/ .github/ .just/`
returns nothing; there is no applier, no verifier, and no task that takes `supabase/` as
an input.

The drift check that was performed — md5 of all twelve `pg_proc.prosrc` values, recorded
in the file header, all twelve matching byte for byte on 2026-10-07 — is a one-time
measurement written down as evidence. It is not a check. The first person to change
`sync_batch_apply` in the database makes those fingerprints false, inside a file that
otherwise reads as authoritative.

**Why not fixed here.** The live half needs credentials, so it cannot be a blocking gate
on CI. The structural half can. Shipping a gate that silently passes when it cannot
connect would be the exact defect class `2026-10-06-ci-single-gate-registry-and-leaf-split.md`
already records, so the honest state is the recorded one.

**Try first:** the structural half — parse the SQL and assert it is self-consistent (every
`CREATE FUNCTION` body has a header fingerprint, no object is declared twice, every table
a policy names exists). That needs no credentials and catches the dominant failure: edit
the file, forget the database.

---

## the-android-graph-is-never-resolved

**Status: OPEN**

**Tracked as:** #227

**Found in:** 2026-10-07, while restoring the desktop graph's resolution test.

`SyncDiGraphResolutionTest` resolves the JVM graph. The Android side has no equivalent and
cannot grow one from where it stands: `shared/src/androidHostTest/` contains only
`AndroidManifest.xml`, and `shared/build.gradle.kts` records that the "Koin graph test" its
comment referred to "is also gone". `testAndroidHostTest` therefore runs zero tests.

`PlatformModuleMirrorTest` compares declared binding *names* between the two platform
modules and resolves nothing, so it cannot see anything that only fails when the
definitions run: a body that resolves a type nobody binds on Android, a resolution cycle
(a `StackOverflowError` at app start with no application frame in the stack — ADR
`2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`),
or a `databaseBuilder` handed the wrong context under a harness. `koin-compiler-plugin`
covers none of it, for the same reason it missed the sync cycle.

**Not done here.** Resolving the Android graph needs Robolectric, and `androidHostTest`
declares no test stack — the build file lists exactly what has to be added, and warns that
the Vintage engine is required because Robolectric is a JUnit 4 runner. Declaring a stack
that no test has ever run would produce a green task that executes nothing, which is the
defect class `2026-10-06-ci-single-gate-registry-and-leaf-split.md` already records.

**Try first:** declare the stack as the build-file comment lists it and add exactly one
test — the `SyncDiGraphResolutionTest` equivalent over `PlatformModule.android.kt` plus
`domainModule()`. If Robolectric cannot run on this host, that is the finding; record it
rather than substituting a fake. Then put `testAndroidHostTest` into
`check-test-runs.py --require`, which today would pass a source set that executes nothing.

---

## the-measurement-system-cannot-see-a-feature-that-declares-no-scenario

**Status: OPEN — the calendar-sync instance is closed, the class is not**

**Tracked as:** none yet; the class-level gap is the argument for a gate below.

**Found in:** 2026-10-07, while writing the scenario specs the Google calendar-sync
feature never had.

**Situation, measured.** `infra/kiwi/traceability/` is a real measurement system: 26 specs,
49 claimed cells, three blocking gates, a one-directional hole ratchet that failed at
`holes: 2 -> 32` on the day it was written. The calendar-sync feature shipped **23
unit-test classes, 8 OpenSpec requirements, and 0 scenario specs** — and therefore
contributed **zero** cells to the matrix. It could not be reported as a gap, because a
system that enumerates what has declared itself cannot report what has not.

The matrix read `holes: 32` the whole time and looked exactly as healthy as it had the
week before, on a different product.

**Already ruled out.** Not a coverage shortfall: the unit tests are good and several guard
invariants that would be expensive to lose (`RecurrenceRuleMapperTest` and
`EventShadowCodecTest` on the byte-identical recurrence rule; `SyncDiffMergeTest` on the
merge). Not a linkage problem either — the scanner found the new carriers first try, and
`traceability validate` exits 0 with holes reported as information by design ("это не
ошибка — это и есть смысл матрицы").

The reason no carrier could exist was upstream of all that: **the panel had zero
`testTags` in 592 lines**, and carriers are only recognised in `desktopApp/src/jvmTest` and
`androidApp/src/androidTest` — `shared/commonTest` cannot carry one, which the 23 existing
tests do. So the feature was not merely unmeasured, it was *unmeasurable*: there was no
address to point a carrier at.

**Why this stays open after the fix.** The calendar-sync instance is closed — 7 specs, 6
desktop carriers, honest `unreachable` on `CAL-SYNC-RECUR-01`, and the floor raised
32 -> 40 with the reason recorded in `traceability-ratchet.json`. What is not closed is
the **class**: nothing requires a feature to declare a scenario, so the next feature can
ship exactly the same way.

**Try next, in this order.**

1. **A gate that a new feature area declares at least one scenario**, failing when a
   directory under `shared/src/commonMain/.../feature/<new>/` appears with no
   `infra/kiwi/scenarios/<area>/`. This is the only step that closes the class. The hard
   part is the exemption list: a feature that genuinely has no user-visible surface should
   be deletable from the list by adding a name to a file, and that file needs its own
   reviewer-visible justification — the same bargain the detekt baseline makes.
2. **The metric that makes it visible without a gate**: `dark_areas` — feature areas with
   production files and zero specs. It cannot fail anything on its own, but it appears in
   the matrix output, so the absence is a *looked-at* number rather than an unasked one.
3. **Reorder the ADR/scenario relationship.** `calendar-sync` got 8 requirements in
   `openspec/specs/` and zero scenarios, and nothing connected the two. If a spec file
   under `openspec/specs/<area>/spec.md` were the thing that demanded scenarios, the gap
   would surface at the moment the requirement was written rather than at the audit.

**Not to do:** raise the hole floor again to make the number smaller. 32 -> 40 was a
*correct* increase: six new scenarios verified on desktop bought an honest accounting of
twelve previously invisible cells. Diluting the number back would restore the exact
condition the ratchet was written to detect.

---

## the-unwritten-property-detector-cannot-see-a-ksp-expression

**Status: OPEN — blocked on an API boundary, re-verified 2026-10-07**

**Tracking:** tracked here rather than as a GitHub issue because the work is a decision
about a build dependency, not a product commitment — and because the next attempt is option 1
in "Try next" below, which is self-contained: add `kotlin-compiler-embeddable`, map
`KtExpression` onto the existing `UnwrittenPropertyAnalysis`, and see whether the gate's
output is worth a `--require` floor. That is a single afternoon with a known failure mode
(the full-callback surface is large and will need filtering), not a queue position.

**Found in:** 2026-10-07, while wiring `tools/unwritten-properties/` to a real
symbol processor. The pure analysis (`UnwrittenPropertyAnalysis.findNeverWritten`) and
its 13 tests are done and passing; the KSP adapter is not, and the reason is structural
rather than a missing dependency.

**Already ruled out — measured, not inferred.** `KSExpression`, `KSCallExpression` and
`KSPropertyAccessExpression` are **absent from every KSP jar in the local Gradle cache**,
verified by listing the class entries of `symbol-processing-api-2.3.11.jar`,
`symbol-processing-common-deps-2.3.11.jar` and every other `com.google.devtools.ksp`
artifact present. What `symbol-processing-api` 2.3.11 ships is declarations only:

```
KSAnnotated KSAnnotation KSCallableReference KSClassDeclaration KSClassifierReference
KSDeclaration KSDeclarationContainer KSFile KSFunction KSFunctionDeclaration
KSModifierListOwner KSName KSNode KSPropertyDeclaration KSPropertyAccessor
KSPropertyGetter KSPropertySetter KSReferenceElement KSType KSTypeAlias
KSTypeArgument KSTypeParameter KSTypeReference KSValueArgument KSValueParameter
KSVisitor KSVisitorVoid
```

**Why this is the boundary and not a gap.** Detecting a never-written property means
finding *references* — a property is written by an assignment or an `apply { }`, both of
which are expressions. KSP's supported API exposes the declaration tree, not the
expression tree, so "is this property ever written" is not expressible in the supported
surface. This is the same wall ADR `2026-10-07-reading-a-state-property-is-not-writing-one`
names from the other side: that ADR proves detekt cannot answer the question because it
visits one file at a time, and this entry says KSP cannot either, for a different reason.

**Why it is still worth doing rather than deleting.** The question is real — ADR
`2026-10-07-a-default-argument-that-is-wrong-for-every-caller` found 15 of 16 call sites
carrying a wrong tag by exactly this reasoning, and a never-written `isSupported` field
shipped once already. The detection has value; only the *route* is blocked.

**Try next, in this order.**

1. **Kotlin compiler analysis API directly** (`org.jetbrains.kotlin:kotlin-compiler-embeddable`,
   `KtExpression`). It has the expression tree, so the analysis maps directly onto
   `UnwrittenPropertyAnalysis`. Cost: an embeddable-compiler dependency and a processor
   that is no longer KMP-shaped. Check `check-dependency-usage.py` before declaring it —
   the gate will flag an artifact whose packages it cannot see used.
2. **A detekt rule over one file at a time, plus the never-written list maintained by
   review.** Honest, cheap, and it cannot be automated; it is strictly worse than option 1
   and strictly better than nothing.
3. **Leave it.** `tools/unwritten-properties/` stays a pure analysis with its tests, not
   wired into `:shared`. This is the current state and it is defensible: the ADR
   `2026-10-07-two-of-three-background-jobs-were-not-buildable-yet` records that a gate
   failing the build on every real finding needs human review, and that was true of the
   wiring independently of the API gap.

**Not to do:** write the adapter against `KSPropertyDeclaration` only. That sees
declarations, and a property is never *declared* again — it would report every property in
the codebase as never-written, which is a green gate asserting something false.

---

## the-android-graph-test-runs-but-cannot-open-a-database

**Status: OPEN — Robolectric is wired and the test executes; Room's native SQLite does not load**

**Tracking:** tracked here rather than as a GitHub issue because the remaining work is a
single bounded step with a known failure mode — extract `libsqlite3.so` for linux-x86_64
from the bundled SQLite artifact into `shared/src/androidHostTest/jniLibs`, or point the
task's `java.library.path` at it, then re-run the one class. Everything else is already in
place: the stack, the detekt source entry, the tag rule, the task-filter exemption and the
ADR correction. It does not need a queue position; it needs someone with a spare
afternoon and the artifact on disk.

**Found in:** 2026-10-07, attempting the fix recorded in the entry above. The stack now
works far enough to produce an answer, and the answer is not the one the entry expected.

**What is done and verified.**

- `shared/src/androidHostTest` declares `robolectric`, `androidx-test-core`,
  `androidx-test-junit` and `junit-vintage-engine` (RuntimeOnly).
- `src/androidHostTest/kotlin` is in `detekt.source`, so detekt now reports on it.
- `TestTagCoverageTest` lists the source set, which applies the `-Ptest.tags` filter.
- `AndroidSyncDiGraphResolutionTest` compiles and **runs** under
  `:shared:testAndroidHostTest -Ptest.tags=fast,slow`. Getting there required two fixes
  recorded in ADR `2026-10-07-a-default-argument-that-is-wrong-for-every-caller`: the test
  needed a `@Tag`, and the task needed an exemption from `includeTags`, because the Vintage
  engine does not map Jupiter's `@Tag` onto Platform tags and so a Robolectric class can
  never be selected by a tag filter at all. Before those, the task was green over 172
  classes with this one absent.

**The finding.**

```
java.lang.UnsatisfiedLinkError: no sqliteJni in java.library.path:
  …:…:…:…:…/shared/src/androidHostTest/jniLibs
  at WrappingDriver_androidKt$wrappingDriver$1.open(WrappingDriver.android.kt)
  at PlatformPragmas.applyOnceToFile(PlatformPragmas.kt:45)
  at AppDatabaseFactory.build(AppDatabaseFactory.kt:67)
  at PlatformModule_androidKt.platformModule$lambda$0$0(PlatformModule.android.kt:101)
  at AndroidSyncDiGraphResolutionTest…
```

Room's `androidx.sqlite:sqlite-bundled` ships an Android `.so`; Robolectric looks for
`sqliteJni` under `src/androidHostTest/jniLibs` and does not find a loadable one. So every
definition that reaches the database — which includes `GoogleSyncEngine` and its four DAOs,
i.e. exactly the cycle this test was written for — cannot be constructed here.

**Why the test is not committed green.** The only assertions worth having are the ones
that resolve DB-backed definitions; anything less resolves nothing and asserts nothing.
Substituting a fake database would test the fake, which is what the entry above warned
against, so the honest state is: no test in that source set yet, and a known reason.

**Try next, in this order.**

1. **Put the native library where Robolectric looks.** Extract `libsqlite3.so` for
   linux-x86_64 from the bundled SQLite artifact into `shared/src/androidHostTest/jniLibs`,
   or set `java.library.path` for the task. Smallest change, and it keeps the test's scope
   honest. Verify by running the test alone before re-running the task.
2. **Assert the cycle without a database.** `koin.checkModules()` with a definition check
   that does not instantiate factories would catch the *shape* of a cycle, which is what
   `koin-compiler-plugin` missed — but it would not catch a wrong context or a missing
   DataStore. Weaker, and it should say so in the class name.
3. **Keep the Robolectric stack for the next Android test and leave this open.** The
   wiring cost is paid once now rather than by the next person who needs a real `Context`.

**Not to do:** mark the test `@Disabled`, or catch the `UnsatisfiedLinkError` and pass.
`check-test-runs.py` rejects the first and it would be a lie in the second — a graph test
that cannot resolve its own database has verified nothing about the graph.
