package com.singularity.todo.core.platform

import android.content.Context

actual object PlatformContext {
    private lateinit var context: Context

    actual val databasePath: String
        get() = context.getDatabasePath("singularity.db").absolutePath

    actual val preferencesPath: String
        get() = context.filesDir.absolutePath + "/settings"

    actual val cachePath: String
        get() = context.cacheDir.absolutePath

    actual fun initialize(context: Any) {
        this.context = context as Context
    }
}
