package com.singularity.todo.core.platform

expect object PlatformContext {
    val databasePath: String
    val preferencesPath: String
    val cachePath: String
    fun initialize(context: Any)
}
