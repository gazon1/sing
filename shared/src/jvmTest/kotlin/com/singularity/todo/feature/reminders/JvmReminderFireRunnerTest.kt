package com.singularity.todo.feature.reminders

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The one ordering guarantee [JvmReminderFireRunner] exists to hold.
 *
 * ## Why the order is the whole contract
 *
 * `ReminderDelivery.fire` reads the reminder through the **active profile**. A process that
 * fires before `ProfileBootstrapper.run()` has resolved that profile gets `null` for a row
 * that exists, returns [ReminderDelivery.Outcome.NotFound], logs "reminder does not exist",
 * and exits 0. Nothing crashes, nothing is retried, the reminder simply never appears.
 *
 * That is the defect this file locks down. It shipped once already, and it shipped because
 * every test around it injected a graph where the profile had *already* been resolved by
 * the test fixture — so the tests could not fail regardless of the order the code used.
 *
 * ## Why a real Koin graph is not built here
 *
 * `JvmReminderFireRunner.run` needs a `Koin` only to obtain two dependencies. Constructing a
 * desktop graph to observe an ordering between two lambdas would test the graph, not the
 * order, and would cost a filesystem and a database. The production wiring stays in `run`;
 * the guarantee lives in `fireAfterProfileResolution`, which `run` is required to call.
 */
@Tag("fast")
class JvmReminderFireRunnerTest {

    @Test
    fun `profile is resolved before the reminder is fired`() = runTest {
        val order = mutableListOf<String>()

        JvmReminderFireRunner.fireAfterProfileResolution(
            resolveProfile = { order += "resolve-profile" },
            fire = {
                order += "fire"
                ReminderDelivery.Outcome.Posted
            },
        )

        assertEquals(
            listOf("resolve-profile", "fire"),
            order,
            "firing before the profile is resolved yields NotFound for a reminder that exists",
        )
    }

    @Test
    fun `fire reads the profile that bootstrap resolved`() = runTest {
        var activeProfile: String? = null
        var profileAtFireTime: String? = null

        val outcome = JvmReminderFireRunner.fireAfterProfileResolution(
            resolveProfile = { activeProfile = "personal" },
            fire = {
                profileAtFireTime = activeProfile
                ReminderDelivery.Outcome.Posted
            },
        )

        assertEquals("personal", profileAtFireTime)
        assertEquals(ReminderDelivery.Outcome.Posted, outcome)
    }

    @Test
    fun `each fire observes the profile its own bootstrap resolved`() = runTest {
        // Two requests in one graph: the second bootstrap can land on a different profile,
        // and a cached read from the first would make the second fire against the wrong
        // row. Asserting per-call rather than once at the end is the whole point.
        val seen = mutableListOf<String>()

        suspend fun fireOnce(profile: String) = JvmReminderFireRunner.fireAfterProfileResolution(
            resolveProfile = { seen += "resolve:$profile" },
            fire = {
                seen += "fire:$profile"
                ReminderDelivery.Outcome.Posted
            },
        )

        fireOnce("personal")
        fireOnce("work")

        assertEquals(listOf("resolve:personal", "fire:personal", "resolve:work", "fire:work"), seen)
    }

    @Test
    fun `a failed profile resolution never fires`() = runTest {
        var fired = false

        assertFailsWith<IllegalStateException> {
            JvmReminderFireRunner.fireAfterProfileResolution(
                resolveProfile = { error("no profile for --profile=ghost") },
                fire = {
                    fired = true
                    ReminderDelivery.Outcome.Posted
                },
            )
        }

        assertTrue(!fired, "a fire against an unresolved graph reports NotFound for a row that exists")
    }

    @Test
    fun `the outcome is returned unchanged`() = runTest {
        // The three outcomes are load-bearing: NoNotifier is the only evidence that a
        // reminder was armed and could not be shown. Collapsing it to Posted would lose it.
        for (expected in ReminderDelivery.Outcome.entries) {
            val actual = JvmReminderFireRunner.fireAfterProfileResolution(
                resolveProfile = {},
                fire = { expected },
            )
            assertEquals(expected, actual)
        }
    }
}
