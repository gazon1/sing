package com.singularity.todo.core.linkpreview

import com.singularity.todo.core.error.runCatchingCancellable
import java.net.InetAddress

/**
 * The JVM's answer to "what does this name resolve to".
 *
 * `InetAddress.getAllByName` is the platform's own resolver, so it sees the hosts
 * file, the search domains and the DNS servers the machine is actually configured
 * with — the same answers a connection would get. A name that does not resolve
 * yields an empty list rather than an exception, which is what
 * [PublicAddresses.isFetchable] reads as "refuse".
 *
 * The lookup blocks, so the caller runs it on its own dispatcher.
 */
class JvmHostAddressResolver : HostAddressResolver {
    override suspend fun resolve(host: String): List<String> =
        runCatchingCancellable {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
}
