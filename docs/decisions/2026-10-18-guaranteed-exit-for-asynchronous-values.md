---
type: decision
date: 2026-10-18
status: accepted
deciders:
  - Singularity Developer
context:
  - "2026-10-18: a device sat on an unfinishable startup spinner through a release; root cause was a session restore with no time bound and no failure handler"
  - "2026-10-18 audit found the same shape twice more: HlcFactory's CompletableDeferred and the NoRunCatchingInSuspend blind spot"
references:
  - "2026-10-03-cancellable-result-capture.md"
  - "core/auth/AuthRepository.kt:restoreSession"
  - "core/sync/HlcFactory.kt:nodeIdDeferred"
  - "detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoRunCatchingInSuspend.kt"
  - "openspec/specs/user-authentication/spec.md:REQ-UA-015"
---

# A value whose only exit is a suspend call must have a guaranteed one

## Context

A release shipped to a device that could not finish starting: `AuthGuard` rendered
`Session.Loading` forever. The chain had no defect in the sense of a wrong answer — it had
no answer.

`restoreSession()` read the session store, and if the read threw or never returned, the
coroutine died or waited. Nothing assigned `_currentSession` on that path, so `Loading` — the
value the state was constructed with — was also the value it kept. The user saw a spinner
with no way past it and no message.

Auditing the codebase for the same shape found two more:

- `HlcFactory.nodeIdDeferred` was completed by `deferred.complete(provider.getOrInitDeviceId())`.
  The provider call is an *argument*, so a throw killed the launch body before `complete`
  ran and the deferred stayed pending for the life of the process. `getCompleted()` on a
  pending deferred raises `IllegalStateException`, so one failed device-id read broke every
  HLC tick — the key conflict resolution orders by.
- `NoRunCatchingInSuspend` walked up only to `KtNamedFunction` carrying `suspend`. A
  suspend *lambda* has no such ancestor, and `scope.launch { runCatching { ... } }` is the
  shape every background repository `init` in the app uses. The rule's activation note
  recorded that the tree reported zero violations; it reported zero because the shape was
  never searched. `SyncPrefs` carried a plain `runCatching` through the gate.

## Idea

A state machine that can only advance by calling into something that may fail, hang, or
throw has no exit it can promise. Each of the three cases above is the same defect wearing a
different costume, and the shared part is that **the failure path was not written down**, so
it was not designed, so nobody checked whether it existed.

## Decision

Four rules, applied to the three sites and enforced where each is cheapest to enforce.

1. **Every asynchronous value that a UI state depends on reaches a terminal state.** A
   `Deferred` is completed or completed-exceptionally; a state leaves its loading variant.
   `withTimeoutOrNull` bounds the waiting, and a `catch` covers the throwing — the timeout
   alone does not, because it returns `null` only for `TimeoutCancellationException` and
   passes every other throw straight through. Falling back is a decision, not an accident of
   the type signature.

2. **Failing is preferred to degrading quietly.** `HlcFactory` completes exceptionally
   rather than substituting a placeholder node id, because the node id is half the HLC
   string and two devices sharing a placeholder would mint byte-identical timestamps — the
   exact collision the class was written to prevent, except silent and crossing a device
   boundary. A loud, deterministic, diagnosable failure beats a quiet wrong answer.

3. **Recovery policy is a named, testable unit; the platform supplies only the IO.**
   `SecureStoreRecovery.openOrErase` decides whether an unreadable encrypted store is
   erased and reopened. It used to live inline in `AndroidSecureStorage`, behind a `Context`
   and a `security-crypto` artifact, which made "can the app recover from a broken install"
   the one behaviour no test could reach.

4. **A detector's silence is not evidence of cleanliness.** `NoRunCatchingInSuspend` now
   recognises suspend lambdas through the call's name, and its activation note is corrected:
   the earlier "zero violations" was a true reading of an incomplete search. The PSI detail
   that made the first attempt fail silently — `KtLambdaExpression`'s parent is a
   `KtLambdaArgument`, not the `KtCallExpression` — is written down in the rule, because a
   rule that stops detecting is indistinguishable from a clean tree.

## Rationale

Each rule is placed where the failure would otherwise be cheapest to repeat: the timeout and
catch where the state is published, the recovery policy where the decision lives, and the
detector where its own blind spot was. A rule that lives only in a fix is a comment; the
three places are chosen so that the next instance of each shape is caught by something
rather than by whoever reads the diff.

Why `restoreFailures` is an event and not a `Session` variant: a failed restore is a fact
about an *attempt*. After the user presses "Continue Offline" the session is `Anonymous` and
nothing about the failure is still true. Encoding it as state would put a third
signed-out-shaped variant into every exhaustive `when (session)` in the app, all of which
treat it identically, and make the compiler oblige by having each restate that identity.

## Consequences

- `AuthRepository.restoreFailures` has a default of never firing, so the dev repository, the
  instrumented stub and the fakes are unchanged — none of them restores anything, and none
  of them can fail at it.
- `NoRunCatchingInSuspend` may now flag code that was previously unreported. New violations
  in `launch`/`async`/`collect` lambdas will fail the build; that is the point, and the
  mechanical fix is `runCatchingCancellable`.
- The recovery policy deliberately does not retry indefinitely. A revoked lock screen or a
  full disk fails the same way on a clean file, and an unbounded retry turns either into a
  startup that never completes — the shape this decision exists to prevent.

## Links

- `openspec/specs/user-authentication/spec.md` — REQ-UA-015
- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/security/SecureStoreRecovery.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/HlcFactoryNodeIdFailureTest.kt`