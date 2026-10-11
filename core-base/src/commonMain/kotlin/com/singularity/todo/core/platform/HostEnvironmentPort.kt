package com.singularity.todo.core.platform

/**
 * Where the process thinks it is running.
 *
 * Two facts, both of which only a platform can answer, and both of which the
 * shared code was reaching for directly until `CommonMainJvmApiTest` flagged
 * `System.getProperty("user.dir")` in `feature/ai/tools/AdrTools.kt`:
 *
 * - [workingDirectory] — the directory the process was launched from.
 * - [homeDirectory] — the current user's home.
 *
 * ## Why this is a port and not an `expect fun`
 *
 * `platformModule()` is the only `expect`/`actual` seam in this module; everything
 * else arrives as a constructor parameter. An `expect fun` here would have been
 * shorter, and would have quietly added a second seam that DI cannot see, cannot
 * override in a test, and cannot be given a fake when a test needs the ADR tools to
 * point at a temporary directory.
 *
 * ## What the values mean where they are meaningless
 *
 * On Android there is no repository checkout and no home directory in the Unix
 * sense. [AndroidHostEnvironment] answers with the app's own data directory, which
 * makes a lookup of a `docs/decisions/` folder resolve to a path that does not
 * exist — the same answer the old code produced by accident, except this one is
 * defined rather than an NPE on `System.getProperty` returning null.
 */
interface HostEnvironmentPort {

    /** The directory the process was launched from, or an empty string if unknown. */
    fun workingDirectory(): String

    /** The current user's home directory, or an empty string if unknown. */
    fun homeDirectory(): String
}
