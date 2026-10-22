---
title: AndroidNavEntries and JvmNavEntries are intentionally NOT fully deduplicated
date: 2026-10-02
adr-number: 2026-10-02-nav-entries-dedup-deferred
status: accepted
tags: [architecture, navigation, android, desktop]
---

# AndroidNavEntries and JvmNavEntries are intentionally NOT fully deduplicated

## Context

Phase 8 asked whether `AndroidNavEntries.kt` (260 lines) and `JvmNavEntries.kt` (258 lines) could be
deduplicated. The two files are ~85% structurally identical — same `entryProvider` DSL, same
top-level entries (Plans, Pomodoro, Statistics, Calendar, Notes, AiChat, Search, Archive, Settings,
AiUsage, ProfileSwitcher), same sub-route entries (ProjectEditor, ProjectDetail, ProjectsGraph,
TasksGraph, NotesGraph, CalendarGraph, AgendaGraph).

## Idea

Extract the common `entry { }` lambda bodies into a shared `CommonNavEntries` builder in
`commonMain`, with platform-specific `createAppEntryProvider` functions that call it. This would
reduce duplication to ~10%.

## Decision

**Do not deduplicate.** The platform-specific differences are architectural, not incidental:

1. **NavBackStack management.** JVM's `rememberInMemoryNavBackStack` is a JVM-only API.
   Android does not use it. This is the core structural difference — AgendaGraph and TasksGraph
   on JVM need a custom backstack to preserve nested navigation state across tab switches.
   On Android, Nav3's own state management handles this.

2. **No shared `@Composable` layer exists.** The `entry { }` lambda is `@Composable`.
   Moving it to `commonMain` would require `expect`/`actual` for `entryProvider`,
   `rememberInMemoryNavBackStack`, and all nav graph constructors — adding more complexity
   than it removes.

3. **The remaining delta is small.** After the Phase 8 TasksByProject removal, the only
   substantive differences are:
   - JVM: creates `NavBackStack` + passes `backStack =` param for AgendaGraph and TasksGraph
   - Android: does not create NavBackStack, does not pass backStack param
   - Import differences (JvmNavEntries imports `remember`, `NavBackStack`, `rememberInMemoryNavBackStack`)

## Consequences

- ~15% apparent duplication remains. This is intentional and reflects genuine platform differences.
- Future nav changes must update both files. Using a comparative diff on every change is required.
- If `rememberInMemoryNavBackStack` is ported to Android, revisit this decision.

---

## Addendum (2026-10-04) — decision confirmed; the convertible subset was extracted

Re-measured after `2026-10-04-navigation-policy` landed, to check the "watch for platform
divergence" criterion. **The decision above stands, and the criterion is met in the sense that
matters: the two files have not gained divergent destinations.** The set of `AppDestination`
references in each is identical.

The entry *bodies*, however, are not redundant — the divergence is architectural, exactly as
this ADR argued, and it is now measured rather than asserted:

| | `AndroidNavEntries.kt` | `JvmNavEntries.kt` |
|---|---|---|
| `backStack = …` arguments | 0 | 6 |
| `rememberInMemoryNavBackStack` | 0 | 2 |
| explicit `tasksStack.add(startRoute)` | 0 | 1 |
| hoisted `agendaStacks[…]` | 0 | 1 |

Android lets each graph own its stack through `rememberNavBackStackTyped` (saved-state backed);
JVM hoists the top-level stacks into the shell so they survive an entry leaving `NavDisplay`'s
visible set. A common body would have to abstract "does this graph get a hoisted stack" and
"does it need the explicit `add`", which is design work with real regression risk in the least
observable part of the app — and the Android side of it cannot be exercised here (Maestro's
device server does not start on this host). **Stays deferred.**

What *was* pure duplication, and is now gone: the four start-route converters
(`toTasksRoute`, `toProjectsRoute`, `toNotesRoute`, `toCalendarRoute`) were `private` copies
in both files. That duplication was the dangerous kind — each `when` is exhaustive over its own
sealed hierarchy, so adding a variant to one file and forgetting the other compiles cleanly in
both and diverges only at runtime, as a screen that opens the wrong graph. They now live once in
`commonMain` as `internal` in `NavStartRoutes.kt`, where the compiler checks both platforms at
once.

Links: ADR `2026-10-04-navigation-policy` (the policy that made the allow-lists disappear),
`shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/NavStartRoutes.kt`.

---

## Addendum (2026-10-21) — the premise was wrong; the deduplication happened

**The decision above is reversed.** Not because the platform differences turned out to be
incidental, but because the ADR's second reason was false: "No shared `@Composable` layer
exists." One did — it was one artifact away.

