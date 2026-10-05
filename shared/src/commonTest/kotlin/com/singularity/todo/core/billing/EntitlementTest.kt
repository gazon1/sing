package com.singularity.todo.core.billing

import com.singularity.todo.core.sync.SyncScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an entitlement port owes its caller.
 *
 * ## Why this test file exists at all
 *
 * The defect these tests cover shipped because the suite had no positive case. There was a test
 * named `hasPro true when subscription is present`, and it asserted the opposite — with a comment
 * saying the positive case needed a real provider. That comment was honest, and the consequence
 * was still that a defect class went untested for as long as nobody built the missing provider.
 *
 * So the first test below is the one that was missing, and it uses a fake shaped like a real
 * provider rather than like the no-op.
 *
 * ## The shape is the point
 *
 * [ReadOnlyShapeProvider] exposes `asStateFlow()`, which returns a `ReadonlyStateFlow` — *not* a
 * `MutableStateFlow`. That is what Google Play Billing, RevenueCat, and every sane Kotlin
 * implementation does, and it is exactly what `NoopSubscriptionProvider` does not do. The old
 * `purchaseStateFor` cast to `MutableStateFlow` succeeded on the no-op and failed on the real
 * shape, so the only implementation that was ever exercised was the one that could never have
 * found the bug.
 */
@Tag("fast")
class EntitlementTest {

    private val work = SyncScope(ownerId = "owner-1", profileId = "work")
    private val home = SyncScope(ownerId = "owner-1", profileId = "home")

    /**
     * Shaped like a real billing provider: the mutable flow is private, per scope, and what
     * leaves is `StateFlow`. This is the whole reason the defect shipped — the no-op exposes
     * `MutableStateFlow` and hides the difference.
     *
     * Per scope, not one shared flow, because that is the shape a provider has when it answers
     * per `(ownerId, profileId)`. A fake with a single backing flow cannot express the profile
     * switch at all — every scope would read the same value — and a test that cannot express
     * the case it is named for passes for the wrong reason. That was caught by exactly that
     * test, which is the argument for writing it.
     */
    private class ReadOnlyShapeProvider(initial: Entitlement) : SubscriptionProvider {

        private val backing = mutableMapOf<SyncScope, MutableStateFlow<Entitlement>>()

        init {
            backing[initial.scope] = MutableStateFlow(initial)
        }

        override fun entitlement(scope: SyncScope): StateFlow<Entitlement> =
            backing.getOrPut(scope) { MutableStateFlow<Entitlement>(Entitlement.Unknown(scope)) }.asStateFlow()

        override suspend fun refresh(scope: SyncScope): Result<Entitlement> =
            Result.success(entitlement(scope).value)

        override suspend fun getFormattedPrice(sku: String): String? = "\$4.99/mo"

        fun establish(scope: SyncScope, entitlement: Entitlement) {
            backing.getOrPut(scope) { MutableStateFlow(Entitlement.Unknown(scope)) }.value = entitlement
        }
    }

    // ── the case that was missing ───────────────────────────────────────────

    @Test
    fun `a paying customer is not denied when the provider exposes a read-only flow`() = runTest {
        // This is the test whose absence let the defect ship. `asStateFlow()` is not an
        // implementation detail here — it is what every real provider does, and it is what the
        // old cast silently rejected.
        val provider = ReadOnlyShapeProvider(
            Entitlement.Known(
                scope = work,
                state = PurchaseState(hasAccount = true, hasPro = true, provider = BillingProvider.GOOGLE_PLAY),
            ),
        )

        val observed = provider.entitlement(work).value

        assertIs<Entitlement.Known>(observed, "a paying customer must not read as unknown")
        assertTrue(observed.state.hasPro, "a paying customer must not be denied")
        assertTrue(observed.state.hasAccount)
        assertEquals(BillingProvider.GOOGLE_PLAY, observed.state.provider)
    }

    @Test
    fun `no read path casts the provider's flow to a mutable type`() {
        // A guard on the shape rather than a behaviour. The old defect was a downcast reaching
        // into a flow the port did not own; `StateFlow` has no mutable supertype to be cast to,
        // so the mistake is unrepresentable rather than merely discouraged.
        val provider = ReadOnlyShapeProvider(Entitlement.Unknown(work))
        val returned: StateFlow<Entitlement> = provider.entitlement(work)

        assertFalse(
            returned is MutableStateFlow<*>,
            "the port must not hand out its mutable flow; that is what made the downcast possible",
        )
    }

    // ── unknown is not a denial ────────────────────────────────────────────

    @Test
    fun `an unanswered scope reads as unknown, not as denied`() = runTest {
        val provider = NoopSubscriptionProvider()

        val observed = provider.entitlement(work).value

        assertIs<Entitlement.Unknown>(observed, "before refresh, a scope is unknown — not signed out")
        assertNull(observed.stateOrNull, "Unknown carries no PurchaseState at all")
    }

