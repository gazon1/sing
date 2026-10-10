---
title: "Billing Entitlement Is A Port Without A Caller"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "entitlement-belongs-to-a-sync-scope"]
---

**Found in:** 2026-10-05, while assessing what stands between the tree and the
first paid feature.

**Status: CLOSED — 2026-10-05. Both defects fixed; the port is still unconsumed, which is now a deliberate state rather than an oversight.** Closed by
`docs/decisions/2026-10-05-entitlement-is-scoped-by-sync-scope.md` and
`openspec/changes/entitlement-belongs-to-a-sync-scope/`.

The cast that denied paying customers is gone: `entitlement(scope): StateFlow<Entitlement>`
is read-only in its signature, so there is no mutable supertype to downcast to.
`hasAccount` is independent of subscription, `Unknown` is separate from a denial, and
`refresh` returns `Result` so an outage cannot present itself as a paywall. Entitlement is
scoped by `SyncScope` — the pair sync state belongs to — and the positive case that was
named-but-never-written now exists against a fake shaped like a real provider.

What remains true and is not a defect: no real billing provider is connected, so every
scope reads `Unknown`. That is the honest state of a project that has not shipped a paid
feature. Wiring the first paid feature is now a consumer question rather than a
port-correctness one.

**Tracked as:** #204

**Symptom:** `core/billing` is five files — `SubscriptionProvider`,
`SubscriptionInfo`, `PurchaseState`, `purchaseStateFor`, `NoopSubscriptionProvider` —
registered in `CoreDiModule` and injected nowhere. `hasPro` gates nothing; there
is no second `if (hasPro)` anywhere in the tree.

**The defect inside it, which matters more than the missing caller.**
`purchaseStateFor` reads the provider's flow by downcasting it:

    (flow as? MutableStateFlow)?.value

`NoopSubscriptionProvider` exposes a `MutableStateFlow`, so the cast succeeds and
the tests pass. A real provider — Google Play Billing, RevenueCat — will expose a
read-only `StateFlow` or a `SharedFlow`, and `asStateFlow()` returns a
`ReadonlyStateFlow` that is **not** a `MutableStateFlow`. The cast then yields
`null` for a paying user, and the derived state says "no subscription": a customer
who has paid is denied. No exception, no log line, no crash.

The existing test is named `hasPro true when subscription is present` and
asserts the opposite, with a comment saying the positive case needs a real
provider. That is an accurate description of why the case is unwritable today,
but it leaves the defect invisible: a test named for the behaviour it does not
check reads as coverage in any inventory.

**A second defect in the same function, found while writing the first one up.**
`hasAccount` is derived as `info != null` — that is, "the user has an *active
paid subscription*". Its own KDoc says "the user has a linked account (even free
tier)". A free-tier user with a signed-in account therefore reads as
`hasAccount = false`. The two fields cannot both be right: with a single
`SubscriptionProvider` source, `hasAccount` is not a function of entitlement at
all, and deriving it from a paid subscription is what makes the triple look
independently meaningful when it is not.

This one is also inert today, for the same reason as the first, and it is worth
naming separately because it will not be fixed by fixing the cast: the KDoc and
the body disagree about what the field *means*, and that is a decision about the
entitlement model rather than a type error.

**Already ruled out:** not reachable today. Nothing injects the port, so the
function is not called in production and the bug cannot yet deny anyone. It is
recorded now because it becomes a *revenue* defect the moment the first paid
feature is wired — which is the one moment nobody is re-reading this code.

**Try next:** decide the paid feature first, then fix the read. The fix is
mechanical — `subscription.first()` in a `suspend` function, or expose a
`currentSubscription` property on the port — but choosing it means deciding
whether entitlement is a *snapshot* (a suspend read) or *state* (a Flow the UI
observes), and that choice belongs with the feature, not with a bug report. Write
the missing positive-case test with a fake provider exposing a read-only
`StateFlow` before shipping anything that charges money.

---
