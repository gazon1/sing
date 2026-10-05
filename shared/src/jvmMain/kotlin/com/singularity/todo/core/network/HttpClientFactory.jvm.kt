package com.singularity.todo.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * JVM/desktop client, on CIO.
 *
 * CIO because that is the engine this project declares for the JVM target
 * (`ktor-client-cio` in `shared/build.gradle.kts`); OkHttp is the Android one. Each
 * platform uses the engine it already ships, rather than both bundling OkHttp so the
 * engines match — the two behave the same over the wire, which is all this contract
 * promises, and dropping a dependency is worth more than the symmetry.
 */
actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    // Callers map status codes onto AppError. Google answers 410 for a dead sync token and
    // 412 for a stale etag; both are ordinary branches here, not transport failures.
    expectSuccess = false
    installSharedTimeouts()
}
