package com.singularity.todo.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for the [ContentState] sealed interface — the canonical state wrapper
 * used by [StatefulContent] to route between Loading / Empty / Error / Ready.
 */
class ContentStateTest {

    @Test
    fun loadingIsASingletonContentState() {
        val a = ContentState.Loading
        val b = ContentState.Loading
        assertEquals(a, b)
    }

    @Test
    fun emptyIsASingletonContentState() {
        val a = ContentState.Empty
        val b = ContentState.Empty
        assertEquals(a, b)
    }

    @Test
    fun errorHoldsAMessage() {
        val error = ContentState.Error("boom")
        assertIs<ContentState.Error>(error)
        assertEquals("boom", error.message)
    }

    @Test
    fun readyHoldsATypedValue() {
        val ready: ContentState<String> = ContentState.Ready("hello")
        assertIs<ContentState.Ready<String>>(ready)
        assertEquals("hello", ready.value)
    }

    @Test
    fun errorMessagesAreDistinct() {
        val e1 = ContentState.Error("one")
        val e2 = ContentState.Error("two")
        assertEquals("one", e1.message)
        assertEquals("two", e2.message)
    }

    @Test
    fun readyValuesArePreservedThroughTheType() {
        val ready = ContentState.Ready(listOf(1, 2, 3))
        assertIs<ContentState.Ready<List<Int>>>(ready)
        assertEquals(listOf(1, 2, 3), ready.value)
    }
}
