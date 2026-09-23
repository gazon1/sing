package com.singularity.todo.core.billing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NoopSubscriptionProviderTest {

    @Test
    fun `subscription is null by default`() = runTest {
        val provider = NoopSubscriptionProvider()
        assertEquals(null, provider.subscription.first())
    }

    @Test
    fun `getFormattedPrice returns null`() = runTest {
        val provider = NoopSubscriptionProvider()
        assertEquals(null, provider.getFormattedPrice("sku.premium.monthly"))
    }

    @Test
    fun `awaitVerification returns true`() = runTest {
        val provider = NoopSubscriptionProvider()
        assertEquals(true, provider.awaitVerification())
    }
}

class PurchaseStateTest {

    @Test
    fun `purchaseStateFor returns all false for no subscription`() = runTest {
        val provider = NoopSubscriptionProvider()
        val state = purchaseStateFor(provider)

        assertFalse(state.hasPro)
        assertFalse(state.hasAccount)
        assertFalse(state.hasSubscription)
    }
}
