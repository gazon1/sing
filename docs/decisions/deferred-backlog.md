# Deferred backlog

Findings that were real enough to record and too expensive to fix where they
surfaced. Each entry names where it was found, what was already ruled out, and
what a future attempt should try **first** — so the next person does not repeat
the dead ends.

Rule for adding: an entry needs a number, the MR that found it, and the checks
already performed. "Looks wrong" is not an entry.

---

## nodate-steps-2-4

**Found in:** MR-1 (`feat/desktop-compose-ui-v2`), bisecting the open question in
`2026-09-30-desktop-compose-ui-flow-tests.md`.

**Status: RESOLVED.** See `2026-09-30-nodate-fix.md`.

**Root cause:** `ProfileAwareCurrentUser._scopedUserId` was initialized to
`UserId.anonymous` before the `combine().collect` fired. In the test harness,
`seedTask()` ran before the collector fired, getting `UserId.anonymous` and
orphaning the task. In production the window is microseconds and harmless; in
tests it was large enough to cause a visible failure.

**Fix:** Seed `_scopedUserId` synchronously from `currentUser.userId.value`
and `profileRepository.activeProfileId.value` (both `StateFlow`, both already
seeded). Also change `FakeAuthRepository` default from `UserId.anonymous` to
`TestUsers.DEFAULT` so the fake is consistent.

**Product question (still open):** does a task with `startDate` but no `dueDate`
count as "No Date"? Schema v16 added start/end dates and no ADR states the
semantic. The rule lives in two places — `Selector.DateBucket.NoDate` and
`AgendaEvaluator.computeBadge` — and should route through a single
`TaskComputed.hasNoDate`. Filed as a follow-up (not blocking).

---

## sync-config-screen-unwired

**Found in:** MR-1 retrospective, `scripts/find-unwired-surfaces.py`. Pre-existing;
not a regression from the desktop UI work.

`feature/sync/presentation/SyncConfigScreen.kt` is a public `@Composable` with no
call site. `SyncViewModel` is fully built and registered. Either the screen was
never wired to a route, or the route was dropped.

**Try next:** read `AppDestination` and both `*NavEntries.kt` for a Sync
destination that exists but does not compose the screen. If no destination exists
at all, decide whether sync configuration is a product feature that lost its
entry point — that is a product call, not a refactor.

---

## saved-views-crud-flow-selects-a-snackbar-that-does-not-exist

**Found in:** MR-2, while cross-checking the constants slated for deletion
against their real consumers. Not a regression — a dormant red flow.

**Symptom:** `Maestro/flows/agenda/03-saved-views-crud.yaml:31` waits on
`id: snackbar_saved`. `SavedAgendaScreen` reports success through
`NotificationHost` → `Notification.Text("Saved", null)` → `ResultDialog`, which
is an **`AlertDialog`, not a snackbar**. `TestTags.SNACKBAR_SAVED` is applied by
no composable anywhere in `shared/src`.

So the flow waits for a UI that does not exist on that screen and the
`extendedWaitUntil` must time out. The flow is tagged `regression`, not `smoke`,
which is why the "10/10 smoke green" claim in `singularity-todo-maestro-flows`
never covered it.

**This invalidates the MR-2 plan item** that proposed deleting `SNACKBAR_SAVED` as
dead. A live flow references it, and applying a tag is equally wrong: there is
no snackbar to tag. The fix belongs in the flow, not in production code.

**Try next:**

1. Decide the intended contract. Either the screen should show a snackbar (a
   one-shot toast for a background save is the better UX than a modal that must
   be dismissed — and the flow's own comment says "it emits a 'Saved' snackbar
   and stays, so the user can keep editing", which describes a toast, not a
   dialog), or the flow should assert on the dialog. The comment suggests the
   former was the intent and the dialog is the regression.
2. If the snackbar is the intent: `NotificationHost` needs a text-notification
   variant that routes to `SnackbarHost` instead of `ResultDialog`, and
   `SNACKBAR_SAVED` gets applied there. That is a production change and needs
   its own ADR.
3. If the dialog is the intent: re-point the flow at `TestTags.Dialog.CONFIRM`
   (which `ResultDialog`'s OK button does not currently tag either — see
   `dialog-buttons-untagged` in the maestro skill) and drop `SNACKBAR_SAVED`.
4. **Then sweep the other ~40 flows.** The check that found this is cheap —
   resolve every `id:` in `Maestro/flows/**` and assert each one is produced by
   some `Modifier.testTag` — and should become a script next to
   `Maestro/scripts/check-tags.sh`, which validates *spelling* but not
   *existence*. Every regression-tagged flow is a candidate for the same class
   of rot.

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

**Try next:** a small baseline file (like the docs-audit dead-ref baseline):
known findings listed in `config/`, script subtracts them and exits 0; a new
finding still exits 1. Then add it to `check.sh`. An hour of work, turns a
manual ritual into a gate.

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
