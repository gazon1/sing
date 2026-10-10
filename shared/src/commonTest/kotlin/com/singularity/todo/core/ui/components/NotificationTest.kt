package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for the [Notification] sealed interface — the rendering target of [NotificationHost].
 * Verifies each variant carries the correct data.
 */
@Tag("fast")
class NotificationTest {

    @Test
    fun textNotificationCarriesTitleAndOptionalText() {
        val n = Notification.Text(title = "AI Result", text = "Buy milk")
        assertIs<Notification.Text>(n)
        assertEquals("AI Result", n.title)
        assertEquals("Buy milk", n.text)
    }

    @Test
    fun textNotificationTextCanBeEmpty() {
        val n = Notification.Text(title = "Saved", text = "")
        assertEquals("", n.text)
    }

    @Test
    fun errorNotificationCarriesMessage() {
        val n = Notification.Error(message = "boom")
        assertIs<Notification.Error>(n)
        assertEquals("boom", n.message)
    }

    @Test
    fun navigateBackIsASingleton() {
        val a = Notification.NavigateBack
        val b = Notification.NavigateBack
        assertEquals(a, b)
    }

    @Test
    fun dismissIsASingleton() {
        val a = Notification.Dismiss
        val b = Notification.Dismiss
        assertEquals(a, b)
    }
}
