package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import org.koin.core.scope.Scope

/**
 * Reports an AI binding that exists but cannot be created.
 *
 * The surfaces that use AI treat it as optional: a missing binding hides the AI
 * button, and a *broken* one must do the same rather than crash the screen. It
 * must not do the same *silently* — a broken binding is a defect, and this is
 * where it leaves a trace.
 */
internal fun Scope.reportBrokenAi(failure: Throwable) {
    getOrNull<Logger>()?.w(
        "An AI binding exists but cannot be created — the surface continues without AI",
        failure,
    )
}
