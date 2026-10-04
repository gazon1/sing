# MR-6 Retro-Gate

**Phase 6 / MR-6** — gates and DoD.

---

## Gate Results

| Gate | Result |
|------|--------|
| `SKIP_ADB=1 ./check.sh` (7 steps) | ✅ ALL CHECKS PASSED |
| `:shared:testAndroidHostTest` | ✅ 1045 tests, 0 failures (was 2 red) |
| `just coverage-ratchet` | ✅ 687/1006 = 68.29%, at baseline |
| `ViewModelTestCoverageTest` | ✅ green; allowlist = 11 |
| `MaestroFlowTagsTest` (ids + runFlow paths) | ✅ green, 55 runFlow refs resolved |
| `:shared:detekt :desktopApp:detekt` | ✅ 0 findings |
| Maestro, 8 agenda journeys | MAESTRO_RESULT |

---

## Problems Found & Fixed

### 1. `:shared:koverXmlReport` was red, and the report it produced was all zeros

**Found in:** Phase 6, building the coverage ratchet the DoD asks for.

Two separate faults stacked:

1. The task depends on `:shared:testAndroidHostTest`, which failed (see #2), so
   the report could not be produced at all.
2. With the Robolectric failures worked around (`-x testAndroidHostTest`) the
   report *was* produced — and every counter under `feature/agenda` was 0.
   Kover instrumentation is disabled for `:shared:jvmTest`
   (`shared/build.gradle.kts`), and the agenda tests live there, so nothing
   instrumented them. A ratchet pinned to that number ratchets on nothing.

**The disable itself is suspect.** The OOM that caused it was attributed to the
Kover runtime, and `2026-09-27-write-layer-soundness.md` ledger #11 records that
this was wrong: it reproduces with Kover off and in complete isolation, so those
tests were disabled rather than the cause fixed. Measured: a filtered
instrumented run of the agenda suite completes in ~90 s and does not OOM.

**Fix.** Instrumentation is now opt-in behind `-Pkover.jvmTest=true`, so
`./check.sh` and `just tcheck` are unaffected until the whole-suite case is
proven, while the ratchet can measure a filtered run. The filter has to arrive
as `-Pcoverage.tests`, not `--tests`: Gradle rejects `--tests` when
`koverXmlReport` is in the same invocation, and without a filter the report task
pulls in the entire suite under instrumentation.

---

### 2. `ReadToolsProfileAwareTest` — 2 tests red on Robolectric, green on the JVM

**Found in:** Phase 6, while measuring the coverage above. Pre-existing; red on
`main`, unrelated to the agenda work.

`CurrentUser` and `ProfileAwareCurrentUser` both seed their `StateFlow` with a
placeholder and update it only from a collector. The AI read tools read
`scopedUserId.value` **synchronously**. The test built both on
`createBackgroundScope()` (`Dispatchers.Default`), so the read raced the
collectors: on a real JVM the background threads won, under Robolectric they
did not. After `switchTo("ai-agent")` the chain still resolved to
`ai-agent/anonymous` while the fixture had seeded `ai-agent/u-1`, so the tools
returned nothing and `assertEquals(1, tasks.size)` saw 0.

The comment in the file claimed commonTest has no `TestScope`. It does —
`runTest { }` *is* one.

**Fix.** `backgroundScope` for both collectors, `runCurrent()` after the
profile switch, and the hand-rolled `resolveScopedUserId` helper is gone: it
re-implemented the profile→userId mapping, which now lives in one place
(`scopedUserIdFor`), so the tests read what production actually resolved and
assert it before seeding.

---

### 3. Five Maestro flows pointed `runFlow` at a directory that does not exist

**Found in:** the Phase 6 Maestro gate — `04-saved-view-results` failed with
`Invalid File Path`.

From a flow in `Maestro/flows/<group>/`, the helpers directory is
`../../helpers/`. Five flows said `../helpers/`. Four of them are in
`flows/tasks/`, which no agenda-tagged gate had ever run — they were committed
broken and nobody had noticed, because the only symptom is a red run on a
device.

**Fix.** Paths corrected. `MaestroFlowTagsTest` now resolves every relative
`runFlow` against the including flow's own directory; verified by putting the
typo back and watching it fail with the exact file and path out of 55 refs.

**Caveat worth knowing:** the arch tests read `Maestro/` from disk, which
Gradle does not track as a `jvmTest` input. CI is a fresh checkout so it always
runs; locally, editing only a flow leaves the task up-to-date and the check
silent. `--rerun-tasks` after a flow-only edit.

---

### 4. Journey 04 asserted two behaviours the app does not have

**Found in:** the same gate, after #3 stopped it from getting that far.

- **The discard guard only shows on a dirty draft.** `SavedAgendaScreen`'s back
  handler checks `editing.draft.isDirty` first; a successful Save clears that
  flag, so back exits directly. Journey 04's own comment assumed the draft
  stayed dirty "from naming". Journey 03 *does* get the dialog — it renames
  without saving, which is the state that should raise it.
- **An undated task cannot appear in a Today-based view.** `seed-task` creates
  a task with no due date; the journey then saved the view from the Today tab,
  whose definition is Overdue + Today. Only the Inbox preset has a No Date
  section. The desktop twin of this flow picks Inbox for exactly this reason and
  documents it.

Neither is a product bug. Both are the same failure the MR-3 retro-gate recorded
in unit form: a test written from an assumption instead of from the code, which
then "finds" a defect that is not there. Fixed the flows, with the reasoning
inline so the next reader does not re-derive it.

---

### 5. CI uploaded a kover report that never existed

**Found in:** Phase 6, while working out how the report is consumed.

`.github/workflows/ci.yml` pointed the upload at
`shared/build/reports/kover/xml-report.xml`; Kover 0.9 writes `report.xml`. The
glob matched nothing, so every run has uploaded an empty artifact and passed.

**Fix.** Corrected path, with a comment so the next rename is not silently
reintroduced.

---

## Deferred to ADR / Backlog

| Item | Reason | Where |
|------|--------|-------|
| The agenda editor's "Save" does not pop the screen | Journeys 03/04 both work around it; whether save-and-close is wanted is a product call, not a test fix | backlog |
| Kover instrumentation is only proven for a filtered run | The whole-suite OOM that motivated the disable was misattributed, but nothing here re-measured the full `jvmTest` under instrumentation | ADR-worthy — see below |
| 5 flows in `flows/tasks/` had never been run by any gate | The agenda tag only covers `flows/agenda/`; `tasks` flows are `regression` + `tasks` and no default gate runs them | backlog |
| List-picker rows select by label in journey 07 | `ModalBottomSheet` does not expose resource-ids to UIAutomator | backlog (MR-5) |
| 11 ViewModels without tests | Pinned by the new rule so it cannot grow | `deferred-backlog.md#vm-without-test` |
| FakeClock in `runDesktopAppTest` | Deferred since MR-2; flow tests still read the real date | backlog (MR-2/MR-4) |

---

## DoD

| DoD item | Status |
|----------|--------|
| A1 matrix green on both platforms | Desktop: `AgendaReachabilityFlowTest` green. Android: 8/8 agenda journeys green on the emulator. |
| 7 presets covered by unit + Create→Results | `AgendaPresetsCatalogTest` (20, all seven factories) + `AgendaEvaluatorMatrixTest` (39) + journey 04 now proves Create→Results end to end |
| Fact cases have a unit spec and a UI fix | Journey 04's two false expectations and the runFlow bug are now contract-tested; the no-Date/no-selector gaps stay in the backlog with links |
| Classes from plan §2 closed or in the backlog | `deferred-backlog.md` carries the 8 backup tables, the 11 VMs, the product gaps, and the docs-rot |
| Contracts green | `MaestroFlowTagsTest` (ids + paths), `TestTagsWiringTest`, `DesktopTestHarnessEnforcementTest`, `EntityMapperCompletenessTest`, `DiFacadeTest`, `ViewModelInitOrderTest`, `ViewModelTestCoverageTest` |

### Known gap this epic deliberately left

`byTags` still has no UI entry, and saved-view selectors still take no
parameters. Both are recorded in the test plan's known-defects section and in
`deferred-backlog.md`; neither was in scope.

---

## MR-6 Summary

The phase delivered the two gates the plan asked for, and both needed
infrastructure that did not exist: the coverage ratchet needed instrumentation
turned back on, and the VM rule needed a new arch test plus two new system
properties. The ratchet is measured at **68.29%** over `domain`, `data`, and the
view models; the whole package including the Compose-only subtrees is 31.60% and
is recorded in the baseline for context, because those subtrees are covered by
the desktop flow tests that kover cannot see.

The unglamorous find was the five broken `runFlow` paths — four of them committed
and never run by any gate. That is the argument for the contract test, and for
running the flows rather than only writing them.
