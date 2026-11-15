package com.singularity.todo.core.linkpreview

import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import inet.ipaddr.ipv4.IPv4Address
import inet.ipaddr.ipv6.IPv6Address
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `PublicAddresses` against a reference implementation, address by address.
 *
 * ## Why an oracle
 *
 * The policy is "public unicast only", and it is hand-written in `commonMain` because
 * the module has no IP library. A hand-written classifier that is only checked against
 * *my own expectations* proves that I wrote down what I believed — not that the belief
 * is the standard. So every address here is classified twice: by `PublicAddresses`,
 * and by `com.github.seancfoley:ipaddress`, which implements the ranges the registries
 * publish. Where the two disagree, one of them is wrong and the build says so.
 *
 * ## The table
 *
 * A sweep rather than a handful of cases: a boundary is where a classifier is wrong, so
 * the sweep takes the first and last address of every block that matters — private,
 * loopback, link-local, carrier-grade NAT, benchmark, documentation, multicast,
 * reserved, plus their IPv6 counterparts and the IPv4-embedded forms. Ordinary
 * addresses are included too, so an over-eager "refuse everything" is caught.
 *
 * ## What the oracle is allowed to disagree about
 *
 * The reference answers "is this address global unicast"; this policy answers "may a
 * preview reach it". They are the same question for these ranges, which is why the sweep
 * is exact — except for the documented exemptions below.
 */
@Tag("slow")
@Tag("slow:data")
class PublicAddressesOracleTest {
    /**
     * Addresses where this policy is deliberately stricter than "global unicast".
     *
     * The shared address space is not the public internet: a host on a carrier's CGNAT
     * may answer, but it is somebody's internal network. Named rather than skipped
     * silently: a disagreement is a decision, and the decision is written here.
     */
    private val deliberatelyStricter =
        mapOf(
            "100.64.0.0" to "carrier-grade NAT",
            "100.127.255.255" to "carrier-grade NAT",
            // Unassigned space outside global unicast `2000::/3`. The reference calls
            // these ordinary because nothing reserved them; the policy refuses them
            // because nothing assigned them either, and "nobody said no" is not the
            // allowlist a fetch should use.
            "101::" to "unassigned space outside 2000::/3",
            "2002::1" to "6to4 for 0.0.0.1 — the embedded address decides",
            "2002:a00:1::1" to "6to4 for 10.0.0.1",
            "2002:7f00:1::1" to "6to4 for 127.0.0.1",
        )

    /**
     * The reference's answer, built from IPAddress's own predicates.
     *
     * Deliberately *not* a reimplementation of the policy: it asks the library which
     * ranges the address falls in. If this file and the policy were both hand-written,
     * the test would only prove they agree with me.
     */
    private fun reference(text: String): Boolean {
        val address = IPAddressString(text.trim('[', ']')).address ?: return false
        if (!address.ordinary()) return false
        return when (address) {
            is IPv4Address -> address.routable()
            is IPv6Address -> address.routable()
            else -> false
        }
    }

    /** The predicates every address has, whichever family it belongs to. */
    private fun IPAddress.ordinary(): Boolean = !isLoopback && !isZero && !isLinkLocal && !isMulticast

    /** Whether the block [cidr] contains [address]. */
    private fun inBlock(
        address: IPAddress,
        cidr: String,
    ): Boolean = IPAddressString(cidr).address?.contains(address) == true

    /** The reference's view of an ordinary IPv4 address. */
    private fun IPv4Address.routable(): Boolean {
        // `isLocal` covers private and link-local but *not* loopback or the unspecified
        // address, so those are asked for by name.
        if (!ordinary() || isLocal) return false
        return RESERVED_V4.none { inBlock(this, it) }
    }

    /** The reference's view of an ordinary IPv6 address. */
    private fun IPv6Address.routable(): Boolean {
        if (!ordinary()) return false
        if (isUniqueLocal || isSiteLocal || isTeredo) return false
        // An address that *embeds* an IPv4 one — mapped, compatible, NAT64 — is judged by
        // the address it embeds. `getEmbeddedIPv4Address()` alone is not that test: it
        // returns the low 32 bits of *any* address, so a real public IPv6 address would be
        // judged as `0.0.0.1`. The embedding has to be established first.
        // Every form that carries an IPv4 address is judged by the address it carries.
        // 6to4 matters as much as the mapped forms: `2002:7f00:1::` is 127.0.0.1
        // reached through a 6to4 prefix, and judging the outer address would call it an
        // ordinary IPv6 address and never look inside.
        embeddedIpv4()?.let { return it.routable() }
        return RESERVED_V6.none { inBlock(this, it) }
    }

    /** The IPv4 address [this] carries, or null when it carries none. */
    private fun IPv6Address.embeddedIpv4(): IPv4Address? =
        when {
            isIPv4Mapped || isIPv4Compatible -> getEmbeddedIPv4Address()
            is6To4 -> get6To4IPv4Address()
            inBlock(this, "64:ff9b::/96") -> getEmbeddedIPv4Address()
            else -> null
        }

