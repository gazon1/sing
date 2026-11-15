package com.singularity.todo.core.linkpreview

/**
 * Which addresses a preview fetch may reach.
 *
 * A link preview fetches an address a *task title* names, and a title is text a
 * person or a sync peer typed. Pointing the fetcher at `http://127.0.0.1:8080`
 * would let the app read its own host's services and show the result on screen —
 * and a public page is free to redirect to one, so checking only the address the
 * user typed is not enough.
 *
 * The rule is an allowlist of *public* unicast addresses rather than a denylist of
 * familiar private ones: the denylist always misses a spelling (`127.1`, an IPv4
 * address written in IPv6 form, a NAT64 prefix), and a miss is a request that goes
 * out.
 *
 * ## What this does not answer
 *
 * It judges the address text `HostAddressResolver` returns. It cannot stop a name
 * that resolves to a public address and then changes its answer, so the fetcher
 * judges **every** hop — the typed host and each redirect target — and
 * [isFetchable] rejects names that resolve to nothing.
 */
object PublicAddresses {
    /**
     * Whether [address] — an IPv4 or IPv6 literal — is a public unicast address.
     *
     * An unparsable string is **not** public: the fetcher then refuses rather than
     * guessing, and a resolver that returned something odd fails closed.
     */
    fun isPublic(address: String): Boolean {
        val v4 = parseIpv4(address) ?: return parseIpv6(address)?.let(::isPublicIv6) ?: false
        return isPublicIv4(v4)
    }

    /**
     * Whether a request to [host] may go out at all, given what it resolves to.
     *
     * A host that is already a literal is judged directly — resolving it would be a
     * round trip that answers the same thing. A name must resolve, and every address
     * it resolves to must be public; a name that resolves to nothing is refused
     * because there is nothing to have judged.
     */
    fun isFetchable(
        host: String,
        resolved: List<String>,
    ): Boolean {
        if (isLiteral(host)) return isPublic(host)
        if (resolved.isEmpty()) return false
        return resolved.all(::isPublic)
    }

    /** Whether [host] is written as an IP literal rather than a name. */
    fun isLiteral(host: String): Boolean = parseIpv4(host) != null || parseIpv6(host) != null

    // ── IPv4 ──────────────────────────────────────────────────────────────────

    /** Four dotted decimal octets, or null. */
    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }

    private fun isPublicIv4(octets: IntArray): Boolean {
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 0 -> false

            // "this network"
            a == 10 -> false

            // private
            a == 127 -> false

            // loopback
            a == 169 && b == 254 -> false

            // link-local
            a == 172 && b in 16..31 -> false

            // private
            a == 192 && b == 168 -> false

            // private
            a == 192 && b == 0 && octets[2] == 0 -> false

            // IETF protocol assignments
            a == 100 && b in 64..127 -> false

            // carrier-grade NAT
            a == 198 && b in 18..19 -> false

            // benchmarking
            a >= 224 -> false

            // multicast, reserved, broadcast
            else -> true
        }
    }

    // ── IPv6 ──────────────────────────────────────────────────────────────────

    /**
     * The eight 16-bit groups of an IPv6 address, or null.
     *
     * Handles `::` compression and a trailing dotted IPv4 (`::ffff:127.0.0.1`), which
     * is the form an address reached through a mapped prefix arrives in.
     */
    private fun parseIpv6(text: String): IntArray? {
        val raw =
            text
                .removePrefix("[")
                .removeSuffix("]")
                .substringBefore('%')
                .lowercase()
        if (!raw.contains(':')) return null
        val halves = raw.split("::")
        if (halves.size > 2) return null
        val head = halves.first().let(::groups)
        // `::` may stand for no groups at all; its presence is what allows the count
        // to fall short of eight.
        val compressed = halves.size == 2
        val tail = if (compressed) halves.getOrNull(1)?.let(::groups) else emptyList()
        val missing = 8 - (head?.size ?: 0) - (tail?.size ?: 0)
        val valid = head != null && tail != null && missing >= 0 && (compressed || missing == 0)
        return if (valid) (head!! + List(missing) { 0 } + tail!!).toIntArray() else null
    }

    /** The groups of one side of an IPv6 address, or null when it is malformed. */
    private fun groups(side: String): List<Int>? {
        if (side.isEmpty()) return emptyList()
        val parts = side.split(':')
        val out = mutableListOf<Int>()
        for ((index, part) in parts.withIndex()) {
            val embedded = parseIpv4(part).takeIf { index == parts.lastIndex }
            val values = embedded?.let { listOf((it[0] shl 8) or it[1], (it[2] shl 8) or it[3]) } ?: hexGroup(part)
            if (values == null) return null
            out += values
        }
        return out
    }

    /** One 16-bit group, or null when [part] is not one. */
    private fun hexGroup(part: String): List<Int>? {
        if (part.isEmpty() || part.length > 4) return null
        return part.toIntOrNull(16)?.let(::listOf)
    }

    private fun isPublicIv6(groups: IntArray): Boolean {
        val first = groups[0]
        val leadingZeros = groups.take(5).all { it == 0 }
        return when {
            // Unspecified (::) and loopback (::1).
            groups.take(7).all { it == 0 } && groups[7] <= 1 -> false

            // IPv4-mapped (::ffff:0:0/96) and IPv4-compatible: judge the embedded IPv4.
            leadingZeros && (groups[5] == 0 || groups[5] == 0xffff) -> isPublicIv4(embeddedIpv4(groups))

            // NAT64 (64:ff9b::/96) embeds an IPv4 address the same way.
            isNat64(groups) -> isPublicIv4(embeddedIpv4(groups))

            first and 0xfe00 == 0xfc00 -> false

            // unique local fc00::/7
            first and 0xffc0 == 0xfe80 -> false

            // link-local fe80::/10
            first and 0xff00 == 0xff00 -> false

            // multicast ff00::/8
            first == 0x2001 && groups[1] == 0x0db8 -> false

            // documentation
            first == 0x2001 && groups[1] == 0 -> false

            // Teredo
            first == 0x0100 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0 -> false

            // discard 100::/64
            else -> true
        }
    }

    /** The last 32 bits of an address that embeds one, as four IPv4 octets. */
    private fun embeddedIpv4(groups: IntArray): IntArray =
        intArrayOf(groups[6] shr 8, groups[6] and 0xff, groups[7] shr 8, groups[7] and 0xff)

    /** The NAT64 well-known prefix `64:ff9b::/96`, which embeds an IPv4 address. */
    private fun isNat64(groups: IntArray): Boolean =
        groups[0] == 0x64 &&
            groups[1] == 0xff9b &&
            groups[2] == 0 &&
            groups[3] == 0 &&
            groups[4] == 0 &&
            groups[5] == 0
}
