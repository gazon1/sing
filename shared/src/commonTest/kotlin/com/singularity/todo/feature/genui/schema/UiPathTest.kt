package com.singularity.todo.feature.genui.schema

import org.junit.Assert.assertEquals
import org.junit.Test

class UiPathTest {

    @Test
    fun `parse returns Root for empty string`() {
        assertEquals(UiPath.Root, UiPath.parse(""))
        assertEquals(UiPath.Root, UiPath.parse("/"))
    }

    @Test
    fun `parse returns Root for whitespace-only`() {
        assertEquals(UiPath.Root, UiPath.parse("   "))
    }

    @Test
    fun `parse handles single segment`() {
        assertEquals(
            UiPath.Prop("items", UiPath.Root),
            UiPath.parse("items"),
        )
    }

    @Test
    fun `parse handles multiple segments`() {
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
    fun `parse treats numeric segments as Child index`() {
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
    fun `of builds from varargs`() {
        val result = UiPath.of("items", "0", "label")
        val expected = UiPath.parse("items/0/label")
        assertEquals(expected, result)
    }

    @Test
    fun `of returns Root for empty list`() {
        assertEquals(UiPath.Root, UiPath.of())
    }

    @Test
    fun `toPointer returns empty for Root`() {
        assertEquals("", UiPath.Root.toPointer())
    }

    @Test
    fun `toPointer returns segment for single Prop`() {
        assertEquals("items", UiPath.Prop("items", UiPath.Root).toPointer())
    }

    @Test
    fun `toPointer returns segments separated by slash`() {
        val path = UiPath.Prop(
            name = "items",
            tail = UiPath.Child(0, UiPath.Prop("label", UiPath.Root)),
        )
        assertEquals("items/0/label", path.toPointer())
    }
}
