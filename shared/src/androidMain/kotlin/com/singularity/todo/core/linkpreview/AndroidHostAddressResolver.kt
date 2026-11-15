package com.singularity.todo.core.linkpreview

import com.singularity.todo.core.error.runCatchingCancellable
import java.net.InetAddress

/**
 * Android's answer to "what does this name resolve to".
 *
 * The same `InetAddress` the JVM uses — it is the platform both targets ship — kept
 * as its own class so the Android graph does not reach into `jvmMain`. The lookup
 * blocks, so the caller runs it off the main thread.
 */
class AndroidHostAddressResolver : HostAddressResolver {
    override suspend fun resolve(host: String): List<String> =
        runCatchingCancellable {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
}
