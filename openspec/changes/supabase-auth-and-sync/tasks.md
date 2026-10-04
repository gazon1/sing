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
- [x] 2.8 `shared/` — drop the user-id parameter from the sync transport, so the
      server derives identity from the session rather than trusting a value the client
      supplies. A wrong value here is an authorisation bypass, not a bug, and the fix
      has to be structural: the parameter has to be gone for it to be impossible.
      Verified by: `SyncTransportIdentityTest`, with a positive control so the regex
      gate cannot go green having checked nothing
- [x] 2.9 `shared/src/commonMain/kotlin/com/singularity/todo/core/files/` — the digest
      helper used for attachment checksums called a JVM-only API from `commonMain`.
      Replaced with okio's `sha256()`, and `CommonMainJvmApiTest` now fails on any new
      `java|javax|android` import in `commonMain`. Verified by: existing attachment
      checksum tests pass

## Phase 3 — Server schema

- [x] 3.1 server — migration: profile table, six document tables, event log, field
      allowlist, applied-patch table. `profile_id` is text, not uuid, and client field
      values live in one `doc jsonb` column per table: the client's `toJson()` is the
      serialisation authority for six Kotlin classes, and a native type per field is
      drift that fails at runtime on a user's data
- [x] 3.2 server — row-level access policies on every synchronised table, keyed on the
      session's owner id via a scalar subquery (`owner_id = (select auth.uid())`) so the
      (owner_id, …) indexes stay usable. Every synchronised table has one; a table that
      forgets is readable by every signed-in user
- [x] 3.3 server — batch application function: idempotent by patch identity, per-field
      merge in one statement, all-or-nothing, security-definer with pinned search path.
      Refusals (`field_not_writable`, `row_unavailable`) are reported and NOT recorded
      in the ledger; a patch that changes nothing changes nothing, so a stale write
      bumps no version and logs no event
- [x] 3.4 server — event-feed function, owner-scoped; revoke direct access to the log,
      because a client that could page the log directly could apply a filter the
      function does not
- [x] 3.5 server — ownership-transfer function, refusing any identity that is not the
      caller's own. Verified by: `docs/sync-server-integration-test.sql` scenario 9 —
      another user's data is not claimable (REQ-UA-005). A transfer to a *different*
      account is not implemented; that needs a source-side decision this function is
      not the place to make
- [x] 3.6 server — health check reachable under a signed-in session. Verified by the
      same file, scenario 0; it also proves the session resolves to an owner, which is
      what a syntax error in one of the other bodies would not

## Phase 4–6 — Client transport

- [x] 4.1 `shared/` — `SupabaseConfigResolver`; URL and key from the secure store, with
      a build-time fallback for development. The stored value WINS over the build-time
      one — a debug build with a developer's test project compiled in must not silently
      override the project a user typed. A release build cannot produce a build-time
      config at all, so "not configured" is a state the app has to handle
      Verified by: `SupabaseConfigResolverTest` (7)
- [x] 5.1 `shared/` — `SyncEvent.profileId`, and the pull loop drops events belonging
      to another profile. Skipped, NOT stalled on: the log interleaves every profile of
      the account, so treating another profile's event as "not applicable" would freeze
      this account's cursor at the first one and it would never sync again. An event
      with no profile (an older server) still applies, which keeps an old server usable
      Verified by: `SyncEnginePullTest` — the other profile's event is skipped, the
      cursor moves past it, and the drop is counted (REQ-OS-011)
- [x] 6.1 `shared/` — implement the sync transport over the RPC endpoint; map failures
      onto the existing error type. The transport is built over a one-method `SyncRpc`
      port rather than over the vendor client, so the parsing is testable without a
      network: a 64-bit log position read through a `double` is exact for every value
      a test is likely to use and wrong past 2^53, and only a canned body makes that
      visible. `SyncWire.kt` holds the wire vocabulary separately from the calling, so
      a schema change is a diff in one file.
      Verified by: `SyncApiClientTest` (19) — result parsing, 2^53+1 read exactly,
      RPC failure as `AppError` with the cause kept, an absent `ok` not read as
      applied, an unknown document type refused rather than skipped
