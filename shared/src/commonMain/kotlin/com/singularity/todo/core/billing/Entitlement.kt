package com.singularity.todo.core.billing

// Provenance: ADAPTED from Tasks.org (GPL-3.0) — the hasPro / hasAccount /
//   hasSubscription triple and the purchaseStateFor factory are that project's
//   shape. The scope key and the Unknown state are ours; see
//   openspec/changes/entitlement-belongs-to-a-sync-scope/.
//   Full registry: docs/legal/PROVENANCE.md

import com.singularity.todo.core.sync.SyncScope

/**
 * What a user is entitled to in one sync scope.
 *
 * ## Why this is a state and not three booleans
 *
 * The previous shape was `PurchaseState(hasPro, hasAccount, hasSubscription)` plus a
 * `purchaseStateFor` factory, and it had two defects that only matter once money is involved.
 *
 * The first was arithmetic: `hasAccount` was derived as `info != null`, which means "has an
 * active paid subscription", while its own KDoc said "has a linked account (even free tier)". A
 * free-tier signed-in user read as having no account. The field was never a fact — it was a
 * restatement of another field, and the two could not both be right.
 *
 * The second was a cast. `purchaseStateFor` read the provider's flow by downcasting it to
 * `MutableStateFlow`, which succeeds for `NoopSubscriptionProvider` and fails for every real
 * provider — those expose a read-only `StateFlow`, and `asStateFlow()` does not return a
 * `MutableStateFlow`. A paying customer read as having no subscription, with no exception and no
 * log line.
 *
 * `Unknown` is what fixes the first and makes the second inexpressible. It separates "not asked
 * yet" from "asked, and the answer is no", so a pending read cannot be reported as a denial, and
 * a consumer can tell a free user from a slow one.
 *
 * ## Why the scope, and why this scope
 *
 * Entitlement is stored against `(ownerId, profileId)` — the pair `SyncScope` names, and the pair
 * sync state belongs to. `SyncScope`'s own KDoc records why that shape is not negotiable for
 * cursors: a single app-wide cursor is only correct while there is exactly one account and one
 * profile, and once there are two, everything after the switch is applied against the wrong
 * history with nothing reporting it.
 *
 * Entitlement has the same hazard plus one of its own. A user who buys Pro and then switches to
 * a personal profile must not silently keep the paid feature for a data set the subscription was
 * never scoped to. Scoping entitlement to the same identity the rows belong to makes that
 * answerable instead of arguable, and reuses `SyncScope`'s validation so an entitlement cannot be
 * constructed for a scope that could not hold a row in the first place.
 *
 * @see com.singularity.todo.core.sync.SyncScope
 */
sealed interface Entitlement {

    /** The sync scope this entitlement is about. Carried on the interface, never alongside it. */
    val scope: SyncScope

    /**
     * No answer has been established for this scope yet.
     *
     * Not an error state and not a denial. It is what a scope reads as before
     * [SubscriptionProvider.refresh] has answered, and what it returns to after — a failed
     * refresh is reported as a failure and leaves this in place, because writing
     * `Known(hasPro = false)` on a network error would turn an outage into a denial.
     */
    data class Unknown(override val scope: SyncScope) : Entitlement

    /** An answer has been established for [scope]. */
    data class Known(override val scope: SyncScope, val state: PurchaseState) : Entitlement

    /**
     * What is known, or `null` while unknown.
     *
     * Deliberately not `PurchaseState.EMPTY`: an absent answer and an empty answer are different
     * facts, and collapsing them is what let the original triple be read as three independent
     * booleans when only one of them ever was.
     */
    val stateOrNull: PurchaseState?
        get() = (this as? Known)?.state
}

/**
 * The established answer for one scope.
 *
 * @property hasAccount   `true` if an account is signed in. Independent of entitlement: a
 *   free-tier user has an account and this is `true`.
 * @property hasPro       `true` if that account holds an active paid entitlement in this scope.
 * @property provider     Which billing provider granted it, or `null` when nothing was granted.
 *   Recorded rather than re-derived, because inferring the provider from an SKU string is the
 *   kind of parsing that goes stale when a store changes its naming.
 */
data class PurchaseState(val hasAccount: Boolean, val hasPro: Boolean, val provider: BillingProvider? = null) {
    init {
        require(!(hasPro && provider == null)) {
            "an active entitlement must name the provider that granted it; hasPro = true with a " +
                "null provider is the shape that makes 'who granted this' unanswerable later"
        }
    }

    companion object {
        /**
         * No account, no entitlement.
         *
         * Reachable and correct for a signed-out user. It is *not* what an unanswered scope reads
         * as — that is [Entitlement.Unknown] — which is why this constant is a member of
         * `PurchaseState` and not of `Entitlement`.
         */
        val SIGNED_OUT = PurchaseState(hasAccount = false, hasPro = false, provider = null)
    }
}

/** What a scope reads as when an account is signed in and no paid entitlement is held. */
fun PurchaseState.Companion.freeTier(): PurchaseState = PurchaseState(hasAccount = true, hasPro = false)
