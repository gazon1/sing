package com.singularity.todo.core.seed

/**
 * The seed link the debug activity is holding, and the rules about arrivals.
 *
 * The activity has three callbacks that can carry a link — [onCreate] with the
 * link it was started with, [onNewIntent] with a link that arrived at a live
 * instance, and [onResume], which carries whatever the activity is holding
 * *now*. Keeping the "which link is current" bookkeeping here, next to the
 * rules that depend on it, is what makes the sequence testable without an
 * Android harness: the activity's callbacks are three lines each, and the
 * behaviour they produce is held by a test.
 *
 * The rules themselves live in [SeedSequencer]: one seed at a time in arrival
 * order, the newest link owns the screen, a link is handled once.
 */
class SeedLinkLifecycle<T : Any> {
    private val sequencer = SeedSequencer()

    /** The link the activity is holding. */
    private var current: T? = null

    /**
     * The link the activity was started with.
     *
     * Handled here and not left to the resume: a link that reaches the activity
     * while it is still starting arrives through [onNewIntent], and the resume
     * would then see the *new* link instead of the launch one — the launch link
     * would never be seeded at all.
     */
    fun onCreate(link: T?) {
        current = link
    }

    /**
     * A link that arrived at a live instance.
     *
     * It becomes the link the activity holds from now on, so the resume that
     * follows sees this link and not the one the activity was started with.
     */
    fun onNewIntent(link: T?) {
        current = link
    }

    /** The link the activity is holding now. */
    fun current(): T? = current

    /**
     * Registers the current link as an arrival, and returns it.
     *
     * Returns null when there is no link, or when the current link was handled
     * already: the same link comes back through the resume right after its
     * arrival was recorded, and that is not a second seed.
     */
    fun arrive(): SeedArrival? = current?.let { sequencer.arrive(it) }

    /**
     * Whether the arrival still owns the screen — no newer link has arrived.
     */
    fun ownsScreen(arrival: SeedArrival): Boolean = sequencer.ownsScreen(arrival)

    /**
     * Runs the seed, one at a time, in arrival order.
     */
    suspend fun <R> run(
        arrival: SeedArrival,
        block: suspend () -> R,
    ): R = sequencer.run(arrival, block)
}
