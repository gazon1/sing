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
