package com.singularity.todo.core.sync

import kotlin.math.max

/**
 * Hybrid Logical Clock — inline value class for zero overhead.
 *
 * Encoded as "physical:counter:node" string.
 * Provides total ordering with wall-clock fallback.
 *
 * Algorithm: Hybrid Logical Clock (HLC) as described in
 * "Logical Physical Clocks and Consistent Snapshots in Distributed Systems"
 */
@JvmInline
value class Hlc(val encoded: String) : Comparable<Hlc> {

    val physical: Long get() = encoded.substringBefore(':').toLongOrNull() ?: 0L
    val counter: Int get() = encoded.split(':').getOrNull(1)?.toIntOrNull() ?: 0
    val node: String get() = encoded.substringAfterLast(':')

    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::physical, Hlc::counter, Hlc::node)

    override fun toString(): String = encoded

    companion object {
        /**
         * Creates a new HLC tick — called on every local event.
         */
        fun tick(last: Hlc?, node: String, nowMillis: Long): Hlc {
            val lastPhysical = last?.physical ?: 0L
            val lastCounter = last?.counter ?: 0

            val physical = max(nowMillis, lastPhysical)
            val counter = if (physical == lastPhysical) lastCounter + 1 else 0

            return Hlc("$physical:$counter:$node")
        }

        /**
         * Merges a received remote HLC with local — called on every received message.
         */
        fun tock(local: Hlc, remote: Hlc, node: String, nowMillis: Long): Hlc {
            val maxPhysical = max(max(local.physical, remote.physical), nowMillis)

            val counter = when {
                maxPhysical == local.physical && maxPhysical == remote.physical ->
                    max(local.counter, remote.counter) + 1
                maxPhysical == local.physical -> local.counter + 1
                maxPhysical == remote.physical -> remote.counter + 1
                else -> 0
            }

            return Hlc("$maxPhysical:$counter:$node")
        }

        /**
         * Parses an encoded HLC string.
         */
        fun parse(encoded: String): Hlc = Hlc(encoded)

        /**
         * Creates an HLC from components.
         */
        fun of(physical: Long, counter: Int, node: String): Hlc = Hlc("$physical:$counter:$node")

        /**
         * Zero HLC for initialization.
         */
        fun zero(node: String): Hlc = Hlc("0:0:$node")
    }
}
