package com.singularity.todo.core.log

import android.content.Context
import okio.Path

/**
 * Returns the log directory on Android.
 *
 * Lazily resolves the Context from Koin, because [initLogging] is called before
 * [org.koin.core.context.startKoin] — the directory is only needed when the first
 * log entry is written (after Koin is running), not at construction time.
 */
fun logDirectory(): Path {
    val context = contextOrThrow()
    return context.filesDir.toPath().resolve("logs")
}

private fun contextOrThrow(): Context {
    return org.koin.core.context.KoinPlatformTools.defaultContext().getOrNull()
        ?: throw IllegalStateException(
            "Koin context not initialized. " +
                "Ensure initLogging() is called after startKoin() or that " +
                "the platform module has registered android.content.Context.",
        )
}
