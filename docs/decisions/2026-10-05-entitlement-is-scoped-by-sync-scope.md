---
title: "Entitlement is scoped by SyncScope, and the read that denied paying customers is unrepresentable"
date: 2026-10-05
tags: [billing, entitlement, sync, architecture, defect]
status: accepted
---

## Context

`core/billing` was five files registered in `CoreDiModule` and injected nowhere. Two
defects made it unsafe to wire as it stood, and neither could be reached by a test
that existed.

**The first denied paying customers.** `purchaseStateFor` read the provider's flow
by downcasting it:

```kotlin
(flow as? MutableStateFlow)?.value
```

`NoopSubscriptionProvider` exposed a `MutableStateFlow`, so the cast succeeded and the
suite passed. Every real provider — Google Play Billing, RevenueCat — exposes a
read-only `StateFlow`, `asStateFlow()` returns a `ReadonlyStateFlow`, and that is not
a `MutableStateFlow`. The cast yielded `null`, and the derived state said "no
subscription": a customer who had paid was denied, with no exception, no log line,
and no crash.

**The second made a field into a non-fact.** `hasAccount` was derived as
`info != null` — "has an active paid subscription" — while its own KDoc said "has a
linked account (even free tier)". A free-tier signed-in user read as having no
account. The two could not both be right, and with one `SubscriptionProvider` as the
only source, `hasAccount` was never a function of account presence at all.

**The test that should have caught the first existed and asserted the opposite.** It
was named `hasPro true when subscription is present`, and it asserted `hasPro` was
false, with a comment explaining that the positive case needs a real provider. The
comment was accurate and the consequence was still a defect class left untested for
as long as nobody built the missing provider.

## Idea

Two separate fixes, and the second one is a type rather than a patch.

Scoping had been left open, because the answer determines the type in the state flow.
It is `(ownerId, profileId)` — the pair `SyncScope` names.

## Decision

`SubscriptionProvider` becomes:

```kotlin
fun entitlement(scope: SyncScope): StateFlow<Entitlement>
suspend fun refresh(scope: SyncScope): Result<Entitlement>
```

`Entitlement` is sealed over `SyncScope`: `Unknown` and `Known`. `PurchaseState`
carries `hasAccount`, `hasPro`, `provider`.

### The scope follows sync, because sync already settled the argument

`SyncScope`'s KDoc records why a pair is not negotiable for cursors: the download
cursor is a position in a per-`(owner, profile)` log, and one app-wide cursor is
correct only while there is exactly one of each. A second profile resumes from a
position belonging to a different data set, everything after it is applied against
the wrong history, and nothing reports it — because a pull that applies a hundred
events successfully looks exactly like a pull that started in the right place.

Entitlement has that hazard and one more. A user who buys Pro and switches to a
personal profile must not silently keep the paid feature for a data set the
subscription was never scoped to, and must not silently lose it either: one is a
theft, the other is a support ticket. Storing entitlement against the identity the
rows belong to makes that question answerable rather than arguable, and it inherits
`SyncScope`'s validation — a blank `ownerId` cannot hold a row, so it cannot hold an
entitlement either.

### The read is unrepresentable, not merely discouraged

The defect was a downcast into a flow the port did not own. Returning `StateFlow`
removes it: a read-only type has no mutable supertype to be cast *to*. There is no
version of `purchaseStateFor` that would compile, rather than one that compiles and
must be remembered not to be used.

This is the difference between a rule and a fix. `docs` saying "do not downcast
this" would have left the next reader with the same cast and no reason; the signature
leaves them with nothing to reach for.

### `Unknown` is not a convenience

It separates "not asked yet" from "asked, and the answer is no", which is what makes
`hasAccount` independent of subscription — and it is what lets a failed `refresh`
report a failure instead of writing a denial. `awaitVerification(): Boolean` could
not express that difference, so a network outage and a user without a subscription had
the same representation, and the first would have shown a paywall to a subscriber.

`Result` is the return type that carries it.

## Rationale

**The positive case is written first, against a fake shaped like a real provider.**
`ReadOnlyShapeProvider` exposes `asStateFlow()`. That is not a detail of the test
double — it is what every real provider does and what the no-op does not, and it is
precisely what the old cast rejected. A fake shaped like the no-op would have passed
against the broken implementation and proved nothing.

**`hasPro = true` requires a named provider**, enforced in `init`. A purchase with
no record of who granted it is unanswerable at the moment someone needs to answer it:
a refund, a support ticket, a store migration.

**`hasSubscription` was deleted rather than kept in sync.** It duplicated `hasPro`
and could only ever agree with it. Three fields where two are independent and one is a
copy is a shape that invites exactly the reasoning error that produced the original
defect.

## Consequences

- `purchaseStateFor` is gone. Nothing referenced it outside the package, so the
  retirement is not a migration.
- The port is still not wired to a real provider; `NoopSubscriptionProvider` is the
  binding, and every scope reads `Unknown`. That is the honest state of a project with
  no paid feature, and it is reachable — the port resolves, scopes validate, and a UI
  can be built against it without anything throwing.
- A consumer that treats `Unknown` as a denial will show a paywall. Visibly, in a
  build where nothing is configured — which is the failure mode this change is for.
- `find-unwired-surfaces.py` will see the port gain no new unwired surface; the
  billing backlog entry can close once this lands.
- What one purchase covering several profiles means is still a business decision.
  This change's position is that the storage question is settled regardless: a policy
  that wants one purchase to span two scopes has to say so, rather than inheriting it
  from a default.

## Links

- `openspec/changes/entitlement-belongs-to-a-sync-scope/` — proposal, requirements, tasks
- `shared/src/commonMain/kotlin/com/singularity/todo/core/billing/Entitlement.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/billing/SubscriptionProvider.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/billing/EntitlementTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncState.kt` — the scope type
- issue #204