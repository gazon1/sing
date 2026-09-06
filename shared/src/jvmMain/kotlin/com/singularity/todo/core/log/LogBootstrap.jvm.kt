package com.singularity.todo.core.log

import co.touchlab.kermit.Logger

actual fun initLogging(isDebug: Boolean, version: String) {
    Logger.setLogWriters(ColorizedWriter())
    applyGlobalSeverity(isDebug)
    Logger.i { "Singularity Todo $version started, isDebug=$isDebug" }
}
