---
title: "Today" is two parameters, and neither of them may be defaulted
date: 2026-10-05
status: accepted
tags: [clock, testing, testability, di, scenarios]
---

## Context

#91 was filed because a desktop flow test could not pin a date. The calendar
rendered October on a September host, the injected clock never arrived, and the
attempt was reverted. The issue's own advice — "thread an injected clock into the
calendar content" — turned out to be the second-order part.

Measuring first changed the picture. The port already existed and was already
used: `todayAt(clock, zone)`, 52 call sites injecting `Clock`, a `FakeClock` in
`commonMain`, and a `TimeZoneProvider` registered in `CoreDiModule`. The defect
was one top-level function and 17 calls to it:

```kotlin
fun todayInSystemZone(): LocalDate = todayAt(TimeZone.currentSystemDefault())
```

with a defaulted zone on `todayAt` itself, so even a caller that *did* inject a
clock got a date that moved with the machine's region. Deterministic over time,
not over machines.

## Decision

**Both parameters required. Neither defaulted.**

```kotlin
fun todayAt(clock: Clock, zone: TimeZone): LocalDate
```

**The global accessor is renamed, not kept.** `todayInSystemZone()` →
`systemToday()`. Same body; a name that says what it costs the caller. The old
name read like a neutral accessor, which is why 17 sites used it as one, and two
spellings of "today" is one more than a caller should choose between. It now has
5 callers, all of them places where the host's date genuinely *is* the answer: a
calendar deep link that means "this month", a `@Preview`. `RelativeBucket.kt`'s
`inline fun todayInSystemZone()` re-export is deleted for the same reason.

## Rationale

**Why a required parameter rather than a defaulted one.** A default is a way of
forgetting. `CreateTaskFromDraftUseCase` already took a `Clock` and called the
global helper four times — the injection was decorative for exactly the values
that matter, and it would have stayed decorative with a defaulted zone, because
nothing about the signature would have changed. A required `zone` makes the
question unavoidable at every call site.

**Why the zone is a parameter and not a `ClockProvider` port.** A single
`ClockProvider` carrying both would be cleaner as an architecture, and it would
have touched all 52 injection sites rather than 9. The two-parameter form is
what the existing `todayAt` already had; the change is that one of its parameters
stopped being optional.

**Why four sites got a different shape of fix.** The issue's framing was "thread
a clock into the UI", and for three of the four that was the wrong diagnosis:

- `CalendarDiModule` computed `today` *inside the DI module*, at
  graph-construction time. The value was fixed before any test could reach it —
  which is the actual reason the flow test could not pin a date, and no amount of
  clock-threading into the content would have fixed it. It now resolves
  `Clock` and `TimeZoneProvider` from the graph, both of which were registered
  and unused here.
- `CalendarContent` took `today: LocalDate = todayInSystemZone()` **while the
  `CalendarUiState.Loaded` it was passed already carried `today`**. Two sources
  for one fact, and the screen's copy was the one no test could set. The
  parameter is gone. This was not on any list; it surfaced as a compile error
  when the import was removed, which is the cheapest possible way to find it.
- `CreateTaskFromDraftUseCase` also computed `dueDate` and `resolveDate` as two
  copies of the same `when` over the same value. One `today` now, used by both.

**Why two test files changed more than the signature required.** `FabActionResolverTest`
computed `todayInSystemZone()` and compared it against the value the resolver had
computed from a second call to the same function. It proved two reads of the
clock agreed — a test that cannot fail on the thing it names. With the date
supplied it pins `LocalDate(2026, 9, 16)` and can fail.
`AgendaViewModelTest` carried a comment explaining that its instant had to be
*noon* "or this test fails in half the world's time zones". That was a real
constraint and it was a workaround for the missing zone parameter: with the zone
pinned to UTC the instant can be 00:30, and the comment is gone with it.

**Why the 20 clock reads in `FakeRepositories.kt` were left alone.** A fake that
stamps entities with the wall clock makes every assertion about ordering or
staleness non-deterministic, so a fake with a fixed clock is the better fake.
But that fake is shared by the whole test tree, and threading a clock through
thirteen files is a different piece of work from making production code
injectable. `isAllowedPath` in `NoDirectClockSystemRule` was given a
`/test/fakes/` entry as part of #189 — that is a statement about *where fakes
live*, and the file's own header says it is not an endorsement of reading the
system clock from one.

## Consequences

- `todayAt` has no defaults. The only caller that reads a system clock is
  `systemToday()`, which says so by name.
