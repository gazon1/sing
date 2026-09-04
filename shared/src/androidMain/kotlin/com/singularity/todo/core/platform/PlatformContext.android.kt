package com.singularity.todo.core.platform

import android.content.Context

// Android implementation of PlatformContext.
// NOTE: The properties below are stubs — Android uses get<Context>() directly
// in PlatformModule.android.kt. These exist only to satisfy the expect/actual contract.
@Suppress("UNUSED_PARAMETER", "EXPECT_ACTUAL_CLASS_IN_BETA")
actual object PlatformContext {
    actual val databasePath: String
        get() = ""

    actual val preferencesPath: String
        get() = ""

    actual val cachePath: String
        get() = ""

    actual fun initialize(context: Any) {
        // No-op: Android gets Context via Koin injection in PlatformModule
    }
}
