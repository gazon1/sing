package com.singularity.todo.core.billing

// Provenance: ADAPTED from Tasks.org (GPL-3.0) — interface shape. The field names
//   isTasksSubscription and isGitHubSponsor are carried over from that source, so the
//   derivation is nameable rather than incidental.
//   Full registry: docs/legal/PROVENANCE.md

import com.singularity.todo.core.sync.SyncScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a user's entitlement comes from, per sync scope.
 *
 * Implementations are platform-specific: Google Play Billing, RevenueCat, GitHub Sponsors, or an
 * internal grant such as a promo code or a team licence.
 *
 * ## Why the read is keyed by scope and returns a `StateFlow`
 *
 * Two properties of this signature are load-bearing, and both were learned from a defect rather
 * than chosen.
 *
 * The first version of this interface exposed `val subscription: Flow<SubscriptionInfo?>` and
 * read it with a cast to `MutableStateFlow` so the read could be synchronous. That cast succeeded
 * for the no-op implementation and failed for every real provider, which exposes a read-only
 * `StateFlow` — `asStateFlow()` does not return a `MutableStateFlow`. A paying customer therefore
 * read as having no subscription, silently, with no exception anywhere.
 *
 * Returning `StateFlow` removes the mistake rather than documenting it: a read-only type has no
 * mutable supertype to be cast *to*, so the whole class of defect is unrepresentable rather than
 * merely discouraged. It also says something true about the data — an entitlement changes, and
 * callers may want to observe that without re-querying.
 *
 * The scope key is for the reason [SyncScope] exists: a purchase belongs to the same
 * `(ownerId, profileId)` as the rows it unlocks, so a profile switch cannot silently carry it.
 *
 * @see Entitlement
 */
interface SubscriptionProvider {

    /**
     * The entitlement for [scope].
     *
     * Returns [Entitlement.Unknown] until [refresh] has answered for that scope. Implementations
     * own the mutable flow; callers cannot write to it.
     */
    fun entitlement(scope: SyncScope): StateFlow<Entitlement>

    /**
     * Re-reads the entitlement for [scope] from the billing backend.
     *
     * Returns a [Result] rather than a `Boolean` because "the user is not entitled" and "the
     * check could not be performed" must not collapse into one answer. On failure the scope's
     * observed value is left as it was — writing `Known(hasPro = false)` here would turn a
     * network outage into a denial, and the user would be shown a paywall for a subscription
     * they hold.
     */
    suspend fun refresh(scope: SyncScope): Result<Entitlement>

    /**
     * A human-readable price for [sku], e.g. `"$4.99/mo"`.
     *
     * `null` when the SKU is unknown or the store is unreachable. A price is presentation, not
     * entitlement: it is never a source of truth for what the user may do.
     */
    suspend fun getFormattedPrice(sku: String): String?
}

/**
 * Details of a purchase as the billing backend reports it.
 *
 * @property sku               Product identifier.
 * @property isMonthly         `true` for monthly billing.
 * @property provider          Which provider granted it. Recorded, not inferred.
 * @property purchaseToken     Backend token for server-side validation, when the provider issues one.
 * @property serverVersion     The provider's row version, for optimistic-concurrency checks.
 */
data class SubscriptionInfo(
    val sku: String,
    val isMonthly: Boolean,
    val provider: BillingProvider,
    val purchaseToken: String? = null,
    val serverVersion: Long = 0,
) {
    /**
     * The entitlement this purchase establishes for a scope.
     *
     * A provider that grants nothing — an internal grant with no SKU, say — is expressed by not
     * having a `SubscriptionInfo` at all rather than by a flag on it, so there is no way to
     * construct a purchase that both exists and grants nothing.
     */
    fun toPurchaseState(): PurchaseState = PurchaseState(
        hasAccount = true,
        hasPro = true,
        provider = provider,
    )
}
