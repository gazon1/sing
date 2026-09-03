package com.singularity.todo.feature.genui.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A type-safe JSON Pointer path into a [DataModel].
 *
 * Supports three operations: root, index-based child, and property access.
 * Serializable so it can be sent as a tool argument or stored in state.
 *
 * Corresponds to RFC 6901 JSON Pointer with the prefix `/` removed,
 * encoded as sealed subtypes for compile-time exhaustiveness.
 */
@Serializable
sealed interface UiPath {
    /** The root of the data model (no path segments). */
    @Serializable @SerialName("root")
    data object Root : UiPath

    /** Index into an array node, followed by [tail]. */
    @Serializable @SerialName("child")
    data class Child(val index: Int, val tail: UiPath) : UiPath

    /** Property access on an object node, followed by [tail]. */
    @Serializable @SerialName("prop")
    data class Prop(val name: String, val tail: UiPath) : UiPath

    /** Last segment of a path (no tail). */
    @Serializable @SerialName("leaf")
    data class Leaf(val segment: Segment) : UiPath

    @Serializable
    sealed interface Segment {
        @Serializable @SerialName("idx") data class Index(val value: Int) : Segment
        @Serializable @SerialName("key") data class Key(val value: String) : Segment
    }

    companion object {
        /** Parses a string like "items/0/label" into a UiPath. */
        fun parse(s: String): UiPath {
            if (s.isEmpty() || s == "/") return Root
            val segments: List<String> = s.trimStart('/')
                .split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return segments.reversed().fold(Root as UiPath) { acc: UiPath, seg: String ->
                when {
                    seg.all { it.isDigit() } -> Child(seg.toInt(), acc)
                    else -> Prop(seg, acc)
                }
            }
        }

        /** Builds a simple path like `items/0/label` from varargs. */
        fun of(vararg segments: String): UiPath = of(segments.toList())

        fun of(segments: List<String>): UiPath {
            if (segments.isEmpty()) return Root
            return segments.reversed().fold(Root as UiPath) { acc: UiPath, seg: String ->
                when {
                    seg.all { it.isDigit() } -> Child(seg.toInt(), acc)
                    else -> Prop(seg, acc)
                }
            }
        }
    }
}

/**
 * Converts a UiPath to a list of string segments in root→leaf order.
 * Example: `UiPath.of("user", "name")` → Prop("user", Prop("name", Root)) → ["user", "name"]
 */
fun UiPath.toSegmentList(): List<String> {
    fun walk(p: UiPath, acc: List<String>): List<String> = when (p) {
        is UiPath.Root -> acc
        is UiPath.Child -> walk(p.tail, acc + p.index.toString())
        is UiPath.Prop -> walk(p.tail, acc + p.name)
        is UiPath.Leaf -> acc + p.segment.toString()
    }
    return walk(this, emptyList())
}

/** Returns the string representation of this path (RFC 6901 style, without leading /). */
fun UiPath.toPointer(): String {
    return when (this) {
        UiPath.Root -> ""
        is UiPath.Child -> "${this.index}${tailSuffix()}"
        is UiPath.Prop -> "${this.name}${tailSuffix()}"
        is UiPath.Leaf -> this.segment.toString()
    }
}

private fun UiPath.tailSuffix(): String {
    val tail: UiPath = when (this) {
        is UiPath.Child -> this.tail
        is UiPath.Prop -> this.tail
        is UiPath.Leaf -> return ""
        UiPath.Root -> return ""
    }
    if (tail is UiPath.Root) return ""
    return "/" + tail.toPointer()
}

private fun UiPath.Segment.toString(): String {
    return when (this) {
        is UiPath.Segment.Index -> this.value.toString()
        is UiPath.Segment.Key -> this.value
    }
}
