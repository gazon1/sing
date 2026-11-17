package com.singularity.todo.core.seed

/**
 * Decides which of several overlapping seed runs owns the screen.
 *
 * A seed link can arrive while the previous seed is still running: the debug
 * activity is `singleTask`, so the second link is delivered to the live
 * instance instead of starting a new one. Both seeds then run, and the older
 * one finishes later — when it does, it must not bring the app up and must not
 * show its failure, because the newer run owns the screen by then.
 *
 * The guard hands out a run number per seed and answers whether a run is still
 * the newest one. It is the whole rule; the caller keeps the run number.
 */
class SeedRunGuard {
    private var latestRun = 0

    /** Starts a run and returns the number that identifies it. */
    fun startRun(): Int {
        latestRun += 1
        return latestRun
    }

    /**
     * Whether the run still owns the screen — no newer run has taken over.
     *
     * A run that does not own the screen is stale: it keeps writing its own
     * rows, but it says nothing and shows nothing.
     */
    fun ownsScreen(run: Int): Boolean = run == latestRun
}
