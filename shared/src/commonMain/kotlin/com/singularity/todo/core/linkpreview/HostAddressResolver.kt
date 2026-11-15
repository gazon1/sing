package com.singularity.todo.core.linkpreview

/**
 * Resolves a host name to the addresses it stands for.
 *
 * ## Why this is a port
 *
 * Preventing an SSRF is a question about addresses, and `commonMain` cannot ask it:
 * Kotlin has no DNS API, and the answer is a platform answer (`InetAddress` on both
 * platforms this app ships). A preview fetcher that only looked at the *host name*
 * would accept `http://127.0.0.1` written in any of the spellings DNS allows, and a
 * page can redirect to one — so the fetcher asks this port and judges the answer.
 *
 * @see com.singularity.todo.core.linkpreview.PublicAddresses
 */
interface HostAddressResolver {
    /**
     * Every address [host] resolves to, as its literal text form.
     *
     * Empty when the name does not resolve at all — the caller treats that as "do not
     * fetch" rather than as "nothing to worry about". A name that resolves to several
     * addresses returns all of them: the fetcher rejects the request if *any* of them
     * is not public, because the platform may connect to any of them.
     */
    suspend fun resolve(host: String): List<String>
}