- `DefaultSearchQueryResolver` and `AgendaDeps` gained a required
  `TimeZoneProvider`; `CreateTaskFromDraftUseCase` gained a required
  `TimeZoneProvider`; `AdrStorage`, `RemoteConfigCacheRepositoryImpl`,
  `NoteEditor` and `ProfileSwitcherViewModel` gained a `Clock` parameter.
- `TEST_TZ` moved from `SlotTestFixtures.kt` to
  `com.singularity.todo.test.fakes.CommonFakes.kt`. A zone declared per test file
  is a second thing to keep in sync with the clock it belongs to, and the clock
  is now injected in two places that both need one.
- **Three tests are new, and they are the first date assertions in the
  repository that can fail.** `SearchQueryResolverTest` had eleven parser tests
  for `due:` and zero resolver tests. `due:today` → `2026-09-16` and
  `due:3d` → `2026-09-19` are now assertable, because the date is a value the test
  supplies. Both assert the range's **upper** bound: a bare `due:` resolves to
  `Relation.LE`, and an LE range is open-ended below by definition
  (`SearchQueryResolver.kt:288-293` pins `from = 1970-01-01`). The first version
  of these tests asserted `from` and read 1970-01-01 — correct behaviour, wrong
  expectation, and the clearest sign the injection was working: the clock's
  influence is on the bound it was always on, and asserting the other end proved
  nothing about it.
- **The third of those three tests asserted the opposite of the property, and
  passed on a resolver that ignored its clock entirely.** It advanced the fake
  clock by three days and asserted the resolved date had *not* moved. That
  certifies the very defect this change exists to remove. It now asserts the date
  *does* follow the clock, which is what makes the injection load-bearing: a
  resolver still reading the system clock does not move three days in a suite
  that runs in seconds. Two of the three new tests were wrong on first run, in
  opposite directions — one asserting a bound the clock does not touch, the other
  asserting stability the clock must break. Neither was a defect in the
  production change; both are recorded because a date test written by reasoning
  about the feature rather than by reading the resolver is exactly as unreliable
  as a date test written against a system clock.
- The four `LIVE DEFECT` entries in `scripts/check-suppression-intent.py` are
  removed, and the gate is green on the remaining six. Two of the four files no
  longer carry a suppression at all; the other two had the clock threaded in.
- `CalendarPreview`'s sample data is pinned to a fixed date. It read
  `todayInSystemZone()`, which meant the preview highlighted a different cell
  from one day to the next — the same host-dependence, in a file nobody had
  checked.
- **A sixth live defect, found while sweeping for what was left**:
  `TimeEntryEditorSheet` prefilled the *end* of a new time entry with
  `Clock.System.now()` read inside the composable. A form deciding what to
  prefill from the host's wall clock is the same class as the calendar's DI
  module: the value is fixed before any test can reach it. `now` is now a
  required parameter, threaded from the screen through `TaskEditorSheetsHost`.
  This one was not in the registry — the gate polices *file-level suppressions*,
  and this file had none, because the value is not the bug; the read is. **A
  suppression gate does not see a defect that was never suppressed**, which is
  the limit of that gate and worth stating rather than discovering twice.
- **A seventh, and the reason two desktop flow tests failed on the first run:**
  `createJvmEntryProvider` and the FAB prefill each read `systemToday()`
  *outside* the Koin graph, in `remember { }` at the top of a composable. Binding
  a fixed `Clock` in a test's module therefore did not reach them — the harness
  supplied one clock, the shell read another, and `CalendarFlowTest` rendered the
  host's month while asserting the pinned one. This is the sharpest form of the
  defect: an injection that works for everything *except* the two places that
  reach around it. `today` is now a parameter of the entry provider, and
  `PlatformShell` supplies it from the graph.
- **Both shells had it, and the desktop one was found by a failing test rather
  than by reading.** `App.kt` already resolved two ports with `koinInject` two
  lines above the shell call, so the fix had a precedent in the same function;
  `androidShellNav3Root` had the identical reach-around for its FAB prefill and
  was fixed on sight. The general rule this leaves: **a shell composable that
  computes a date for itself is not injectable, and nothing in the type system
  says so.** `koinInject` throws in `@Preview`, which is why the fix is a
  parameter with the resolution one level up in `App.kt` rather than a
  `koinInject` inside the shell — both shells have no `@Preview`, but the
  parameter is what makes that a decision rather than an accident.
