package com.singularity.todo.core.seed

/**
 * How a failed seed says so.
 *
 * Every report of a failed seed carries the same prefix: the screen the debug
 * seed activity leaves up, the logcat line, and the runner's summary next to
 * the flow failure. One prefix, three readers — the seed helper asserts that
 * the prefix is *not* on the screen, the runner greps the logcat for it, and a
 * human reading the device sees it. A reason nobody can find is not a reason.
 */
object SeedFailure {
    /** The prefix every report of a failed seed carries. */
    const val PREFIX: String = "Seed failed: "
}
