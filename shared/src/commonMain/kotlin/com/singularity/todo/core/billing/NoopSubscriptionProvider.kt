package com.singularity.todo.core.billing

// Provenance: ADAPTED from Tasks.org (GPL-3.0) — no-op implementation only.
//   Full registry: docs/legal/PROVENANCE.md

import com.singularity.todo.core.sync.SyncScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The provider used when no billing backend is connected.
 *
 * Every scope reads [Entitlement.Unknown], and every [refresh] succeeds with that same answer.
 * That is the honest state of a build with no store: the client does not know what the user is
 * entitled to, and saying so is different from saying "nothing".
 *
 * The distinction matters because it is the one the previous implementation lost. Every
 * entitlement here is [Entitlement.Unknown], so a consumer that treats unknown as a denial will
 * show a paywall — visibly, in a build where nothing has been configured, rather than silently
 * denying a customer in a build where something has.
 *
 * Owns its `MutableStateFlow` rather than exposing one. The cast that caused the original defect
 * was possible only because a flow the port did not own was reachable from the outside.
 */
class NoopSubscriptionProvider : SubscriptionProvider {

    /**
     * One flow per scope, created on first ask.
     *
     * A `synchronized` map rather than a bare one: `entitlement()` is a plain function, so it
     * can be called from any thread, and two scopes asked concurrently must not both see an
     * absent entry and install different flows — the loser would hold a flow nobody else can
     * reach, and its scope would silently stop updating.
     */
    private val scopes = mutableMapOf<SyncScope, StateFlow<Entitlement>>()

    override fun entitlement(scope: SyncScope): StateFlow<Entitlement> = synchronized(scopes) {
        scopes.getOrPut(scope) {
            MutableStateFlow<Entitlement>(Entitlement.Unknown(scope)).asStateFlow()
        }
    }

    /**
     * Succeeds, and establishes nothing.
     *
     * A no-op provider has no backend to ask, so this is not an error — it is the complete
     * answer. Returning `Unknown` rather than a signed-out `PurchaseState` is what stops an
     * unconfigured build from presenting itself as one where the user has been denied.
     */
    override suspend fun refresh(scope: SyncScope): Result<Entitlement> =
        Result.success(Entitlement.Unknown(scope))

    override suspend fun getFormattedPrice(sku: String): String? = null
}
