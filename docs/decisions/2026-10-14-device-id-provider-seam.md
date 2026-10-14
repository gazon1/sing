---
title: "DeviceIdProvider: sync depends on a device-id seam, not on the auth SessionStore"
date: 2026-10-14
tags: [sync, auth, hlc, koin, test-determinism, measurement]
supersedes: 2026-10-14-measurement-determinism-helpers-and-gates
status: accepted
---

## Context

`HlcFactory` needs exactly one value from storage: the device id it stamps
hybrid-logical-clock timestamps with. It was injected with the whole auth
`SessionStore` interface — tokens, email, `clear()` — which made the HLC seam
depend on an unrelated auth surface. The e05a68ad incident (binding commented out
inside an unrelated scripts PR, breaking every downstream compile via KOIN-D003
deferred validation) made the coupling visible, but removing the dependency is
better than only protecting the binding.

The device id is not a secret; it has always lived in plain DataStore by design.
`SecureSessionStore` (the KEYCHAIN token store) merely delegated
`getOrInitDeviceId()` back to the DataStore store.

This ADR supersedes
`2026-10-14-measurement-determinism-helpers-and-gates` — the closed body cannot
be edited (`check-adr-immutable.py`), so its still-valid rules are restated
below with the measurement updates accumulated since it was written.

## Decision

1. **New port `core/sync/DeviceIdProvider`** — `suspend fun
   getOrInitDeviceId(): String`. `HlcFactory(deviceIdProvider, clock, scope)`
   depends on it, not on `SessionStore`.
2. **`DataStoreSessionStore` implements `DeviceIdProvider`**, and Koin binds
   `single<DeviceIdProvider> { get<DataStoreSessionStore>() }` — the plain-DataStore
   store is the honest home of a non-secret id.
3. **The `single<SessionStore>` interface binding is removed.** After (1) it had
   zero consumers — auth code resolves concrete types (`SecureSessionStore`,
   `DataStoreSessionStore`) directly. A binding nothing reads is a trap waiting
   for someone to resolve it against the wrong store.
4. **CI runs JVM tests with `--no-parallel`** (`ci.yml` Gradle step, same call the
   pre-push hook makes): `org.gradle.parallel=true` forked two test JVMs
   concurrently — the collision behind #471's EOFException.

### Restated rules from the superseded ADR

The superseded ADR drops out of the digest in full; its surviving rules live
here (measurement numbers updated to the narrowed state):

1. **Display waits go through the four helpers** (`assertTextDisplayed`,
   `assertTagDisplayed`, `assertContentDescriptionDisplayed`,
   `assertNodeDisplayed`) — never `awaitTag(...).assertIsDisplayed()` and no raw
   `.assertIsDisplayed()` outside helper code. Enforced two-sided by
   `HarnessConventionTest` (`DISPLAY_ASSERT_PATTERN`, exempt-file stale-check).
2. **`non-tagged-maestro-elements` stays `active: false`** until the Maestro
   tranche lands TestTags ids; debt is narrowed to **26 findings in 12 files**
   (was 578) by requiring `TestTags.` in the file. Baseline cannot absorb them —
   `check-baseline-ratchet.py` forbids baseline growth.
3. **Determinism outranks parallelism**: pre-push and the CI JVM-test step both
   pass `--no-parallel`; flake measurement runs five green full-suite runs and
   `check-flaky-tests.py` between runs.

## Rationale

Ports express the minimum contract a consumer needs. Making the dependency
explicit (`DeviceIdProvider`) removes the auth coupling, kills a dead interface
binding, and lets the Koin compiler plugin verify the smaller graph. Keeping the
id on DataStore preserves the security invariant it always had — the KEYCHAIN
store was never the id's real home.

## Consequences

- **Never** resolve `SessionStore` from Koin — the interface binding no longer
  exists; inject the concrete store or `DeviceIdProvider`.
- **Never** add `getOrInitDeviceId` (or device-id reads) back onto a
  token-bearing seam; the id stays on `DataStoreSessionStore` / `DeviceIdProvider`.
- `FakeHlcFactory` and test fixtures implement `DeviceIdProvider` directly —
  new fixtures do not need the full auth surface.
- The restated rules above are the digest-visible contract; the superseded ADR
  is retained for history but ignored by the digest.

## Links

- Supersedes: `2026-10-14-measurement-determinism-helpers-and-gates.md`
- `HlcFactory.kt`, `DeviceIdProvider.kt`, `CoreDiModule.kt` (auth + sync sections)
- `scripts/check-adr-immutable.py` (why a closed body needs a superseding ADR)
