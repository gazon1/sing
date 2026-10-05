package com.singularity.todo.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android client, on OkHttp.
 *
 * OkHttp rather than the `Android` engine (`ktor-client-android`) because that artifact is
 * not a dependency of this project: only `ktor-client-okhttp` and `ktor-client-cio` are
 * declared. Adding a second engine to introduce a different one would cost APK size for no
 * behavioural gain, and the JVM actual already uses OkHttp, so a request behaves the same
 * on both platforms — including connection pooling, which matters for a background sync
 * that fires every few minutes and should not pay a TLS handshake each time.
 */
actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    // Callers map status codes onto AppError; see the common declaration for why
    // expectSuccess stays off.
    expectSuccess = false
    installSharedTimeouts()
}