`androidx.navigation3:navigation3-ui` publishes **no desktop variant**. The Compose
Multiplatform port lives in a separate artifact, `org.jetbrains.androidx.navigation3:navigation3-ui`,
whose Android variant is a *redirect* to the androidx artifact and whose Desktop variant
carries the port. That one artifact puts `NavDisplay` into `commonMain`, which is what made
"a shared `@Composable` layer" possible at all. The ADR never checked whether the UI artifact
had a common variant; it checked the runtime's.

The other two reasons survive, and are now *seams* rather than blockers:

1. **NavBackStack management** → the seam `rememberAppNavBackStack`. Android is saved-state
   backed, Desktop is in memory, and the graphs ask the seam instead of branching.
2. **The remaining delta is small** → it was small, and the small parts were hoisted stacks
   (Desktop) and the tasks graph entry. Both are now *parameters*: the hoisted stacks arrive
   as `AppNavStacks`, and the tasks graph entry stays in the two platform files.

What the addendum of 2026-10-04 called "the entry bodies are not redundant" was true of the
*bodies* and irrelevant to the conclusion: the bodies could have been common all along, once
`NavDisplay` was reachable from `commonMain`.

### What the deduplication cost

Two seams and one measurement:

- `rememberAppNavBackStack` — the stack's storage.
- `SystemBackHandler` — the system back gesture. It replaced two `expect`/`actual` triples
  (`TasksBackHandler`, `ProjectsBackHandler`) that each said the same thing twice.
- `rememberAppEntryDecorators` — the ViewModel-store decorator. **This one was not a
  difference at all; it was a bug.**

### The bug the measurement found

The Desktop graphs had no ViewModel-store decorator, and their KDoc claimed that "each
NavDisplay entry already has proper per-entry ViewModel scoping" on Desktop. Nothing in nav3
provides that scoping — `rememberViewModelStoreNavEntryDecorator` is the only thing that does.
`NestedEntryViewModelScopeTest` measured it: on Desktop, a nested entry inherits the store of
whatever is above it.

Koin keys a ViewModel by its class alone, **not** by the parameters. Two entries sharing a
store therefore get the *same* ViewModel instance: the second screen silently reuses the first
one's state, with the first one's parameters. On Android the decorator hid this; on Desktop it
was live.

The decorator is now applied on both platforms, from one common implementation.

### Consequences

- **Always** put a nested graph's `NavDisplay` in `commonMain`. The platform difference is a
  seam, not a reason for a second copy of the graph.
- **Never** assume a nav3 artifact has no common variant. Check the *root* module of the
  multiplatform line, not the platform variant.
- **Never** let a platform file claim a scoping behaviour that was never measured.
  `NestedEntryViewModelScopeTest` exists because a comment was not a measurement.
- The task's `TasksGraph` entry stays platform-specific: Desktop seeds the pushed graph's
  stack itself and pushes the detail route onto it. That one is a real difference, and it is
  now the *only* `entry<>` block left outside the common contributors.

Links: ADR `2026-10-21-nav3-1-2-0-upgrade` (the upgrade that brought the artifact),
`shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/entries/`,
`docs/navigation.md`.

## Addendum (2026-10-22) — the tasks entry is no longer a real difference

The bullet above — "The task's `TasksGraph` entry stays platform-specific: Desktop seeds the
pushed graph's stack itself and pushes the detail route onto it. That one is a real
difference" — no longer holds, and the reason it was written down is gone.

The difference it described had two parts: Desktop seeded the pushed graph's stack itself,
and Desktop pushed the detail route onto that seed. The second part was the defect: a
placeholder seed with the requested route on top put a screen nobody asked for underneath the
one they did, and dropped the parameters the route carried. With the seed now being the
requested route (ADR `2026-10-03-nav3-backstack-top-vs-start-dispatch`, addendum of
2026-10-22), the push is gone.

What is left of the difference is the stack's *backing* — Desktop keeps it in memory,
Android keeps it in saved state — and that difference was never the entry's to make. It
belongs to the `rememberAppNavBackStack` seam, which picks the right backing per platform
and is already called by every other graph. The entry keeps its platform-specific shape for
now, because the OpenSpec change that covers this work says so, and because the two files
are now guarded by a rule that fails when they ask for different routes
(`NavStackSeedTest`).

The general rule this ADR already states still stands, and this case is the next one to
answer it: **the platform difference is a seam, not a reason for a second copy of the
graph.** When the tasks entry is next touched, the honest move is to move it into the
common contributors and let `TasksNavGraph` ask `rememberAppNavBackStack` for its stack —
the copy in `JvmNavEntries.kt` and the copy in `AndroidNavEntries.kt` are now identical
except for the line that creates the stack.

Links: ADR `2026-10-03-nav3-backstack-top-vs-start-dispatch` (addendum 2026-10-22),
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/nav/NavStackSeedTest.kt`,
OpenSpec change `the-desktop-task-stack-starts-elsewhere`.
