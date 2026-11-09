---
title: Two-instance desktop test harness for sync scenarios
date: 2026-10-29
status: accepted
tags: [testing, sync, desktop-compose, multi-instance]
---

## Context

Three sync scenarios (`SYNC-INCOMING-01`, `SYNC-OFFLINE-01`, `SYNC-PROFILES-01`) are marked `unreachable` on both tiers because they need two devices signed into one account. No harness existed to run two app instances against one (real or faked) server, so the cells were honest but empty.

## Decision

**Design (harness and first carrier implemented):** a desktop-only harness that runs two app instances in one test JVM, each with its own Room database file, sharing one faked sync server.

- Two DBs: `AppDatabaseFactory.build(createSqlDriver(), path)` per instance, paths under a temp dir (`instance-A.db`, `instance-B.db`). Same pattern as `TaskRepositorySyncPropagationTest`.
- Shared server: a `SharedSyncServer` (in-memory event log, shared by both instances) replaces Supabase. Both instances push to and pull from it. This is the faked-server option from the issue; real Supabase stays for manual runs.
- Two Koin graphs: each instance gets its own `KoinApplication` (like `runDesktopAppTest` / `buildDesktopAppKoin`) but wired to the shared `SharedSyncServer` and a per-instance DB path. `TwoInstanceHarness` owns both and exposes `instanceA` / `instanceB` handles. The harness binds a deterministic local auth seam (`FakeAuthRepository`) so carriers can open a sync scope without network I/O.
- Instance control: the harness drives instances through repository/sync APIs with explicit `syncOnce()` round trips (deterministic). A Compose-scene-per-instance variant remains possible; try in-process first if UI-level assertions are needed.

**Order of coverage:** `SYNC-INCOMING-01` first (typing not overwritten by incoming change) — landed in #2572; then `SYNC-OFFLINE-01` (queue survives restart, flapping connection); then `SYNC-PROFILES-01` (profile follows account).

**Reachability update:** a scenario's `unreachable` list is edited only when a carrier (a desktop test with `@DisplayName("SYNC-…-01 …")`) lands. This ADR alone does not remove any `unreachable` entry.

## Consequences

- Implemented: `SharedSyncServer` (shared test double), `TwoInstanceHarness` (desktop test helper), and the `SYNC-INCOMING-01` desktop carrier (`SyncIncoming01ScenarioTest`, #2572). Carrier tests for `SYNC-OFFLINE-01` and `SYNC-PROFILES-01` do not exist yet.
- The harness reuses existing building blocks (`AppDatabaseFactory`, `buildDesktopAppKoin` / `runDesktopAppTest` assembly, `Fake*` doubles); it adds no new dependency.
- Risk: two Compose scenes in one JVM may fight over the Skiko/GL context; if a UI-level carrier needs that, fall back to process-level (two JVMs sharing a file) — but that loses the deterministic clock. The current `SYNC-INCOMING-01` carrier avoids this by driving graph/repository state and draft behavior directly.

## Links

- Issue #1390
- `TaskRepositorySyncPropagationTest.kt` — template for per-instance DB setup
- `desktopApp/.../DesktopAppHarness.kt` — `runDesktopAppTest` Koin assembly to reuse
