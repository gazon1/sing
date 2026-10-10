---
title: "Task Detail Coordinator Graph Test Times Out Under Parallel Load"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** #447

**Found in:** 2026-10-04, while verifying the identity-derivation change across three
modules in one Gradle invocation (`:shared:jvmTest :desktopApp:test :mcp-server:test`).

**Symptom:** `TaskDetailCoordinatorGraphTest.coordinator_built_from_di_graph_leaves_loading`
fails with `TimeoutCancellationException: Timed out waiting for 10000 ms` on
`coordinator.state.first { it is Loaded }`. It passes 3/3 when run alone (7-20s per run).

**Not the identity change.** `CurrentUser.currentSession` is a `StateFlow` in both the
interface and `FakeAuthRepository`, so `liveScopedUserId` emits on first collection exactly
as the cached `scopedUserId` did; the only difference is one `combine` operator. The failure
reproduces only when three modules build and run concurrently on this host.

**Why the test uses real time at all:** the coordinator's scope is
`createBackgroundScope()` — real `Dispatchers.Default` — so a `withTimeout` on
`runTest`'s virtual clock would expire instantly instead of waiting for the real worker.
The 10s real-time budget is therefore a deliberate, documented choice, not an oversight.

**Real issue:** a wall-clock budget makes the suite's correctness depend on host load.
The project rule (see `singularity-todo-test-flaky-prevention`) is that tests must not
depend on real elapsed time.

**Try next:** inject a `TestScope`/background scope into `TaskDetailCoordinator` for tests
so the wait becomes virtual-time and instantaneous; failing that, replace the single 10s
budget with a bounded poll that reports the observed wait on failure, so a slow host fails
loudly with data instead of looking like a hang. Do NOT simply raise the number.

---