- [x] 6.2 `shared/` — architecture test: vendor SDK imports confined to the transport
      and authentication seams (REQ-OS-014, REQ-UA-008). `VendorSdkConfinementTest`
      (3) names the two seams, asserts they still exist and still import the SDK —
      without which the rule would pass vacuously after a rename — and carries a
      positive control
- [x] 6.3 server — the event log carries the row as it stands **after** the merge, not
      the request that caused it. It was logging `p->'doc'`, which a field-diff client
      never sends, so its events recorded `{}` and the pulling side — which
      deserialises `data` as a whole entity — would have materialised a task of
      defaults. A new `sync_read_row` helper reads the merged row back in the same
      statement as the merge. Verified by: `docs/sync-server-integration-test.sql`
      scenario 10
- [x] 6.4 server — the entry points keep their grants. A `revoke … from public, anon,
      authenticated` written for the helpers also hit `sync_batch_apply` during this
      phase; nothing in the schema changed and the first push failed with a permission
      error. `create or replace` preserves an ACL, so this is invisible to every
      ordinary migration and to every schema diff. Verified by: the same file,
      scenario 11

## Phase 7–8 — Auth

- [x] 7.1 `shared/` — session store over the secure store; migrate a plaintext token
      on first read and erase it. The refresh token is a bearer credential for the
      user's account, and preferences are an unencrypted file that travels in
      backups. The migration is write-then-erase and the ordering is the design:
      reversed, a crash between the two steps leaves the user signed out of their own
      account with the copy that would have let them back already deleted. A failed
      keychain write therefore keeps the plaintext copy and retries, because a
      plaintext token is a problem and a missing one is a lost account. The device id
      stays in preferences — an identifier with no authority of its own, where a
      keyring reset must not change it.
      Verified by: `SecureSessionStoreTest` (9) — the token moves, the plaintext is
      erased, a refused write leaves the plaintext in place, and a sign-in that lands
      before a pending migration is not overwritten by it (REQ-UA-002)
- [x] 7.2 `shared/` — architecture test: no token port writes to plain-text
      preferences. `PlaintextTokenIsolationTest` (3) also asserts the token keys are
      still declared, so renaming them cannot make the rule vacuously true, and
      carries a positive control. Verified against a real injected violation, not
      only against its own sample
- [x] 8.1 `shared/` — implement the authentication repository over an `AuthGateway`
      port, so the domain sees a `RemoteSession` rather than the SDK's `UserSession`
      and the repository is testable without a network (REQ-UA-008). Sign-up,
      sign-in, anonymous, sign-out, ownership transfer; the pending flag is cleared
      in a `finally` on every path, because a spinner that outlives its operation is
      indistinguishable from a hang.
      The transfer argument is worth reading: an anonymous user's data is claimed by
      *not moving it*. The provider attaches credentials to the identity that already
      exists, so the owner does not change and the rows are already the new account's.
      A move would be a second, redundant operation with its own failure mode. The id
      is checked anyway, because that is a property of the provider and not of this
      code — and a provider that issued a different id would orphan every row with no
      error from either side.
      `migrateAnonymousTo` takes the credentials rather than an expected id: the
      earlier shape left nothing to attach and a standing temptation to pass a
      placeholder address, which the provider would have accepted.
      Verified by: `AuthRepositoryTest` (20)
- [x] 8.2 `shared/` — signed-out on an unrecoverable session; queued changes survive.
      A refresh the *server rejects* signs out; a refresh that fails *on the network*
      keeps the stored session, because an unreachable server says nothing about
      whether a token is valid. Sign-out clears credentials even when the server is
      unreachable — the user asked, and refusing would mean they cannot sign out on a
      plane. Nothing in the repository writes the outbox on any path.
      Verified by: `AuthRepositoryTest` — the rejected-refresh, offline-refresh and
      unreachable-sign-out cases (REQ-UA-003, REQ-UA-006)

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
