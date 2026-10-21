---
title: "The session restore runs behind a promise, not behind a timeout"
date: 2026-10-20
status: accepted
tags: [architecture, coroutines, auth, reliability]
---

## Context

`SupabaseAuthRepository.restoreSession()` had a deadline, and the deadline was
cooperative:

```kotlin
withTimeoutOrNull(RESTORE_SESSION_TIMEOUT) { doRestoreSession() }
```

`withTimeoutOrNull` cancels the coroutine it wraps, and cancellation is a request the
coroutine has to honour. The restore reads the refresh token, the owner id and the
email out of `SecureSessionStore` — a keychain read on Android, a `secret-tool`
subprocess on the desktop — and a read blocked inside a driver call ignores
cancellation entirely. The timeout therefore bounded the *wait for a cooperative
read*, and the app waited for a non-cooperative one anyway.

This is the same asymmetry the remote-config port had. Its read was moved behind a
bound of its own — the port runs the read in a scope that is not the caller's, and the
caller waits on a promise of the answer (`core/coroutines/BoundedRead.kt`). The
version-gate ADR recorded the move and named the auth repository as the same shape
still to be done:

> `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt` — the
> same deadline shape, for a session restore that must not leave the app on a spinner

The restore is what decides whether the app opens on the agenda or on the login
screen, and a restore that never answers leaves the app on `Session.Loading` — a
spinner in front of every screen, with no error anywhere, because nothing threw.

## Idea

- **A.** Leave the timeout as it is. It bounds the common case — a slow but
  cooperative store — and the pathological case was rare.
- **B.** Run the restore in a scope of its own and let the caller wait on a promise.
  The caller can be released at the deadline whether or not the work behind it
  finishes.
- **C.** Make the store read non-blocking. The read would have to be rewritten to a
  driver that answers or fails, which is a different storage design.
- **D.** Give up on restoring and open the app signed out, with a manual "restore
  my session" action for the rare install whose store is slow.

## Decision

**B** — the restore runs in a scope of its own, and the caller waits on a promise.

- The repository holds a `restoreScope`: the injected scope's context, minus its job,
  plus a fresh `SupervisorJob`. It is a scope of its own in the sense that matters:
  the restore is not a child of the coroutine that waits for it, and not a child of
  the caller's scope either. A caller that has been released is not still carrying the
  work it released.
- The wait is `restoreScope.boundedRead(timeoutMs = RESTORE_SESSION_TIMEOUT,
  onAbandonedFailure = ::reportAbandonedRestoreFailure) { doRestoreSession() }` — the
  same primitive the remote-config port uses.
- The deadline still means the same thing to the user: a restore that has not
  answered in ten seconds ends at a signed-out session and reports a restore failure
  through the existing `restoreFailures` channel. What changed is that the app now
  *reaches* that answer when the store is stuck, instead of waiting behind it.
- A restore that answers after the deadline is not thrown away. The deadline ended
  the waiting, not the restore: the session it found is published. A restore that
  *fails* after the deadline has nobody left to handle it, so it is reported by
  `reportAbandonedRestoreFailure` rather than dropped.

## Rationale

- **A deadline that cannot end the wait is not a deadline.** It reads like a bound and
  behaves like a suggestion. The whole cost of the old shape was that the failure it
  was written for — a store that never answers — was the one failure it could not
  produce.
- **The bound belongs to the operation, not to the caller.** The caller of
  `restoreSession()` is the repository's own scope, and it has no reason to hold a
  deadline of its own. The remote-config port had already moved its bound inside;
  leaving the auth repository outside meant the app had two shapes of "this read may
  not answer", one of which did not work.
- **A promise can be given up on; a coroutine cannot.** This is the whole mechanism.
  `boundedRead` starts the read in the scope it was given and awaits a `Deferred`
  under a timeout. When the timeout fires, the await ends and the read keeps running
  where it is. No cancellation is sent to it, so a read blocked in a driver call is
  not interrupted — it simply stops being waited for.
- **The failure modes stay distinguishable.** A read that throws is a failed read and
  is caught and answered the way a timeout is answered: signed out, with the failure
  reported. A read that misses the deadline is not a failure, it is a late answer, and
  it is treated as one. Conflating them would mean a slow store reported itself as a
  broken one.
- **C is a rewrite of the storage layer, not a fix of the deadline.** The keychain
  read is what it is.
- **D changes what the app is.** The restore exists so that a signed-in user opens
  the app signed in. Turning it into a manual action is a product decision, and it
  was not needed to fix the bound.

## Consequences

- **The spinner has an end.** A store that never answers now ends at the login
  screen in at most `RESTORE_SESSION_TIMEOUT`, with a restore failure reported. This
  is the behaviour the timeout always claimed to have.
- **The restore may outlive the wait.** A late restore still publishes its session,
  so the app can move from the login screen to the agenda by itself. That is
  deliberate: the restore found a real session, and throwing it away would be a
  second bug.
- **`restoreScope` is never cancelled.** Its lifetime is the repository's, and the
  repository is a Koin `single` — a process-lifetime instance. The same bargain
  `createBackgroundScope()` makes for every other long-lived component.
- **The scope inherits the injected dispatcher and failure handler** rather than
  hardcoding `Dispatchers.Default`, so the restore runs where the platform layer said
  background work runs and its failures are reported the way every other background
  failure in this app is reported.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt` —
  `restoreSession`, `restoreScope`, `reportAbandonedRestoreFailure`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/coroutines/BoundedRead.kt` —
  the primitive, and the rule that the receiver must be a scope of its own
- `2026-10-20-version-gate-read-timeout.md` — the ADR that named this file as the
  same shape still to be done
- `openspec/specs/user-authentication/spec.md` — REQ-UA-015: a restore that does not
  answer in time ends at a signed-out session the user can recover from
