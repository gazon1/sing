package com.singularity.todo.core.linkpreview

import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line between an address a preview may reach and one it may not.
 *
 * The allowed cases are ordinary public addresses. The refused ones are every spelling
 * of "inside this machine or this network" that an attacker would reach for, because a
 * denylist of familiar private ranges always misses one.
 */
@Tag("fast")
class PublicAddressesTest {
    companion object {
        @JvmStatic
        fun refused(): List<Arguments> =
            listOf(
                Arguments.of("127.0.0.1"),
                Arguments.of("127.1.2.3"),
                Arguments.of("10.0.0.1"),
                Arguments.of("172.16.0.1"),
                Arguments.of("172.31.255.255"),
                Arguments.of("192.168.1.1"),
                Arguments.of("169.254.169.254"),
                Arguments.of("0.0.0.0"),
                Arguments.of("100.64.0.1"),
                Arguments.of("224.0.0.1"),
                Arguments.of("255.255.255.255"),
                Arguments.of("::1"),
                Arguments.of("::"),
                Arguments.of("fe80::1"),
                Arguments.of("fc00::1"),
                Arguments.of("fd12:3456::1"),
                Arguments.of("ff02::1"),
                Arguments.of("::ffff:127.0.0.1"),
                Arguments.of("::ffff:10.0.0.1"),
                Arguments.of("64:ff9b::7f00:1"),
                // 6to4: the embedded IPv4 is the address, so these are loopback,
                // private and "this network" reached through a 2002::/16 prefix.
                Arguments.of("2002:7f00:1::1"),
                Arguments.of("2002:a00:1::1"),
                Arguments.of("2002::1"),
                // Special-purpose blocks that sit *inside* global unicast 2000::/3 but
                // are not globally reachable: documentation 3fff::/20, benchmarking
                // 2001:2::/48, the deprecated ORCHID prefix 2001:10::/28.
                Arguments.of("3fff::1"),
                Arguments.of("3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff"),
                Arguments.of("2001:2::1"),
                Arguments.of("2001:2:0:ffff:ffff:ffff:ffff:ffff"),
                Arguments.of("2001:10::1"),
                Arguments.of("2001:1f:ffff:ffff:ffff:ffff:ffff:ffff"),
                // Outside global unicast 2000::/3 (unassigned space, deprecated
                // site-local, SRv6 SIDs) and Teredo inside it.
                Arguments.of("4000::1"),
                Arguments.of("5f00::1"),
                Arguments.of("fec0::1"),
                Arguments.of("2001::1"),
                Arguments.of("not-an-address"),
                Arguments.of(""),
            )

        @JvmStatic
        fun allowed(): List<Arguments> =
            listOf(
                Arguments.of("93.184.216.34"),
                Arguments.of("8.8.8.8"),
                Arguments.of("172.32.0.1"),
                Arguments.of("2606:2800:220:1:248:1893:25c8:1946"),
                Arguments.of("2a00:1450:4001:80f::200e"),
                // Just past the non-global blocks: the end of 3fff::/20 is 3fff:0fff:…,
                // so 3fff:1000:: is ordinary global unicast; 2001:2::/48 ends at
                // 2001:2:0:ffff:…; ORCHIDv2 2001:20::/28 is globally reachable (only the
                // deprecated 2001:10::/28 is refused).
                Arguments.of("3fff:1000::1"),
                Arguments.of("2001:2:1::1"),
                Arguments.of("2001:20::1"),
                Arguments.of("2001:30::1"),
                // Globally reachable allocations inside the 2001::/23 protocol block:
                // the PCP/TURN/DNS-SD anycast addresses, AMT, and AS112-v6.
                Arguments.of("2001:1::1"),
                Arguments.of("2001:3::1"),
                Arguments.of("2001:4:112::1"),
            )
    }

    @ParameterizedTest
    @MethodSource("refused")
    fun `a non-public address is refused`(address: String) {
        assertFalse(
            PublicAddresses.isPublic(address),
            "$address must not be reachable by a preview",
        )
    }

    @ParameterizedTest
    @MethodSource("allowed")
    fun `a public address is allowed`(address: String) {
        assertTrue(
            PublicAddresses.isPublic(address),
            "$address is a public address and must be reachable",
        )
    }

    @Test
    fun `a name is fetchable only when everything it resolves to is public`() {
        assertTrue(PublicAddresses.isFetchable("example.com", listOf("93.184.216.34")))
        assertFalse(PublicAddresses.isFetchable("example.com", listOf("93.184.216.34", "127.0.0.1")))
    }

    @Test
    fun `a name that does not resolve is not fetchable`() {
        assertFalse(PublicAddresses.isFetchable("example.com", emptyList()))
    }

    @Test
    fun `a literal host is judged as it stands, without asking a resolver`() {
        assertTrue(PublicAddresses.isFetchable("93.184.216.34", emptyList()))
        assertFalse(PublicAddresses.isFetchable("127.0.0.1", emptyList()))
    }

    @Test
    fun `a padded octet is not a literal, so the platform resolver decides`() {
        // `0177.0.0.1` is 127.0.0.1 to a resolver that reads octal and 177.0.0.1 to one
        // that reads decimal. Two stacks disagree, so this parser must not have an
        // opinion: the string is not a literal, and the answer comes from whichever
        // resolver the connecting stack actually uses.
        assertFalse(PublicAddresses.isLiteral("0177.0.0.1"))
        assertFalse(PublicAddresses.isLiteral("001.002.003.004"))
        // A bare zero is not padding.
        assertTrue(PublicAddresses.isLiteral("0.0.0.0"))
        assertTrue(PublicAddresses.isLiteral("10.0.0.1"))
        // And a padded octet is never reported public on this parser's reading alone.
        assertFalse(PublicAddresses.isPublic("0177.0.0.1"))
    }

    @Test
    fun `a padded-octet host is judged by what it resolves to`() {
        // On a resolver that reads it as loopback, this is refused; on one that reads it
        // as 177.0.0.1, that address is what the connection reaches and what is judged.
        assertFalse(PublicAddresses.isFetchable("0177.0.0.1", listOf("127.0.0.1")))
        assertTrue(PublicAddresses.isFetchable("0177.0.0.1", listOf("177.0.0.1")))
        // A resolver that answers nothing cannot be assumed safe.
        assertFalse(PublicAddresses.isFetchable("0177.0.0.1", emptyList()))
    }
}
