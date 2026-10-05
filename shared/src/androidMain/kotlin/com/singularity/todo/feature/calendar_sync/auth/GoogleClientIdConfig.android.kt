package com.singularity.todo.feature.calendar_sync.auth

/**
 * Android: reads the client id baked in by the build, if any.
 *
 * `buildConfigField` in `androidApp/build.gradle.kts` populates this from
 * `local.properties` (`google.client.id`), which is not committed. A build made without
 * that file leaves the field as an empty string, so this returns null and the app asks
 * the user to paste an id instead.
 *
 * `Class.forName` rather than a direct import, for the same reason
 * `buildTimeSupabaseConfig()` does it — `shared` compiles before `androidApp` has
 * generated its `BuildConfig`.
 */
actual fun buildTimeGoogleClientId(): BuildTimeGoogleClientConfig? {
    val clientId = runCatching {
        Class.forName("com.singularity.todo.BuildConfig")
            .getField("GOOGLE_CLIENT_ID").get(null) as? String
    }.getOrNull()
    if (clientId.isNullOrBlank()) return null
    return BuildTimeGoogleClientConfig(clientId)
}
