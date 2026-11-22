package com.singularity.todo.debug

import com.singularity.todo.mcp.introspection.ScreenInspectorHost
import org.koin.core.context.GlobalContext

/**
 * The process-wide screen-inspector host: the transport's entry point into the shared
 * contract.
 *
 * ## Why this lives in `androidApp/src/debug` and not next to the contract
 *
 * [ScreenInspectorHost] is the contract — JSON in, JSON envelope out, the same object the
 * JVM tests drive. It has to be compiled for both targets, so it lives in
 * `shared/src/jvmAndroidMain`, which the Android *release* compilation also includes.
 *
 * This object is different: it is the piece that decides *which* container a
 * transport-driven request gets, and it is the only callable entry point an outside caller
 * can reach. Keeping it here means a release APK compiles the contract (inert: nothing in a
 * release build constructs it) but carries **no** host entry point at all — the debug
 * source set is not compiled into a release variant, so the object does not exist there.
 *
 * That is the distinction the first revision of this change missed: it put this object in
 * the shared set, so a release build carried a public `handle()` an agent-shaped caller
 * could have used, while the architecture test only asserted that the *provider* was
 * debug-only. Both reviewers caught it. See ADR
 * `2026-11-22-the-android-screen-inspector-host-is-a-debug-provider.md`.
 *
 * ## Lifecycle
 *
 * One host per process, created on the first request and released by [stop]. Sessions
 * outlive a request on purpose — `open` in one `content call` and `invoke` in the next is
 * the whole workflow. Nothing runs in the background, and nothing listens anywhere.
 */
internal object ScreenInspectorDeviceHost {
    /** @see ScreenInspectorHost.METHOD */
    const val METHOD: String = ScreenInspectorHost.METHOD

    @Volatile
    private var host: ScreenInspectorHost? = null

    private val lock = Any()

    /**
     * Serves one request against the running process's container.
     *
     * The container is read per request rather than captured: `stopKoin()` or a process
     * teardown makes the next request fail closed instead of driving a closed container.
     */
    fun handle(
        method: String,
        argument: String?,
    ): String = processHost().handle(method, argument)

    /** Releases the runtime; idempotent, because the caller is a lifecycle hook. */
    fun stop() {
        val closed =
            synchronized(lock) {
                host.also { host = null }
            }
        closed?.close()
    }

    /** True once a request has created the runtime; the provider's diagnostics read it. */
    fun isStarted(): Boolean = synchronized(lock) { host?.isStarted() == true }

    private fun processHost(): ScreenInspectorHost =
        synchronized(lock) {
            host ?: ScreenInspectorHost(GlobalContext::getOrNull).also { host = it }
        }
}
