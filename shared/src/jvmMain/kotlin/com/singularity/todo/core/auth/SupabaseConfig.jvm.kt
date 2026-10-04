package com.singularity.todo.core.auth

/**
 * JVM/Desktop: reads the development configuration from system properties.
 *
 * A developer passes `-Dsingularity.supabase.url=… -Dsingularity.supabase.anonKey=…`;
 * the packaged desktop application does not, so it cannot produce a
 * [BuildTimeSupabaseConfig] either. Both platforms therefore share one rule: the
 * "is this a development build?" question has the same answer on both, and it is
 * answered by the absence of the value rather than by a separate build flag that could
 * disagree with it.
 */
actual fun buildTimeSupabaseConfig(): BuildTimeSupabaseConfig? {
    val url = System.getProperty("singularity.supabase.url")
    val key = System.getProperty("singularity.supabase.anonKey")
    if (url.isNullOrBlank() || key.isNullOrBlank()) return null
    return BuildTimeSupabaseConfig(url = url, anonKey = key)
}
