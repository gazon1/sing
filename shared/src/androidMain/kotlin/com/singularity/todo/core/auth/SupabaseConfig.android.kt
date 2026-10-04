package com.singularity.todo.core.auth

/**
 * Android: reads the development configuration from `BuildConfig`.
 *
 * The release build type leaves both fields empty, so a shipped APK cannot produce a
 * [BuildTimeSupabaseConfig] and the app has to handle "no server configured" as a
 * state. That is the intended behaviour: a release build reaches a server the user
 * names, never one baked in at build time.
 *
 * `Class.forName` rather than a direct import, for the same reason `appVersion()`
 * does it — `shared` compiles before `androidApp` has generated its `BuildConfig`.
 */
actual fun buildTimeSupabaseConfig(): BuildTimeSupabaseConfig? {
    val url = Class.forName("com.singularity.todo.BuildConfig")
        .getField("SUPABASE_URL").get(null) as? String
    val key = Class.forName("com.singularity.todo.BuildConfig")
        .getField("SUPABASE_ANON_KEY").get(null) as? String
    if (url.isNullOrBlank() || key.isNullOrBlank()) return null
    return BuildTimeSupabaseConfig(url = url, anonKey = key)
}