- **An eighth, and the one that needed a whole module change:**
  `todayFlow(zone = TimeZone.currentSystemDefault())` read `Clock.System` through
  the other `todayAt` overload. `AgendaViewModel` composes its *entire* task
  stream from it — `todayFlow().flatMapLatest { today -> watchTasks(…, today) }` —
  so the agenda's notion of today was a second un-injectable source, and it is
  the one that decides whether a task lands in the "Tomorrow" section.
  `AgendaTabDefinitionFlowTest` failed with **"All available tags (0 total)"**
  after the shell fix, which is what surfaced it: a task dated tomorrow-relative
  to the *pinned* clock was being bucketed against the *host's* date, so it
  matched no section at all. `todayFlow(clock, zone)` now takes both, and
  `delayUntilNextMidnight` takes the clock too — it was reading `Clock.System`
  to work out how long to sleep, which is the same defect one function deeper.
  `NotesListViewModel` also composes a `todayFlow` and got the same treatment.
  and `WriteToolsTest` failed with `NoClassDefFoundError: …TaskDao` in a run that
  also had two Gradle builds racing on the same tree — a clean-tree probe and a
  verification run started before the probe's `git stash pop`. Re-running those
  two classes alone, on the same working tree, passed. They are not in the
  baseline and nothing was changed to make them pass. Recorded because the
  temptation on seeing `NoClassDefFoundError` is to attribute it to whatever
  change is in front of you, and here the change was innocent.
- **A measurement caveat worth recording.** During this work,
  `ReadToolsProfileAwareTest` and `WriteToolsTest` failed with
  `NoClassDefFoundError: …TaskDao` in a run that also had two Gradle builds
  racing on the same tree — a clean-tree probe and a verification run started
  before the probe's `git stash pop`. Re-running those two classes alone, on the
  same working tree, passed. They are not in the baseline and nothing was changed
  to make them pass. Recorded because the temptation on seeing
  `NoClassDefFoundError` is to attribute it to whatever change is in front of you,
  and here the change was innocent. **Do not run two Gradle builds against one
  worktree**, which is the actual lesson and the reason this is here.
- **The desktop Compose tests need an X server, and losing it looks like a code
  failure.** `DISPLAY` was set to `:0` with no server behind it partway through
  this work, and `AgendaTabDefinitionFlowTest` went from two assertion failures
  to three `java.awt.AWTError: Can't connect to X11 window server` — a
  *different* failure with the same test names. Read the exception type before
  concluding anything: `AWTError` is the environment, an assertion is the code.
  A test whose failure message changes character between runs is reporting its
  harness, not its subject.
- **Eight desktop flow tests were reading the host's date.** `runDesktopAppTest`
  has accepted a `clock` parameter since the first date flake, and
  `AgendaBadgePolicyFlowTest`, `OpenTaskFromAgendaFlowTest`, `CalendarFlowTest`,
  `NavigationFlowTest`, `AgendaTabDefinitionFlowTest`, `SetDueDateFlowTest`,
  `SetPriorityFlowTest` and `TaskRowFlowTest` were not passing one — they called
  `todayInSystemZone()` and asserted against the host's real date. The harness
  was ready; the tests were not using it. `CalendarFlowTest` is the file the
  issue's evidence section is about, and it now pins 2026-09-16.
  The shape is the one `AgendaBadgePolicyFlowTest` already used — a
  `private companion object` holding a pinned instant and its `FakeClock`, next to
  a `today` derived from the same day. That file was the one place in the test
  tree that got this right, and the first version of this change invented a
  different shape for the other seven; the existing pattern won, because a
  convention with one precedent is cheaper than a convention with none.
  `AgendaBadgePolicyFlowTest` keeps **two** instants, since two of its tests are
  about a distance in time rather than about a date.

## An operational note: `git checkout -- .` after `stash pop`

Partway through, a script that sorted import blocks touched **431 files** — every
Kotlin file in `shared` and `desktopApp` — where the intent was eight. The
response was a `git checkout` loop over everything not matching a filename pattern,
and then, in the middle of judging whether the cleanup had worked,
`git stash && git stash pop && git checkout -- .`. That last command discarded the
entire uncommitted body of this work: 48 files, roughly 550 insertions, including
the `todayFlow` fix, both `App.kt` files and both entry providers.

It was recovered from `git fsck --no-reflogs --lost-found`, which surfaced two
`WIP on feat/test-scences` stash commits — one of 47 files (the real work) and one
of 444 (the state *after* the bad sort). Restoring the right one and re-applying
the four known edits took about ten minutes, because the edits were made through
scripts whose intent was recorded in the ADR and the issue comments.

Three things are worth keeping from this:

- **`git fsck --lost-found` finds stash commits that `git stash pop` dropped.** A
  popped stash is a dangling commit, not garbage-collected, and it keeps the exact
  tree. This is the difference between ten minutes and rewriting an afternoon.
