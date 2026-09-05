package com.singularity.todo.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for the [Notification] sealed interface — the rendering target of [NotificationHost].
 * Verifies each variant carries the correct data.
 */
class NotificationTest {

    @Test
    fun `Text notification carries title and optional text`() {
        val n = Notification.Text(title = "AI Result", text = "Buy milk")
        assertIs<Notification.Text>(n)
        assertEquals("AI Result", n.title)
        assertEquals("Buy milk", n.text)
    }

    @Test
    fun `Text notification text can be null`() {
        val n = Notification.Text(title = "Saved", text = null)
        assertEquals(null, n.text)
    }

    @Test
    fun `Error notification carries message`() {
        val n = Notification.Error(message = "boom")
        assertIs<Notification.Error>(n)
        assertEquals("boom", n.message)
    }

    @Test
    fun `NavigateBack is a singleton`() {
        val a = Notification.NavigateBack
        val b = Notification.NavigateBack
        assertEquals(a, b)
    }

    @Test
    fun `Dismiss is a singleton`() {
        val a = Notification.Dismiss
        val b = Notification.Dismiss
        assertEquals(a, b)
    }
}
