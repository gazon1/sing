package com.singularity.todo.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/**
 * The project's single HTTP client factory.
 *
 * ## Why this exists
 *
 * `docs/PLATFORM-REFERENCE.md` has documented a `createHttpClient()` for some time, and
 * `core/database/contract/SqlDriverFactory.kt` even references
 * `com.singularity.todo.core.network.createHttpClient` — but no such thing existed. The
 * only `HttpClient` in `shared` was a `by lazy` local inside `KoogAgentService`, built for
 * one caller with one caller's timeouts.
 *
 * That combination is a trap: the next network caller copies the `by lazy` block, invents
 * its own timeouts, and two features quietly disagree about what a reasonable request
 * timeout is. So the factory is created here, and the documentation becomes true.
 *
 * ## Why `expectSuccess = false`
 *
 * Every caller maps HTTP status onto the project's own [com.singularity.todo.core.error.AppError]
 * hierarchy, and several status codes are *expected* outcomes rather than transport
 * failures — Google answers `410` when a sync token expires, which is a normal branch in an
 * incremental sync, and `412` when an etag is stale, which means "re-read and re-plan".
 * Letting Ktor throw on those would turn two routine paths into exceptions, and would
 * discard the response body the error mapping needs.
 *
 * ## Why the timeouts are what they are
 *
 * A calendar sync runs unattended in the background, so a request that hangs is worse than
 * one that fails: it holds a worker slot and delays every later sync. Ten seconds to
 * connect and thirty for the whole exchange is a ceiling on a round trip, not a target, and
 * it is an order of magnitude above what an interactive request should need.
 *
 * Callers that want something else — a bulk import that legitimately takes minutes — build
 * their own client and say why. They do not widen this one for everybody.
 */
expect fun createHttpClient(): HttpClient

/** Timeouts shared by every client this project builds. */
internal object NetworkTimeouts {
    /** How long to wait for the connection itself. */
    const val CONNECT_MILLIS = 10_000L

    /**
     * An upper bound on the whole exchange, not just the socket read.
     *
     * Ktor's `requestTimeoutMillis` is the latter; a caller waiting on a calendar refresh
     * is waiting on the former.
     */
    const val REQUEST_MILLIS = 30_000L
}

/** Applies the shared timeout policy. Extracted so both actuals stay in step. */
internal fun HttpClientConfig<*>.installSharedTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = NetworkTimeouts.CONNECT_MILLIS
        requestTimeoutMillis = NetworkTimeouts.REQUEST_MILLIS
    }
}
