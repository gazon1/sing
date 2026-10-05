package com.singularity.todo.core.billing

// Provenance: ADAPTED from Tasks.org (GPL-3.0) — the hasPro / hasAccount / hasSubscription
//   triple and the purchaseStateFor factory are that project's shape.
//   Full registry: docs/legal/PROVENANCE.md

/**
 * Derived entitlement state for the current user.
 *
 * @property hasPro           `true` if the user has an active paid subscription.
 * @property hasAccount       `true` if the user has a linked account (even free tier).
 * @property hasSubscription  `true` if the user has an active Tasks subscription.
 */
data class PurchaseState(val hasPro: Boolean, val hasAccount: Boolean, val hasSubscription: Boolean) {
    companion object {
        /** Empty state — used before [SubscriptionProvider] is queried. */
        val EMPTY = PurchaseState(hasPro = false, hasAccount = false, hasSubscription = false)
    }
}

/**
 * Computes [PurchaseState] from a [SubscriptionProvider].
 *
 * This is a pure factory function — call it when you need a snapshot
 * of the user's entitlement state.
 */
fun purchaseStateFor(provider: SubscriptionProvider): PurchaseState {
    val info = provider.subscription.let { flow ->
        // Extract current value from StateFlow for synchronous access.
        (flow as? kotlinx.coroutines.flow.MutableStateFlow)?.value
    }
    return PurchaseState(
        hasPro = info?.isTasksSubscription == true,
        hasAccount = info != null,
        hasSubscription = info?.isTasksSubscription == true,
    )
}
