package com.singularity.todo.core.platform

actual object PlatformContext {
    private val baseDir = java.io.File(
        java.lang.System.getProperty("user.home"),
        ".singularity-todo"
    ).apply { mkdirs() }

    actual val databasePath: String = java.io.File(baseDir, "singularity.db").absolutePath
    actual val preferencesPath: String = baseDir.absolutePath
    actual val cachePath: String = java.io.File(
        java.lang.System.getProperty("java.io.tmpdir"),
        "singularity-todo"
    ).absolutePath

    actual fun initialize(context: Any) {
        // No-op on JVM
    }
}
