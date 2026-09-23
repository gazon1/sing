---
title: "Billing abstractions: SubscriptionProvider port + Noop implementation"
date: 2026-09-23
tags: [billing, subscriptions, monetization]
status: accepted
---

## Context

Singularity Todo has no billing layer at all. There is no model for paid features, no concept of "Pro", and no way to gated functionality behind a subscription. Tasks.org has `SubscriptionProvider` (interface, backed by Google Play + Paddle + GitHub Sponsors + Desktop Link) and `PurchaseState` (hasPro / hasAccount / hasSubscription). These are clean abstractions with a single no-op implementation for now.

## Idea

Take only the abstraction (interface + data classes), not the Google Play / Paddle implementations. Create a single `NoopSubscriptionProvider` and `purchaseStateFor(provider)` factory. This gives us the data model and the interface contract. Real payment integration (Google Play Billing, RevenueCat, or Supabase billing) will be the next ADR.

## Decision

### SubscriptionProvider

```kotlin
// commonMain
interface SubscriptionProvider {
    data class SubscriptionInfo(
        val sku: String,
        val isMonthly: Boolean,
        val isTasksSubscription: Boolean,
        val purchaseToken: String? = null,
        val isGitHubSponsor: Boolean = false,
    )

    val subscription: Flow<SubscriptionInfo?>
    suspend fun getFormattedPrice(sku: String): String?
    suspend fun awaitVerification(): Boolean = true
}
```

### NoopSubscriptionProvider

```kotlin
class NoopSubscriptionProvider : SubscriptionProvider {
    override val subscription = MutableStateFlow(null)
    override suspend fun getFormattedPrice(sku: String) = null
    override suspend fun awaitVerification() = true
}
```

### PurchaseState

```kotlin
data class PurchaseState(
    val hasPro: Boolean,
    val hasAccount: Boolean,
    val hasSubscription: Boolean,
)

fun purchaseStateFor(provider: SubscriptionProvider): PurchaseState =
    PurchaseState(
        hasPro = provider.subscription.value?.isTasksSubscription == true,
        hasAccount = provider.subscription.value != null,
        hasSubscription = provider.subscription.value?.isTasksSubscription == true,
    )
```

**No interface for `PurchaseState`** — `data class` is sufficient. When real billing is added (multiple providers: Google Play + GitHub Sponsors), we will revisit whether an interface is needed. Premature interface is YAGNI.

### BillingProvider enum

```kotlin
enum class BillingProvider {
    GOOGLE_PLAY,
    GITHUB_SPONSOR,
    INTERNAL_GRANT,  // e.g. promo codes, lifetime access
}
```

`INTERNAL_GRANT` replaces `Paddle` from Tasks.org (Paddle is not in our monetization model).

### Directory structure

```
core/billing/
  BillingProvider.kt
  SubscriptionProvider.kt
  NoopSubscriptionProvider.kt
  PurchaseState.kt
```

## Rationale

- **Interface for `SubscriptionProvider`**: different payment backends (Google Play, RevenueCat, Supabase billing) have different SDKs. The interface allows swapping implementations without changing call sites. This matches the project's established pattern: `SecureStoragePort`, `NotificationPort`, `FileSystem`, `BackupCodec`.
- **`data class` for `PurchaseState`**: single implementation for now. The factory function `purchaseStateFor` computes derived state from the `subscription` flow — no duplication of logic.
- **`hasPro = subscription.value?.isTasksSubscription == true`**: `hasPro` means the user has an active Tasks subscription (not just an account). `isTasksSubscription` from `SubscriptionInfo` is the right field.
- **`INTERNAL_GRANT`**: promotional codes, team licenses, or lifetime access granted by the developer. Google Play and GitHub Sponsors are the two primary channels.

## Consequences

- `NoopSubscriptionProvider` is bound as `single<SubscriptionProvider>`. All purchase-related UI shows "Pro: false".
- `purchaseStateFor` is a factory function (not a class) — it recomputes on each call from the live `subscription` flow. If performance becomes an issue (excessive recomputation), it can be replaced with a `DerivedPurchaseState : PurchaseState` class that caches the result.
- Real Google Play Billing integration will require: `GooglePlaySubscriptionProvider : SubscriptionProvider`, adding `com.android.billingclient:billing` dependency, and wiring `BillingClient` in `androidMain`. This is the next ADR in this area.
- `awaitVerification()` returning `true` by default (Noop) means the app assumes the user is verified when no payment provider is connected. When Google Play is connected, it will query Google Play's licensing API.

## Links

- `shared/src/commonMain/.../core/billing/BillingProvider.kt`
- `shared/src/commonMain/.../core/billing/SubscriptionProvider.kt`
- `shared/src/commonMain/.../core/billing/NoopSubscriptionProvider.kt`
- `shared/src/commonMain/.../core/billing/PurchaseState.kt`
