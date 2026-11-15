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
}
