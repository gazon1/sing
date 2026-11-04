package com.singularity.todo.test.stubs

import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The release-notes payload an end-to-end flow asked to see, or `null` for the real behaviour.
 *
 * The WhatsNew sheet renders `RemoteConfigSnapshot.whatsNewPayload`, which the production port
 * fetches from a config server and caches. The E2E build has no server, so the payload — and
 * therefore the sheet — could not be reached from a flow at all: the surface existed, the flows
 * had nothing to drive. Armed from the debug seed link, this object is the missing trigger.
 *
 * It is deliberately in-memory and not persisted: the seed activity arms it and then starts the
 * app in the *same* process, so one process is all it ever has to survive, and a flow that ends
 * cannot leak a payload into the next one — the runner force-stops the app between flows.
 */
object DebugWhatsNewArming {
    private val _requested = MutableStateFlow<String?>(null)

    /** What the sheet should show, or `null` when nothing was armed. */
    val requested: StateFlow<String?> = _requested.asStateFlow()

    /** Arms the sheet with a one-line release note carrying [text]. */
    fun arm(text: String) {
        _requested.value = text
    }

    /**
     * The A2UI payload for [text].
     *
     * The payload itself is built in commonMain, where a test can parse it — a hand-written
     * A2UI string nothing reads is an empty sheet the first time a flow reaches the device.
     */
    internal fun payloadFor(text: String): String = whatsNewDemoPayload(text)
}

/**
 * A [RemoteConfigPort] that delegates to the real one and overlays an armed payload.
 *
 * Everything else — the minimum supported version the gate reads, the maintenance banner, the
 * flags — still comes from the delegate, so the E2E build exercises the app's own config path
 * rather than a snapshot invented for the test.
 *
 * With nothing armed this is a pass-through, which is the point: an override that always
 * carried a payload would put a modal sheet over every flow in the suite.
 */
internal class DebugRemoteConfigPort(
    private val delegate: RemoteConfigPort,
    scope: CoroutineScope,
) : RemoteConfigPort {
    private val overlay: MutableStateFlow<RemoteConfigSnapshot> =
        MutableStateFlow(delegate.observe().value.withArmedPayload(DebugWhatsNewArming.requested.value))

    init {
        // Collection rather than a captured value: the seed activity arms the payload after Koin
        // has already started, so the port may well be constructed before there is anything to
        // show. Its `observe()` has to gain the payload when it arrives, not only at construction.
        scope.launch {
            combine(delegate.observe(), DebugWhatsNewArming.requested) { snapshot, text ->
                snapshot.withArmedPayload(text)
            }.collect { overlay.value = it }
        }
    }

    override fun observe(): StateFlow<RemoteConfigSnapshot> = overlay.asStateFlow()

    override suspend fun snapshot(): RemoteConfigSnapshot =
        delegate.snapshot().withArmedPayload(DebugWhatsNewArming.requested.value)

    override suspend fun refresh(): Result<RemoteConfigSnapshot> =
        delegate.refresh().map { it.withArmedPayload(DebugWhatsNewArming.requested.value) }
}

private fun RemoteConfigSnapshot.withArmedPayload(text: String?): RemoteConfigSnapshot =
    if (text == null) this else copy(whatsNewPayload = DebugWhatsNewArming.payloadFor(text))

/**
 * Koin module for E2E builds that lets the seed link deliver a WhatsNew payload.
 *
 * Loaded **instead of** the production `RemoteConfigPort` binding, through
 * [com.singularity.todo.SingularityApp.extraKoinModules] with `allowOverride = true`.
 *
 * @param delegate the real port, resolved *before* this module is loaded. It has to be passed in
 *   rather than resolved with `get()`: this module replaces the `RemoteConfigPort` binding, so a
 *   `get()` inside it would resolve this binding and recurse without end.
 */
fun testRemoteConfigModule(delegate: RemoteConfigPort): Module =
    module {
        single<RemoteConfigPort> { DebugRemoteConfigPort(delegate = delegate, scope = get()) }
    }
