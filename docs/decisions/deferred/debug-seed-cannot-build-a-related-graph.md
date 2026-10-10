---
title: "Debug Seed Cannot Build A Related Graph"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#171](https://github.com/gazon1/sing/issues/171)

**Found in:** the same plan, 0C.1, and re-confirmed on 2026-10-05 while checking
which of the queued scenarios have a prerequisite rather than only a missing test.

**Situation:** `DebugSeedActivity` dispatches on a single key and the first matching
branch wins, so one invocation seeds **one object**. Three queued scenarios need a
graph: a subtask attached to its parent, a task with time entries, a populated
database to round-trip. Adding branches does not reach that — the branches cannot
reference each other and nothing is atomic, so a failure halfway leaves a half-seeded
database that the next assertion reads as real data.

**Already ruled out:** seeding by writing a backup and restoring it. Seven tables sit
outside the backup payload (#77), so that route silently omits them and the scenario
tests a subset of the database while claiming to test all of it.

**Try next:** a JSON payload describing the object graph, deserialised into a
`sealed interface SeedItem` and applied through the existing use cases in one
transaction. Through use cases, not DAOs: a scenario must not be able to construct a
state the app itself could not produce, because that is the property that makes it
worth having. The same model should serve the desktop Compose harness, with the shared
part in the test support module. Do not build it speculatively — `TASK-SUB-01` is the
first scenario that needs it, and its requirements are the honest ones.

---
