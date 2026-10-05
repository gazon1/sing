package com.singularity.todo.core.billing

// Provenance: ADAPTED from Tasks.org (GPL-3.0) — interface shape. The field names
//   isTasksSubscription and isGitHubSponsor are carried over from that source, so the
//   derivation is nameable rather than incidental.
//   Full registry: docs/legal/PROVENANCE.md

import kotlinx.coroutines.flow.Flow

/**
 * Port interface for querying subscription / entitlement status.
 *
 * Implementations are platform-specific (Google Play Billing, RevenueCat,
 * Supabase billing, GitHub Sponsors API, etc.).
 *
 * A single [SubscriptionProvider] may represent multiple [BillingProvider]s
 * internally (e.g. Google Play + GitHub Sponsors for the same user).
 *
 * @see NoopSubscriptionProvider
 */
interface SubscriptionProvider {
    /**
     * Full details of the current subscription, if any.
     * Emits `null` when the user has no active subscription.
     */
    val subscription: Flow<SubscriptionInfo?>

    /**
     * Returns a human-readable price string for [sku], e.g. `"$4.99/mo"`.
     * Returns `null` if the SKU is unknown or the store is unavailable.
     */
    suspend fun getFormattedPrice(sku: String): String?

    /**
     * Verifies the subscription status with the billing backend.
     * Returns `true` if the subscription is valid and active.
     *
     * Default: `true` (assumes valid when no provider is connected).
     */
    suspend fun awaitVerification(): Boolean = true
}

/**
 * Details of an active subscription from a billing provider.
 *
 * @property sku               Product SKU (e.g. `"sku.premium.monthly"`).
 * @property isMonthly         `true` for monthly billing, `false` for annual.
 * @property isTasksSubscription `true` if this subscription is for Singularity Todo.
 * @property purchaseToken     Backend purchase token (for Google Play validation).
 * @property isGitHubSponsor   `true` if access is granted via GitHub Sponsorship.
 */
data class SubscriptionInfo(
    val sku: String,
    val isMonthly: Boolean,
    val isTasksSubscription: Boolean,
    val purchaseToken: String? = null,
    val isGitHubSponsor: Boolean = false,
)
