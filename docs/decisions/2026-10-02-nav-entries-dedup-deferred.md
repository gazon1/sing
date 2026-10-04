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
