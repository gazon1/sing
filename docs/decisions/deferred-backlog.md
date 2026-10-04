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

**Found in:** the post-epic docs pass. `DIGEST.md` sat at 1498/1500 lines.

**Symptom:** the digest indexes every Consequences bullet and creates a
section per tag, so it grows with every ADR while the limit is fixed. The
next author who writes a verbose ADR gets a failed `docs-audit` with no
obvious remedy and will either trim content (bad) or raise the limit (worse).

**Partially done (2026-09-30):** `MAX_ITEMS_PER_TAG` lowered 12 → 10 — the
digest is an index, the ADR body is one link away. That bought ~45 lines of
headroom at 351 entries.

**Further done (2026-10-04):** the gate was found **already red** — the digest
sat at 1255 against a 1250 limit, and `AGENTS.md` at 253 against 250, both
before this branch touched them. Two caps added to
`refresh-decisions-digest.py`: `MAX_BULLETS_PER_ADR = 6` (this entry's item 1,
which had been listed here since 2026-09-30 and never done) and
`MAX_ITEMS_PER_TAG` 10 → 8. The omission counter was corrected too, because with
a second cap in play the old `total - MAX_ITEMS_PER_TAG` formula no longer
described what was on screen. Digest now **1204 / 1250**, ~46 lines of headroom.
ADR: `2026-10-04-doc-size-budget-was-already-red.md`.

**Try next, if the warning returns:**

1. The caps are the first thing to turn, not the line count. Expect
   `MAX_ITEMS_PER_TAG` → 7 before anyone considers `MAX_DIGEST_LINES`.
2. Only when both caps are at their floor, raise `MAX_DIGEST_LINES` with a
   comment explaining why the index needs the room.
3. The structural fix, if the index ever outgrows this shape: stop indexing
   consequences. "Active entries" (427 lines) and the per-tag sections (695)
   restate the same 432 ADRs twice; title + tags + a one-line summary, with
   consequences left in the bodies, would be a third of the size and lose
   nothing a reader actually uses the digest for.
4. Keep the existing discipline regardless: Consequences bullets are
   consequences; only **Always/Never** rules belong in the Critical section.

---

## ci-gates-are-all-continue-on-error

**Found in:** `refactor/tag-registry-and-robots`, while wiring `check-tags.sh`
into `.github/workflows/ci.yml`.

**Symptom:** every gate step in the `build` job carried
`continue-on-error: true` — `Build version catalog gate`, `Run detekt`,
`Assemble Android debug`, `Find unwired surfaces`. Only `jvmTest`,
`desktopApp:test` and `Check Maestro test tags` could fail the workflow.
So "CI is green" said nothing about detekt, unwired surfaces, or version
literals.

**Already checked:** `:shared:detekt` enforced locally
(`ignoreFailures = false` in `shared/build.gradle.kts` and `check.sh` step
`[6/6]` fails on it) — this was a CI-policy gap, not a detekt gap.

**Status: PARTIALLY RESOLVED.** Phase 1.1 (PR-2) flipped three gates to blocking:
`Find unwired surfaces`, `Check doc sizes`, `Check dead doc references`.
The remaining `continue-on-error` gates (`Run detekt`, `Assemble Android debug`)
should be evaluated after 3 successful PRs with the current blocking gates,
one at a time, oldest debt first.

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

## agenda-section-add-button-noop

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

**Found in:** MR-0, свип desktop harness и FakeClock.

**Symptom:** `FakeClock` существует (`shared/src/commonMain/.../test/fakes/FakeClock.kt`) с API `advance(Duration)`, `setNow(Instant)`, `today(zone)`. Имеет **0 упоминаний** в `desktopApp/src/jvmTest`. `runDesktopAppTest` не принимает clock-параметр. Все desktop flow-тесты используют `todayInSystemZone()` → реальное время хоста → date-dependent тесты флакиют на границах месяца/недели.

`CalendarFlowTest` так уже падал: «passed on September 30th, failed on October 1st».

**Status: OPEN.** Фиксируется в MR-2 (тест-инфраструктура): добавить `fakeClock: FakeClock? = null` параметр в `runDesktopAppTest`, подключать через `overrides = module { single<Clock> { fakeClock } }` (Koin last-wins). Закрыть backlog-пункт `no-direct-clock-system-kdoc-claims-tests-are-exempt`.

---

## vm-without-unit-tests

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

**Fix (structural): DONE** (2026-10-04). `Modifier.exposeTestTagsAsResourceId()`
is an expect/actual helper (`core/ui/TestTagExposure.kt` + `.android.kt` +
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
`DropdownMenu` and its items get `exposeTestTagsAsResourceId()`. Flows
`archive/01-restore`, `tasks/04-delete` and `tasks/06-delete-undo` were
repointed from `overflow_*` to `task_action_*`.

**Left standing, deliberately:** the five dead `EditorOverflow.*` constants stay
in the registry, because deleting them would make `MaestroFlowTagsTest` fail —
correctly, but for the wrong reason. A flow written tomorrow would hit the same
trap. The honest fix is to delete the constants *and* the flows' dependence on
them in one change, which is what the allowlist entry has been asking for since
`2026-09-30-testtag-registry-honesty`.

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
