package com.singularity.todo.feature.calendar_sync.auth

/**
 * JVM/Desktop: reads the client id from a system property.
 *
 * A developer runs with `-Dsingularity.google.clientId=…`; the packaged desktop
 * application does not, so it returns null and the user is asked to paste an id in
 * settings. Same rule as the Supabase configuration on this platform: "is this a
 * development build?" is answered by the absence of the value, never by a separate flag
 * that could disagree with it.
 */
actual fun buildTimeGoogleClientId(): BuildTimeGoogleClientConfig? {
    val clientId = System.getProperty("singularity.google.clientId")
    if (clientId.isNullOrBlank()) return null
    return BuildTimeGoogleClientConfig(clientId)
}
