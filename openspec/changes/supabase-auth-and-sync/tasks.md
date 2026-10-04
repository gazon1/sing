# Tasks — supabase-auth-and-sync

Order matters. Every group leaves the build green. Test names are the ones to write;
a task is not done until its test passes.

---

## Phase 0 — Specification

- [x] 0.1 `openspec/` — write proposal, design, and the two capability specs
      (`offline-sync`, `user-authentication`)
- [x] 0.2 `docs/decisions/` — ADR for the per-field conflict policy
- [x] 0.3 `docs/decisions/` — ADR for SQL-over-edge-functions
- [x] 0.4 `openspec/` — `openspec validate --all --strict` clean

## Phase 1 — Pre-backend cleanup

These three are fixed before a backend exists, because without one the defects are
invisible and with one they become irreversible data loss on the user's own data.

- [x] 1.1 `shared/` — delete the unwired OAuth subsystem (4 files) and its tests;
      close backlog entry `core-auth-oauth-is-entirely-unwired`; remove its two
      baseline lines. Verified by: dead-symbol detector reports no removed symbols
- [x] 1.2 `scripts/find-unwired-surfaces.py` — strip comments and string literals
      before counting references. Verified by: detector now reports the auto-sync
      class as unwired
- [x] 1.3 `shared/` — remove the auto-sync entry point and its test, which test a
      class nothing calls. Verified by: `find-unwired-surfaces.py` clean again

## Phase 2 — Sync core refactor

- [x] 2.1 `shared/` — single-owner sync cycle via a conflated channel with one
      consumer; delete the lock-and-recursion coalescing.
      Verified by: `SyncCoordinatorCoalescingTest` — ten concurrent requests yield one
      cycle plus at most one follow-up (REQ-OS-008)
- [x] 2.2 `shared/` — report unappliable events and stop advancing the cursor past
      them. Verified by: `SyncEnginePullTest` — an event of an unhandled type leaves
      the cursor unmoved and the count above zero (REQ-OS-007)
- [x] 2.3 `shared/` — exponential per-patch backoff with a cap, a maximum attempt
      count, and a dead-letter store. Verified by: `SyncEnginePushTest` — a patch past
      the attempt limit is in the dead-letter store and not retried (REQ-OS-010)
- [x] 2.4 `shared/` — move sync state from preferences into the database, keyed by
      owner and profile; Room 33 → 34; adopt the existing values once, at first read.
      Verified by: `SyncStateMigrationTest` — cursor for one profile is invisible to
      another (REQ-OS-009)
- [x] 2.5 `shared/` — build patches as a diff against the last uploaded state, with a
      logical clock; remove the row checksum and the code computing it. The base is
      `sync_shadow` (Room 34 → 35), holding both the confirmed state and the
      in-flight one so a queued patch's fields are not re-sent. `ConflictResolver` and
      its JVM-only SHA-256 are deleted; the outbox now coalesces per entity.
      Verified by: `BuildPatchDiffTest` — a one-field edit yields exactly one field
      operation, and a stale response cannot promote a superseded patch's state
      (REQ-OS-002); `SyncEnginePushTest` — the shadow advances only on acceptance
- [x] 2.6 `shared/`, `androidApp/` — one sync driver per platform; remove the
      alarm-based scheduler. Verified by: architecture test asserting one driver per
      platform source set
- [x] 2.7 `shared/` — tests for the sync engine and the pull dispatcher, which
      previously had none. `SyncEnginePullTest` (7), `SyncEnginePushTest` (8),
      `SyncBootstrapperDispatchTest` (3). The dispatch table is asserted because the
      pull loop now *stalls* on an event it cannot apply, which turns a missing
      handler from silent loss into a permanent block (REQ-OS-011)
- [ ] 2.8 `shared/` — drop the user-id parameter from the sync transport, so the
      server derives identity from the session rather than trusting a value the client
      supplies. Verified by: existing transport tests compile unchanged
- [x] 2.9 `shared/src/commonMain/kotlin/com/singularity/todo/core/files/` — the digest
      helper used for attachment checksums called a JVM-only API from `commonMain`.
      Replaced with okio's `sha256()`, and `CommonMainJvmApiTest` now fails on any new
      `java|javax|android` import in `commonMain`. Verified by: existing attachment
      checksum tests pass

## Phase 3 — Server schema

- [ ] 3.1 server — migration: profile table, six document tables, event log, field
      allowlist, applied-patch table
- [ ] 3.2 server — row-level access policies on every synchronised table, keyed on the
      session's owner id via a scalar subquery so the index is used
- [ ] 3.3 server — batch application function: idempotent by patch identity, per-field
      merge, all-or-nothing, security-definer with pinned search path
- [ ] 3.4 server — event-feed function, owner-scoped; revoke direct access to the log
- [ ] 3.5 server — ownership-transfer function, refusing any identity that is not the
      caller's own. Verified by: integration test — another user's data is not
      claimable (REQ-UA-005)
- [ ] 3.6 server — health check reachable under a signed-in session
      Verified by: integration test returns ok

## Phase 4–6 — Client transport

- [ ] 4.1 `shared/` — client factory; URL and key from the secure store with a
      build-time fallback for development
- [ ] 5.1 `shared/` — add a profile dimension to pulled events; drop events belonging
      to another profile before applying. Verified by: `SyncBootstrapperDispatchTest`
      (REQ-OS-011)
- [ ] 6.1 `shared/` — implement the sync transport over the RPC endpoint; map failures
      onto the existing error type. Verified by: `SyncApiClientTest` — result parsing,
      sequence numbers without precision loss, RPC failure as an error not an
      exception
- [ ] 6.2 `shared/` — architecture test: vendor SDK imports confined to the transport
      and authentication seams (REQ-OS-014, REQ-UA-008)

## Phase 7–8 — Auth

- [ ] 7.1 `shared/` — session store over the secure store; migrate a plaintext token
      on first read and erase it. Verified by: `SecureSessionStoreTest` (REQ-UA-002)
- [ ] 7.2 `shared/` — architecture test: no token port writes to plain-text
      preferences
- [ ] 8.1 `shared/` — implement the authentication repository: sign-up, sign-in,
      anonymous, sign-out, ownership transfer; clear the pending flag on both success
      and failure. Verified by: `AuthRepositoryTest` (REQ-UA-001, REQ-UA-007)
- [ ] 8.2 `shared/` — signed-out on an unrecoverable session; queued changes survive.
      Verified by: `AuthRepositoryTest` (REQ-UA-003, REQ-UA-006)

## Phase 9–10 — Identity and seeding

- [ ] 9.1 `shared/` — identity mapper splitting the composite local id into owner and
      profile at the network boundary; an unparseable value is an error, not a
      default. Verified by: `SyncIdentityMapperTest`
- [ ] 10.1 `shared/` — seed planner: upload existing local data once, through the
      ordinary push path. Verified by: `SeedPlannerTest` — exactly once, and a
      partial run resumes without duplicates (REQ-OS-013)

## Phase 11 — Sign-in surface

- [ ] 11.1 `shared/`, `androidApp/`, `desktopApp/` — sign-in and sign-up screen,
      reachable on both platforms, with the pending state wired to the repository
- [ ] 11.2 `shared/` — architecture test: no unwired new surfaces

## Verification

- [ ] `./gw :shared:jvmTest -Ptest.tags=fast,slow` green, zero skipped
- [ ] `just lint` clean
- [ ] `python3 scripts/find-unwired-surfaces.py` clean
- [ ] `openspec validate --all --strict` clean
- [ ] `just gate` green
