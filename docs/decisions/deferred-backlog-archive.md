# Archived backlog findings

Every entry here was closed or resolved. It is kept, not deleted: the reason a
finding stopped being open is usually the most useful sentence in it, and a link
from an ADR, a skill or the live backlog has to resolve to something.

**Moved 2026-10-05, once the file's statuses became machine-readable.** The split
was not possible before that. Of the 82 entries, 42 carried no status line at
all, and two independently written regexes counted the resolved ones as 18 and
26 — each silently classifying what it could and skipping the rest. Splitting on
that signal would have moved whichever entries a parser happened to recognise,
which is the same failure as a check that matches less than it intended.

`scripts/check-backlog-status.py` now requires every entry in the live file to
carry a `**Status:**` from a fixed vocabulary, and an OPEN entry to carry a
`**Tracked as:**` reference. Entries marked `PARTIALLY` deliberately **stayed**:
a partly-done finding is a live commitment with a recorded part of it done, not
a closed one.

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

## docs-audit-workflow-was-never-valid-yaml

**Status: CLOSED 2026-10-05.** The general lesson is now enforced, not
remembered: `check.sh` step 14 parses every `.github/workflows/*.yml` and
fails the build on one that will not parse. Proven by sabotage rather than
assumed. The `"Try next"` below is kept for the record of how the gap was
found; its closing sentence is superseded by this status.

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
failure, not a lint error, just a workflow that silently does not exist.

**CLOSED 2026-10-04 — the class is gated, verified by sabotage.** `check.sh`
step 14 (`workflow YAML parses`) runs `yaml.safe_load` over every
`.github/workflows/*.yml` and exits non-zero on the first that will not parse.
Proved it can fail rather than assuming it: appending an unterminated quoted
scalar to `ci.yml` makes the gate exit 1 with a line and column, and removing
it returns the gate to green. So the entry's "it has not been added yet" is no
longer true, and the general lesson it names is now enforced rather than
remembered. Kept for the record of how the gap was found.

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

## an-untagged-test-class-is-invisible-to-a-tag-filtered-run

**Found in:** 2026-10-04, on the first CI run of the verifiability branch — the
run that finally executes `testAndroidHostTest`, which the old
`-Ptest.tags=fast,slow` filter had meant never ran at all.

**Status: RESOLVED (2026-10-05).** The open question below — should an untagged class
fail a gate? — is answered yes, by `TestTagCoverageTest`, and that gate's own
`@Test`-only blind spot was found and closed in the same change. See "Try next" below.

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

**Answered 2026-10-05 — yes, and the gate that does it had its own hole.**
`TestTagCoverageTest` (shared/src/jvmTest) fails any test class in a tag-filtered
source set that carries no `@Tag`. But it detected test members by matching `@Test`
alone, so a class whose tests are `@ParameterizedTest` registered as *having no
tests* and was reported clean. Two such classes were, at that moment, invisible to
CI for the second time — the gate about untagged classes was blind to the same
condition it was written for:

- `RecurrenceRuleMapperTest`
- `RruleGeneratorTest`

Both are now `@Tag("fast")`, and the gate matches every JUnit test annotation
(`@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate`,
plus the `kotlin.test` spelling) instead of one. The check verifies what it claims:
a class with a test member and no tag fails, whichever annotation carries the test.

Scope note, checked rather than assumed: `detekt-rules` (18 untagged classes) and
`androidApp` (4) are **not** in the gate's source-set list, and do not need to be —
neither module's test task applies a tag filter, so an untagged class there still
runs. Adding them would have been the loud wrong fix. The list now names the
criterion it encodes: source sets *whose Gradle task translates `-Ptest.tags` into
a JUnit filter*.

Also worth noting: `koverXmlReport` depends on `testAndroidHostTest`, so the
`kover-report` job was **red on `main`** for this reason. A job that is red for a
reason nobody reads is the same failure as a gate that is green for a reason nobody
checks.


---

