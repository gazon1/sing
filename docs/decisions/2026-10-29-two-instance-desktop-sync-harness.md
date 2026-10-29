---
title: Two-instance desktop test harness for sync scenarios
date: 2026-10-29
status: accepted
tags: [testing, sync, desktop-compose, multi-instance]
---

## Context

Three sync scenarios (`SYNC-INCOMING-01`, `SYNC-OFFLINE-01`, `SYNC-PROFILES-01`) are marked `unreachable` on both tiers because they need two devices signed into one account. No harness existed to run two app instances against one (real or faked) server, so the cells were honest but empty.

## Decision

**Design (not yet fully implemented):** a desktop-only harness that runs two `App` instances in one test JVM, each with its own Room database file, sharing one faked sync server.

- Two DBs: `AppDatabaseFactory.build(createSqlDriver(), path)` per instance, paths under a temp dir (`instance-A.db`, `instance-B.db`). Same pattern as `TaskRepositorySyncPropagationTest`.
- Shared server: a `FakeSyncServer` (in-memory, keyed by account/profile) replaces Supabase. Both instances push to and pull from it. This is the faked-server option from the issue; real Supabase stays for manual runs.
- Two Koin graphs: each instance gets its own `KoinApplication` (like `runDesktopAppTest`) but wired to the shared `FakeSyncServer` and a per-instance DB path. A `TwoInstanceHarness` owns both and exposes `instanceA`/`instanceB` handles for Compose tests.
- Instance control: each instance is a `runDesktopComposeUiTest`-style scene; the harness drives them sequentially within one test (no parallelism) to keep the Compose clock deterministic. Cross-instance sync is triggered explicitly by calling `pull()` on the receiving instance rather than by a background timer, so tests are deterministic.

**Order of coverage:** `SYNC-INCOMING-01` first (typing not overwritten by incoming change), then `SYNC-OFFLINE-01` (queue survives restart, flapping connection), then `SYNC-PROFILES-01` (profile follows account).

**Reachability update:** a scenario's `unreachable` list is edited only when a carrier (a desktop test with `@DisplayName("SYNC-…-01 …")`) lands. This ADR alone does not remove any `unreachable` entry.

## Consequences

- New code needed: `FakeSyncServer` (shared), `TwoInstanceHarness` (desktop test helper), and at least one carrier test per scenario. None exists yet — this ADR records the design so the implementation can be picked up without re-deriving it.
- The harness reuses existing building blocks (`AppDatabaseFactory`, `runDesktopAppTest`'s Koin assembly, `FakeSyncRepository`-style fakes); it adds no new dependency.
- Risk: two Compose scenes in one JVM may fight over the Skiko/GL context; if that fails, fall back to process-level (two JVMs sharing a file) — but that loses the deterministic clock. Try in-process first.

## Links

- Issue #1390
- `TaskRepositorySyncPropagationTest.kt` — template for per-instance DB setup
- `desktopApp/.../DesktopAppHarness.kt` — `runDesktopAppTest` Koin assembly to reuse
