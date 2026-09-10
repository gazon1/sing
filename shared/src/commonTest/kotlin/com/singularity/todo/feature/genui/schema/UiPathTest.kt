package com.singularity.todo.feature.genui.schema

import org.junit.Assert.assertEquals
import org.junit.Test

class UiPathTest {

    @Test
    fun parseReturnsRootForEmptyString() {
        assertEquals(UiPath.Root, UiPath.parse(""))
        assertEquals(UiPath.Root, UiPath.parse("/"))
    }

    @Test
    fun parseReturnsRootForWhitespaceOnly() {
        assertEquals(UiPath.Root, UiPath.parse("   "))
    }

    @Test
    fun parseHandlesSingleSegment() {
        assertEquals(
            UiPath.Prop("items", UiPath.Root),
            UiPath.parse("items"),
        )
    }

    @Test
    fun parseHandlesMultipleSegments() {
        val result = UiPath.parse("items/0/label")
        val expected = UiPath.Prop(
            name = "items",
            tail = UiPath.Child(
                index = 0,
                tail = UiPath.Prop("label", UiPath.Root),
            ),
        )
        assertEquals(expected, result)
    }

    @Test
    fun parseTreatsNumericSegmentsAsChildIndex() {
        val result = UiPath.parse("items/42/name")
        val expected = UiPath.Prop(
            name = "items",
            tail = UiPath.Child(
                index = 42,
                tail = UiPath.Prop("name", UiPath.Root),
            ),
        )
        assertEquals(expected, result)
    }

    @Test
    fun ofBuildsFromVarargs() {
        val result = UiPath.of("items", "0", "label")
        val expected = UiPath.parse("items/0/label")
        assertEquals(expected, result)
    }

    @Test
    fun ofReturnsRootForEmptyList() {
        assertEquals(UiPath.Root, UiPath.of())
    }

    @Test
    fun toPointerReturnsEmptyForRoot() {
        assertEquals("", UiPath.Root.toPointer())
    }

    @Test
    fun toPointerReturnsSegmentForSingleProp() {
        assertEquals("items", UiPath.Prop("items", UiPath.Root).toPointer())
    }

    @Test
    fun toPointerReturnsSegmentsSeparatedBySlash() {
        val path = UiPath.Prop(
            name = "items",
            tail = UiPath.Child(0, UiPath.Prop("label", UiPath.Root)),
        )
        assertEquals("items/0/label", path.toPointer())
    }
}
