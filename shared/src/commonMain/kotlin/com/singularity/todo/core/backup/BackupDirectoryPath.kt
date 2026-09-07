package com.singularity.todo.core.backup

/**
 * Returns the platform-specific backup directory path.
 * This is where zip backups are stored on disk.
 * Platform-specific values are provided via Koin in PlatformModule.android.kt / PlatformModule.jvm.kt.
 */
val backupDirectoryPath: String
    get() = throw IllegalStateException("backupDirectoryPath must be provided by platform module")
