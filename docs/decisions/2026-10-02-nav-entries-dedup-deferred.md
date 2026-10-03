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