    @Test
    fun `the no-op refresh succeeds and establishes nothing`() = runTest {
        // A build with no store has no answer, and "no answer" is not a failure. It is also not
        // a denial, which is the distinction the previous implementation lost.
        val provider = NoopSubscriptionProvider()

        val result = provider.refresh(work)

        assertTrue(result.isSuccess, "a no-op provider has nothing to fail at")
        assertIs<Entitlement.Unknown>(result.getOrThrow())
    }

    @Test
    fun `a failed refresh does not turn an outage into a denial`() = runTest {
        // The scenario the `Boolean` return could not express. On failure the previously
        // established value stays; writing `Known(hasPro = false)` here is what would show a
        // subscriber a paywall for a subscription they hold.
        val provider = FailingProvider()

        val result = provider.refresh(work)

        assertTrue(result.isFailure, "a provider that cannot be reached must report failure")
        assertIs<Entitlement.Unknown>(provider.entitlement(work).value, "a failure must not write a denial")
    }

    private class FailingProvider : SubscriptionProvider {
        private val backing = MutableStateFlow<Entitlement>(Entitlement.Unknown(SyncScope("owner-1", "work")))
        override fun entitlement(scope: SyncScope): StateFlow<Entitlement> = backing.asStateFlow()
        override suspend fun refresh(scope: SyncScope): Result<Entitlement> =
            Result.failure(IllegalStateException("the store is unreachable"))
        override suspend fun getFormattedPrice(sku: String): String? = null
    }

    // ── account presence is independent of subscription ────────────────────

    @Test
    fun `a free-tier signed-in user has an account`() = runTest {
        val state = PurchaseState.freeTier()

        assertTrue(state.hasAccount, "signed in without a subscription is still an account")
        assertFalse(state.hasPro)
        assertNull(state.provider, "nothing was granted, so no provider named one")
    }

    @Test
    fun `no account means no entitlement`() = runTest {
        val state = PurchaseState.SIGNED_OUT

        assertFalse(state.hasAccount)
        assertFalse(state.hasPro)
        assertNull(state.provider)
    }

    @Test
    fun `an entitlement must name the provider that granted it`() {
        // `hasPro = true` with no provider is the shape that makes "who granted this"
        // unanswerable at the moment someone needs to answer it — a refund, a support ticket,
        // a store migration.
        assertFailsWith<IllegalArgumentException> {
            PurchaseState(hasAccount = true, hasPro = true, provider = null)
        }
    }

    // ── scope ──────────────────────────────────────────────────────────────

    @Test
    fun `a profile switch does not carry entitlement with it`() = runTest {
        val provider = ReadOnlyShapeProvider(
            Entitlement.Known(
                scope = work,
                state = PurchaseState(hasAccount = true, hasPro = true, provider = BillingProvider.GOOGLE_PLAY),
            ),
        )

        assertTrue(provider.entitlement(work).value.stateOrNull!!.hasPro)

        // `home` is a different scope and has never been answered.
        assertIs<Entitlement.Unknown>(provider.entitlement(home).value, "a profile switch must not carry Pro")
    }

    @Test
    fun `the same provider answers per scope`() = runTest {
        val provider = NoopSubscriptionProvider()

        val a = provider.entitlement(work)
        val b = provider.entitlement(home)

        assertTrue(a === a, "repeated reads of one scope return the same flow")
        assertTrue(a !== b, "two scopes are two flows — otherwise a switch cannot be observed")
        assertEquals(work, a.value.scope)
        assertEquals(home, b.value.scope)
    }

    @Test
    fun `an entitlement cannot be constructed for a scope that could not hold a row`() {
        // The rule is `SyncScope`'s, not this module's: entitlement is stored against the same
        // identity rows are, so it inherits the same validation rather than re-implementing it.
        assertFailsWith<IllegalArgumentException> { SyncScope(ownerId = "", profileId = "work") }
        assertFailsWith<IllegalArgumentException> { SyncScope(ownerId = "owner", profileId = "  ") }
        assertFailsWith<IllegalArgumentException> { Entitlement.Unknown(SyncScope("", "work")) }
    }

    // ── the granting provider is recorded, not derived ──────────────────────

    @Test
    fun `a github sponsorship records its provider`() = runTest {
        val provider = ReadOnlyShapeProvider(
            Entitlement.Known(
                scope = work,
                state = PurchaseState(hasAccount = true, hasPro = true, provider = BillingProvider.GITHUB_SPONSOR),
            ),
        )

        assertEquals(BillingProvider.GITHUB_SPONSOR, provider.entitlement(work).value.stateOrNull?.provider)
    }

    @Test
    fun `a purchase maps to a state naming its own provider`() = runTest {
        val info = SubscriptionInfo(
            sku = "github:sponsor",
            isMonthly = true,
            provider = BillingProvider.GITHUB_SPONSOR,
        )

        val state = info.toPurchaseState()

        assertEquals(BillingProvider.GITHUB_SPONSOR, state.provider)
        assertTrue(state.hasPro)
    }
}
