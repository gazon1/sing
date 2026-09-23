package com.singularity.todo.core.billing

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * No-op subscription provider — used when no real billing provider is connected.
 *
 * All methods are safe no-ops. [subscription] always emits `null`.
 */
class NoopSubscriptionProvider : SubscriptionProvider {
    override val subscription = MutableStateFlow<SubscriptionInfo?>(null)

    override suspend fun getFormattedPrice(sku: String): String? = null
}
