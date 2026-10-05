package com.singularity.todo.core.platform

import android.content.Context

/**
 * Android actual of [HostEnvironmentPort].
 *
 * Android has no working directory to speak of — an app is not launched from one —
 * and its Unix home is not readable. Both answers are therefore the app's private
 * data directory, which is the only directory the app may name without a permission
 * prompt.
 *
 * That makes a lookup for a `docs/decisions/` folder resolve to a path that does not
 * exist, so [AdrTools][com.singularity.todo.feature.ai.tools.AdrStorage.listAdrs]
 * returns an empty list. That is the correct answer for a phone, and it is now
 * produced on purpose: the previous code asked `System.getProperty("user.dir")`,
 * which is not a documented Android property, and fell through to
 * `Path(getProperty("user.home"), …)` — a second undocumented property — on the way
 * to throwing or to writing somewhere no user could find.
 */
class AndroidHostEnvironment(context: Context) : HostEnvironmentPort {

    private val dataDirectory: String = context.filesDir.absolutePath

    override fun workingDirectory(): String = dataDirectory

    override fun homeDirectory(): String = dataDirectory
}