    @Test
    fun `the classifier agrees with the reference on every address in the table`() {
        val disagreements =
            (V4 + V6)
                .distinct()
                .filter { address -> disagrees(address) }
                .map { address ->
                    "  - $address: policy says public=${PublicAddresses.isPublic(address)}, " +
                        "reference says ${reference(address)}"
                }
        assertTrue(
            disagreements.isEmpty(),
            "PublicAddresses disagrees with the reference classifier:\n" +
                disagreements.joinToString("\n") +
                "\n\nEither the policy is wrong (fix it) or the stricter answer is a decision " +
                "(record it in `deliberatelyStricter` with the reason).",
        )
    }

    /** Whether the policy and the reference disagree about [address] without a reason. */
    private fun disagrees(address: String): Boolean {
        val referenceSaysPublic = reference(address)
        val policySaysPublic = PublicAddresses.isPublic(address)
        if (referenceSaysPublic == policySaysPublic) return false
        // An exemption is allowed only when it is the documented "refuse more" kind.
        val exemption = deliberatelyStricter[address]
        return !(exemption != null && referenceSaysPublic && !policySaysPublic)
    }

    @Test
    fun `every deliberate exemption is a stricter answer, not a wider one`() {
        val wrong = deliberatelyStricter.keys.filter { PublicAddresses.isPublic(it) }
        assertTrue(
            wrong.isEmpty(),
            "these addresses are listed as deliberately refused but the policy allows them: $wrong",
        )
    }

    @Test
    fun `the table is not vacuous`() {
        assertTrue(V4.size > 40 && V6.size > 20, "the table must stay a sweep, not a handful")
        val allowed = (V4 + V6).count { PublicAddresses.isPublic(it) }
        assertTrue(allowed > 5, "the table must contain addresses the policy allows, or it proves nothing")
    }

    private companion object {
        /** The IPv4 blocks the registries set aside. */
        val RESERVED_V4 =
            listOf(
                "0.0.0.0/8", // "this network"
                "192.0.0.0/24", // IETF protocol assignments
                "198.18.0.0/15", // benchmarking
                "192.0.2.0/24",
                "198.51.100.0/24",
                "203.0.113.0/24", // documentation
                "224.0.0.0/3", // multicast and reserved
            )

        /** The IPv6 blocks the registries set aside. */
        val RESERVED_V6 =
            listOf(
                "2001:db8::/32", // documentation
                "2001:2::/48", // benchmarking
                "2001:10::/28", // deprecated ORCHID
                "3fff::/20", // documentation, assigned 2024
                "5f00::/16", // Segment Routing (SRv6) SIDs
                "100::/64", // discard
            )

        /** The IPv4 sweep: both ends of every block above, plus ordinary addresses. */
        val V4 =
            listOf(
                "0.0.0.0",
                "0.255.255.255",
                "1.1.1.1",
                "8.8.8.8",
                "9.255.255.255",
                "10.0.0.0",
                "10.255.255.255",
                "11.0.0.0",
                "100.63.255.255",
                "100.64.0.0",
                "100.127.255.255",
                "100.128.0.0",
                "126.255.255.255",
                "127.0.0.0",
                "127.0.0.1",
                "127.255.255.255",
                "128.0.0.0",
                "169.253.255.255",
                "169.254.0.0",
                "169.254.169.254",
                "169.255.0.0",
                "172.15.255.255",
                "172.16.0.0",
                "172.31.255.255",
                "172.32.0.0",
                "192.0.0.0",
                "192.0.0.8",
                "192.0.0.255",
                "192.0.1.0",
                "192.0.2.0",
                "192.0.2.255",
                "192.0.3.0",
                "192.88.99.1",
                "192.167.255.255",
                "192.168.0.0",
                "192.168.255.255",
                "192.169.0.0",
                "198.17.255.255",
                "198.18.0.0",
                "198.19.255.255",
                "198.20.0.0",
                "198.51.100.0",
                "198.51.100.255",
                "198.51.101.0",
                "203.0.113.0",
                "203.0.113.255",
                "203.0.114.0",
                "223.255.255.255",
                "224.0.0.0",
                "239.255.255.255",
                "240.0.0.0",
                "255.255.255.255",
                "93.184.216.34",
                "151.101.1.1",
                "203.0.114.1",
            )

        /** The IPv6 sweep, including the forms that embed an IPv4 address. */
        val V6 =
            listOf(
                "::",
                "::1",
                "::2",
                "64:ff9b::a00:1",
                "64:ff9b::7f00:1",
                "64:ff9b::5db8:d822",
                "100::",
                "100::1",
                "101::",
                "2001::1",
                "2001:2::1",
                "2001:2:0:ffff:ffff:ffff:ffff:ffff",
                "2001:2:1::1",
                "2001:10::1",
                "2001:1f:ffff:ffff:ffff:ffff:ffff:ffff",
                "2001:20::1",
                "2001:30::1",
                "2001:1::1",
                "2001:3::1",
                "2001:4:112::1",
                "2001:db8::1",
                "2001:db8::ffff",
                "3fff::",
                "3fff::1",
                "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff",
                "3fff:1000::1",
                "5f00::1",
                "2002::1",
                "2002:a00:1::1",
                "2002:7f00:1::1",
                "2606:4700::1",
                "2a00:1450:4001:80f::200e",
                "fc00::1",
                "fd12:3456::1",
                "fe80::1",
                "febf:ffff::1",
                "fec0::1",
                "ff02::1",
                "ff05::2",
                "::ffff:127.0.0.1",
                "::ffff:10.0.0.1",
                "::ffff:93.184.216.34",
                "::127.0.0.1",
                "::93.184.216.34",
            )
    }
}
