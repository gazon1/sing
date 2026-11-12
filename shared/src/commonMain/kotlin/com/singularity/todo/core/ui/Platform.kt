package com.singularity.todo.core.ui

expect val isAndroidPlatform: Boolean

/**
 * Whether this is the desktop app.
 *
 * The repository ships two apps: the Android one and the JVM desktop one, so
 * exactly one of [isAndroidPlatform] and this is true.
 */
expect val isDesktopPlatform: Boolean
