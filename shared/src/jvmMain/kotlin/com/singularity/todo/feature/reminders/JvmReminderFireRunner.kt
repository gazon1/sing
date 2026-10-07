package com.singularity.todo.feature.reminders

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.profile.ProfileBootstrapper
import org.koin.core.Koin

/**
 * Runs one `fire-reminder` request from a freshly started graph.
 *
 * ## Why this exists rather than three lines in `desktopApp/main.kt`
 *
 * The two things it does must happen in this order, and the reason is not obvious at the
 * callsite:
 *
 * ```
 * ProfileBootstrapper.run()        // resolves which profile is active
 * delivery.fire(reminderId, userId) // reads the row through the ACTIVE profile
 * ```
 *
 * `ReminderRepository.get` is scoped to the active profile. Fire first and it returns
 * `null` for a row that exists — `Outcome.NotFound`, logged with a message pointing at the
 * wrong cause, from a unit that exited 0. The reminder silently does not appear and the
 * only evidence says the reminder does not exist.
 *
 * Written inline in `main.kt` that ordering is a comment, and `main.kt` is an entry point
 * no test executes. Here it is a function with one caller, and the caller is three lines
 * long because everything worth testing moved out of it.
 *
 * ## What it deliberately does not do
 *
 * It does not start Koin, create the data directory, or initialise logging. Those are
 * process setup, they have no ordering relationship to the profile, and they need a real
 * filesystem — which is what makes them untestable in the first place.
 */
object JvmReminderFireRunner {

    private val logger = Logger.withTag("fire-reminder")

    /**
     * Fire [request] against [koin], returning what happened.
     *
     * Safe to call with a graph that has not resolved a profile yet — which is the normal
     * case here, because the graph was started seconds ago. That is the entire job of this
     * function.
     */
    suspend fun run(
        koin: Koin,
        request: JvmReminderFireCommand.Request,
    ): ReminderDelivery.Outcome {
        val delivery = koin.get<ReminderDelivery>()

        val outcome = fireAfterProfileResolution(
            resolveProfile = { ProfileBootstrapper(koin.get()).run() },
            // `ReminderDelivery` is the same object Android fires through, so the text,
            // the tag and the one-shot retirement cannot differ between the platforms.
            fire = { delivery.fire(reminderId = request.reminderId, userId = request.userId) },
        )

        logger.i { "Reminder ${request.reminderId.value}: $outcome" }
        return outcome
    }

    /**
     * The ordering itself, with the graph resolved by the caller.
     *
     * [run] is the production wiring; this is the contract that wiring has to satisfy.
     * Splitting them is what makes the order testable — a `Koin` here would mean either a
     * full desktop graph in `jvmTest`, or a fake that only re-asserts the fake's own
     * construction. Both were the failure mode this function exists to end: the original
     * defect was correct code in the wrong order, verified by tests that had arranged the
     * order already.
     *
     * Nothing about the steps is negotiable — [resolveProfile] completes before [fire] is
     * called, never concurrently, and a throwing [resolveProfile] propagates rather than
     * letting [fire] run against an unresolved graph.
     */
    internal suspend fun fireAfterProfileResolution(
        resolveProfile: suspend () -> Unit,
        fire: suspend () -> ReminderDelivery.Outcome,
    ): ReminderDelivery.Outcome {
        resolveProfile()
        return fire()
    }
}