**Re-measured 2026-10-07.** The rule was right and the scope was not: `TestTagCoverageTest`
listed `shared/src/commonTest`, `shared/src/jvmTest`, `desktopApp/src/jvmTest` and
`mcp-server/src/test`, but not `shared/src/androidHostTest` — a source set that applies the
same `-Ptest.tags` filter. The first class added to it (`AndroidSyncDiGraphResolutionTest`)
was therefore never checked, and two further facts surfaced that the entry did not predict:
`includeTags` excludes untagged classes, and the Vintage engine drops Jupiter's `@Tag` from
JUnit4 ones entirely, so a Robolectric class cannot be selected by a tag filter at all.
`androidHostTest` is now listed, `testAndroidHostTest` is exempt from an explicit tag filter,
and the finding is in "the-android-graph-test-runs-but-cannot-open-a-database" in
`deferred-backlog.md`.

## taskdetailviewscreen-is-633-lines-of-unreachable-composable

**Found in:** 2026-10-06, while running the new `static` gate job against the tree.

**Status: RESOLVED 2026-10-07.** The screen was deleted; the file was the last thing
holding the finding up.

`shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/
TaskDetailViewScreen.kt` was 633 lines with no production call site, so
`scripts/find-unwired-surfaces.py` reported it and the `static` job stayed red.

Its header recorded why it was still there:

> This screen has no call site — `find-unwired-surfaces.py` reports it, and
> `dad11e6b`'s note says deleting another branch's deliberate carrier is the
> owner's call, not this one's.

That was a decision deferred and then not revisited — the failure mode
`an-open-backlog-entry-does-not-mean-the-work-is-still-open` describes. The owner
re-decided on 2026-10-07 and chose deletion over baselining.

The supporting evidence for deleting rather than baselining: the only remaining
mention of the file in the tree was a KDoc in `TaskDetailProposalSection.kt` saying
the section was "Moved out of `TaskDetailViewScreen` when that screen was deleted",
and a test KDoc in `TaskDetailTimeTrackingSectionTest.kt` recording that nothing
composed it. Both were rewritten rather than left dangling. `:shared:compileKotlinJvm`
builds after the deletion, so nothing resolved against it.

**Not to do:** re-add a second task-detail screen as a clock-suppression carrier. That
is what produced the 633 lines, and the reason the #187 one-screen invariant matters
is that two screens under one route and one ViewModel shipped with time tracking on
one platform and not the other — which is the bug
`TaskDetailTimeTrackingSectionTest` now guards against.
---

## a-filtered-test-run-is-indistinguishable-from-a-shrunken-suite

**Status: RESOLVED 2026-10-07.** The test task writes a run manifest beside the XML, and
`check-test-runs.py` reads it: below the floor, the message now says the counts are not
evidence and names the filter, instead of reporting a regression against a tree where
nothing had happened. Verified end to end — manifest marked partial, most of the XML moved
aside, gate prints the filtered-run sentence. A missing manifest is treated as unknown, not
as filtered.

**Tracked as:** #222 (closed)

**Found in:** 2026-10-07, while running `check-gate-wiring.py` on a tree where nothing
was broken.

`check-test-runs.py` reports the same verdict, with the same message, for two unrelated
situations: the suite genuinely shrank, and someone ran
`./gw :shared:jvmTest --tests 'SomeOneClass'` for a fast loop. The second rewrote
`shared/build/test-results/jvmTest/` with one class's XML and silently invalidated the
evidence that `check-test-runs.py`, `check-coverage.py` and `check-flaky-tests.py` all
read.

Observed, on a healthy tree, after a filtered run of two arch test classes:

```
ERROR: gate 'test-runs' already fails on a clean tree (exit 1). Fix the underlying
failure before trusting its sabotage control.
```

Every gate named in that sentence was behaving correctly. The diagnosis cost is the
defect: it says "fix the underlying failure", and the underlying failure was an ordinary
development command run on the same machine twenty minutes earlier. The same ambiguity
hit `origin/main` in the other direction earlier in the session — `check.sh` never reached
its last steps and nothing in the output said why.

**Not a staleness problem, and must not be regressed into one.** Freshness is handled:
`check.sh` passes `--max-age 21600`, CI passes `--since "$RUN_STARTED"`, and `count()`
returns `None` rather than a passing zero when the newest report predates the window. The
gap is partiality.

**Try first:** have the test task write a run manifest next to the XML — task path,
whether `--tests` was passed, source-set class count — and have the gate read one field
from it, so it can say "this evidence came from a filtered run" instead of "a suite
stopped running". Gradle leaves no such marker today, which is why this is not a
five-line fix to the gate itself.

---
