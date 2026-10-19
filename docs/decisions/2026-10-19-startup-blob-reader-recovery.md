---
created: 2026-10-19
tags: [auth, security, recovery, infrastructure]
status: accepted
---

# ADR: Startup blob reader recovery

## Context

Several places in the codebase read a persisted blob on startup and use it to restore
application state:

| Reader | Storage | Corruption path |
|--------|---------|----------------|
| `AndroidSecureStorage` | EncryptedSharedPreferences (Keystore) | Auto Backup restores XML without the Keystore key → `GeneralSecurityException` on every read |
| `JvmSecureStorage` | AES-GCM file (`~/.config/singularity/secure.bin`) | Truncated header, wrong MAC, partial write → decryption failure |
| `SecureSessionStore` | Delegates to `SecureStorage` | Inherits whichever port is registered |
| `HlcFactory.nodeIdDeferred` | Device ID provider | Throwing provider leaves deferred *pending* forever |
| `DataStoreSyncPrefs` | DataStore preferences | Corrupt DataStore file → `IOException` on first read |

The auth restore path (`AuthRepository.restoreSession`) also wraps everything in
`runCatchingCancellable` and `withTimeoutOrNull` so a throwing or hanging storage
produces a null outcome that falls back to signed-out, rather than a crash or hang.

## Decision

Every startup blob reader follows this recovery policy:

**One erase-and-retry, then propagate.** If the first read or write of a
blob throws, erase the blob and retry once. If the retry also throws, propagate
the second exception with the first attached as suppressed. Do not retry
indefinitely.

The policy is implemented in `SecureStoreRecovery.openOrErase`, a shared policy
object in `commonMain` so both Android and JVM ports can use it without duplicating
the logic. The policy object takes two lambdas — `open` and `erase` — that carry
the platform-specific file deletion.

## Reasoning

### Why one retry is sufficient

A recoverable corruption (stale ciphertext, truncated header) fails the first
attempt and succeeds after an erase. A persistent failure (revoked Keystore,
full disk, revoked keyring credential) fails the same way on a clean file.
One retry distinguishes these two cases without risking an infinite loop.

### Why not indefinitely many retries with backoff

A network timeout might succeed on retry. A corrupt file never will. Allowing
indefinite retries converts a deterministically failing storage into a startup that
never completes, which is strictly worse than propagating the error and falling back
to a sane default.

### Why not a background thread

A recovery that succeeds means the blob was already unreadable, so nothing is lost
by erasing it synchronously. A background erase adds implementation complexity,
asynchronous failure reporting, and the possibility of a race where the app
continues past the recovery before the erase completes.

### Why `runCatchingCancellable` for DataStore

`DataStoreSessionStore` and `DataStoreDraftStore` use `catchDataStoreIoError` on
the flow, which replaces an I/O error with `emptyPreferences()` / an empty draft.
This is a different policy: "the store is empty" is a reasonable default for
settings and drafts, and the error is logged.

`runCatchingCancellable` is used in the launch blocks of `DataStoreSyncPrefs` and
`HlcFactory` to ensure that a `CancellationException` thrown by a cancelled scope
does not get swallowed and replaced with a silently wrong default — which is how a
cancelled startup init would appear to continue as "using defaults" with no
indication that anything went wrong.

## Consequences

### Positive

- Android and JVM secure storage both recover from a corrupt keystore / encrypted
  file without requiring a reinstall or manual data deletion.
- The `SecureStoreRecovery` policy is tested once in `SecureStoreRecoveryTest`
  and the JVM variant is tested in `JvmSecureStorageRecoveryTest`, so the logic
  is never tied to a specific platform.
- A second failure is not retried, so a genuinely broken storage (full disk,
  revoked key) fails fast rather than looping.

### Negative

- An erase destroys **all** keys in the secure file, not just the one that failed.
  This is unavoidable for a file format that stores all keys in one encrypted blob:
  if the blob cannot be decrypted, no key can be recovered individually.
- The JVM fallback (`secure.bin`) is erased in its entirety on any corruption.
  There is no per-key recovery because the file format does not support it.

## Alternatives considered

### Per-key retry without erase

Reading key-by-key from a corrupted file would require a format that stores
each key separately. The current binary format is a single encrypted blob. Adding
per-key isolation would be a format migration and is out of scope for this fix.

### Null as a fallback for secure storage

Returning null when secure storage throws would silently lose credentials. The auth
flow uses null-from-storage as "signed out" — a silent data loss would make
"cannot read session" indistinguishable from "never signed in", which loses the
ability to tell the user what actually happened. The erase-and-retry policy
preserves the distinction: either the store works or it doesn't, and the user
is told which.

### Separate JVM keyring fallback

The `secure.bin` fallback already *is* the fallback for when `secret-tool` is
unavailable. Adding a third fallback (in-memory only) would make data loss more
likely, not less.

## Implementation notes

- `SecureStoreRecovery.openOrErase` is in `shared/src/commonMain/kotlin/com/singularity/todo/core/security/`.
- `AndroidSecureStorage` uses it via `SecureStoreRecovery.openOrErase` in the
  `by lazy` initializer for `prefs`.
- `JvmSecureStorage` uses it for both `fallbackReadWithRecovery` and
  `fallbackWriteWithRecovery`, called from `read` and `write` respectively.
- `fallbackFile` and `secretToolAvailable` in `JvmSecureStorage` are `open`
  members to allow test subclassing.

## Links

- `SecureStoreRecovery.kt` — policy object
- `AndroidSecureStorage.kt` — Android port
- `JvmSecureStorage.kt` — JVM port
- `AuthRepository.kt` — `runCatchingCancellable` + `withTimeoutOrNull` around `restoreSession`
- `HlcFactory.kt` — `runCatchingCancellable` + `completeExceptionally` for device id
- `DataStoreSyncPrefs.kt` — `runCatchingCancellable` in init block
- ADR `2026-10-18-guaranteed-exit-for-asynchronous-values` — the general principle