- **A filename allowlist in a cleanup loop is not a safety net.** The loop
  reverted 431 files correctly and the *check* afterwards was on a tree that had
  already been damaged, so the check could not have caught it.
- **Scope a bulk script by the files you touched, not by a pattern.** The
  import-sorter had no such bound and read the whole repository. It is also worth
  noting what the sorter was trying to fix: 7 ktlint findings, in files I had
  already modified. Running a repository-wide formatter to fix findings in eight
  files is a different act, and it should have been a smaller one.

## A note on how the eight test files were edited

The eight desktop flow tests needed one mechanical change each — pin the date,
pass the clock — and that is the kind of edit a script should do. Three attempts
did it wrong, and the failures are worth naming because two of them produced
*compiling* code:

1. A regex that inserted the declaration assumed a class-level `today` field. In
   three files `today` was a local `val` inside each test method, so the
   declaration landed nowhere and `NOW` was unresolved.
2. A line-wrapper that put `runDesktopAppTest(` on its own line did not re-indent
   the lambda body. It compiled; detekt reported **94** findings, 31 in one file.
3. An unwrap that rebuilt the call from the wrapped form **dropped the comma
   between arguments** — `clock = CLOCK checkA11y = true`. That is a syntax error,
   and it is the only one of the three that a compiler caught.

So: a bulk edit of eight files produced three rounds of breakage, and the
detekt-first discipline is what caught two of them. The successful version was
the smallest one — shorten a constant name from `PINNED_CLOCK` to `CLOCK` and let
the line fit — which is a reminder that the fix for "this line is too long" is
usually not a reflow.

## What is left, and why it was not done

A scan of production sources for `Clock.System.now()` outside `test/fakes/`
returns 15 sites, down from 38 measured at the start of the suppression triage.
Of those 15: **9 are `@Preview` fixtures** (hard-coded sample data, not
behaviour), **1 is `FileLogWriter`**, **1 is `systemToday()` itself**, and
**3 are the reach-around in the task-detail path** —
`TaskDetailViewScreen.kt:257` and `TaskEditorContent.kt:374` (both mine: I made
`TimeEntryEditorSheet` take a required `now` and then had the screen supply it
from `Clock.System`, which moves the read rather than removing it) and
`TaskEditorSheetsHost.kt:129`, which predates this work.

The fix shape is the shell fix again: resolve `Clock` and `TimeZoneProvider`
once in `App.kt` and thread one `now` down `PlatformShell` → task-detail screen →
`TaskEditorContent` → `TaskEditorSheetsHost`. It is four signature changes in
composables that only `desktopApp`'s Compose tests exercise, and this host has
no X server (#201), so **it is recorded rather than done**. A four-deep
signature chain that no test can execute is the exact shape of change this
issue spent its length arguing against, and the last hop in particular is easy
to get wrong with nothing to catch it.

**How it is recorded matters, and the first attempt did not work.** The first
place to try was `NoDirectClockSystemRule.isAllowedPath`, alongside the
`/test/fakes/` entry added for the fakes. The rule's own unit test passed, the
compiled class contained both new string literals, `:detekt-rules:jar` reported
`up-to-date` — and detekt kept reporting both files, with a report timestamp that
advanced on every run, so the task was genuinely re-executing against a class
that provably had the entries. An allow-list entry nobody can see taking effect
is worse than no entry: it looks like the decision was made and was not.

So the two screens carry `@file:Suppress("NoDirectClockSystem")` with the reason
inline, and `check-suppression-intent.py` fails the build if the reason is not
there. That is the mechanism this session built, applied to its own
commitment: the enforcement is a gate, not a list in a rule nobody can verify is
loaded. The suppression is also where a reviewer looks — at the file that reads
the clock — rather than in a rule four modules away.

What *is* verified: `:shared:jvmTest` is green at 224 classes including three
date assertions that could not previously be written; `CalendarFlowTest` went
green after the shell fix, which is the substantive claim in the issue's own
evidence section; and the eight desktop flow tests are pinned to a fixed date
and compile. The remaining seven need a re-run on a host with a display.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/platform/Clock.kt` —
  `todayAt(clock, zone)`, `systemToday()`
- `scripts/check-suppression-intent.py` — the registry whose `LIVE DEFECT`
  section this emptied
- `docs/decisions/2026-10-05-positive-control-registry-is-derived.md` — the
  derived-registry argument this follows
- `openspec/specs/test-execution-integrity/spec.md` — a scenario that ran without
  reporting is a failure; a scenario whose date is the host's cannot be written
