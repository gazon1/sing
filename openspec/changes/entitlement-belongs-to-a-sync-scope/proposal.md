# entitlement-belongs-to-a-sync-scope

## What

Model entitlement as `(ownerId, profileId)` — the same pair sync state belongs to — and give it a
state type that can say "not asked yet" instead of collapsing that into "no".

`core/billing` exists as five files and is injected nowhere. Two defects make it unsafe to wire
as it stands, and both are the kind that only appear once money is involved:

- **`purchaseStateFor` denies a paying customer.** It reads the provider's flow by downcasting
  to `MutableStateFlow`. `NoopSubscriptionProvider` exposes one, so the tests pass. A real
  provider — Google Play Billing, RevenueCat — exposes a read-only `StateFlow`, `asStateFlow()`
  returns a `ReadonlyStateFlow` which is not a `MutableStateFlow`, and the cast yields `null`.
  The derived state then says "no subscription": a customer who has paid is denied, with no
  exception, no log line, and no crash.
- **`hasAccount` is not a fact.** It is derived as `info != null`, which means "has an active
  paid subscription", while its own KDoc says "has a linked account (even free tier)". A
  free-tier signed-in user reads as having no account. The two cannot both be right.

## Why this change, and why the scope is `(ownerId, profileId)`

The scope question was left open when the bug was filed, because the answer determines the type
in `StateFlow<…>` and therefore the shape of everything downstream. It is answered here by
following sync identity rather than inventing a parallel key.

Sync already went through this. `SyncScope`'s KDoc records why: the download cursor is a
position in a per-`(owner, profile)` event log, and storing one cursor for the whole app is only
correct while there is exactly one of each. A second profile resumes from a position belonging to
a different data set, every subsequent event is applied against the wrong history, and nothing
reports it — because a pull that applies a hundred events successfully looks exactly like a pull
that started in the right place.

Entitlement has the identical shape of hazard, with an extra edge: a profile switch. A user who
buys `Pro` and then switches to a personal profile must not silently keep the paid feature for a
data set the subscription was never scoped to, and must not silently lose it either — one is a
theft, the other is a support ticket. Storing entitlement against the same scope the rows
belong to makes that question answerable rather than arguable.

A separate entitlement key would be a second notion of "whose data is this", and the two would
diverge the first time someone added a profile. Using `SyncScope` also means the value type's
`init` validation — no blank `ownerId`, no blank `profileId` — applies, so an entitlement cannot
be constructed for a scope that could not hold a row.

## How

`Entitlement` is a sealed interface over `SyncScope`:

```kotlin
sealed interface Entitlement {
    val scope: SyncScope
    data class Unknown(override val scope: SyncScope) : Entitlement
    data class Known(override val scope: SyncScope, val state: PurchaseState) : Entitlement
}
```

`Unknown` is not a convenience. It is what stops a pending read from being reported as a
negative answer, and it is what makes `hasAccount` independent: "has a linked account" is a
property of the account, not of the subscription, and deriving it from `info != null` was always
wrong. With `Unknown` in the type, "not asked" cannot be silently read as "no".

The port exposes `StateFlow<Entitlement>` and therefore owns the mutable flow itself. The defect
class stops being reachable: `purchaseStateFor` had nothing to reach *for* only because it
reached into a flow it did not own. A read-only type signature forbids the cast that caused the
bug rather than documenting why it must not be written.

Verification is a separate, explicit transition — `refresh()` returning `Result` — rather than a
method a consumer has to remember to call. A consumer that needs a verified answer awaits it; a
consumer that only needs to render waits for `Unknown` to leave.

`PurchaseState` drops `hasSubscription` as a duplicate of `hasPro` and gains `provider: BillingProvider?`,
so "who granted this" is recorded rather than re-derived.

## What this does not do

It does not connect a real billing provider. `NoopSubscriptionProvider` remains the binding, and
with it every scope reads `Unknown`. That is the honest state of a project that has not shipped
a paid feature, and it is reachable — the port resolves, scopes resolve, and the UI can be built
against it without anything throwing.

It also does not answer what happens when a purchase covers several profiles. That is a
business decision about what is being sold, and this change's position is that the *storage*
question is settled regardless of it: entitlement is recorded against a scope, and a policy that
wants one purchase to cover two profiles has to say so explicitly rather than inherit it from a
default.