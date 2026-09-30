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

**Symptom:** an undated task is written successfully — `observeAll()` returns
it — while the desktop agenda renders "No tasks", and stays empty across a tab
switch that recreates the ViewModel.

**Already ruled out:**

- *Wrong preset* — `AgendaPresets.Inbox` declares a "No Date" section; only
  `Today` does not, and the failing test opened Inbox.
- *Domain / matcher* — `AgendaNoDateRegressionTest` is green: an undated active
  task lands in Inbox → No Date with the right badge, in exactly one section.
- *The 1970 sentinel* — `RelativeBucket.NoDate` maps to
  `DateRange(1970-01-01, 1970-01-01)`, but `SelectorMatcher` special-cases the
  bucket with a direct `task.dueDate == null` branch and never uses the range.
  Reading the enum mapping instead of the matcher is what sent the first
  hypothesis at the wrong layer.

**Try next, in this order:**

1. **Step 2 — Fake ⇄ Room contract.** Add to `FakeRepositoryFidelityTest` the
   comparison that actually discriminates: `observeAll()` (the path that was
   seen to work) against `observeByFilter(TaskFilter.All)` (the path
   `AgendaViewModel` actually uses). Both run on `watchActive`, so they should
   agree; if they do not, the break is in the repository or the DAO rather than
   above it.
2. **Step 3 — ViewModel.** `AgendaViewModel` combines `todayFlow()` with
   `observeByFilter`. Drive it directly with `runCurrent()` (not
   `advanceUntilIdle()`). Green 1–2 plus red 3 localises the break to that
   combine.
3. **Step 4 — uid.** Only if 1–3 are green and the desktop flow is still red.
   The specific suspicion is `ProfileAwareCurrentUser.scopedUserId` being
   `MutableStateFlow(UserId.anonymous)` updated from a collector — an `anonymous`
   placeholder that a real value races, which is the same shape as the
   "orphans anything written in that window" note in
   `2026-09-30-desktop-compose-ui-flow-tests.md`. If confirmed, fix the source
   (seed the value synchronously, or introduce `sealed UserScope { Resolving;
   Ready(id) }`) — **not** a fallback StateFlow alongside the existing one, which
   duplicates state. This affects Android cold start too, so it needs its own
   ADR.

**Do not** close this as "known issue" while the layers above the domain are
unexonerated. The bisect's own rule: "everything green" is not a diagnosis, and
the desktop symptom must be reproduced in a second full-suite run before it is
attributed to the harness.

**Product question, unanswered and cheap to settle:** does a task with a
`startDate` but no `dueDate` count as "No Date"? Schema v16 added start/end
dates and no ADR states the semantic. The rule currently lives twice —
`Selector.DateBucket.NoDate` and `AgendaEvaluator.computeBadge` both test
`dueDate == null` alone. Both should route through a single
`TaskComputed.hasNoDate` so the answer can be given once.

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
