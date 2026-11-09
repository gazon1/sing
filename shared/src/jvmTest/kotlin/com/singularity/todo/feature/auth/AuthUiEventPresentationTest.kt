package com.singularity.todo.feature.auth

import com.singularity.todo.core.ui.components.Notification
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * How the auth event bus reaches the user.
 *
 * ## What this pins
 *
 * The login screen presents the events where they arrive and keeps no copy of
 * them, so the mapping from an event to what the user sees is the whole of
 * what the screen does with it. Two things have to stay true:
 *
 * - an **error** is a dialog, so it is on screen until the user closes it — a
 *   rejected password is a fact about what just happened, not a hint that
 *   vanishes while the user is still reading it;
 * - a **neutral notice** is a snackbar, and never an error. Telling someone
 *   their account was created in the shape of a failure is how a user ends up
 *   retyping an address that is already registered.
 */
@Tag("fast")
class AuthUiEventPresentationTest {
    @Test
    fun `an error is presented as an error dialog`() {
        val notification = AuthUiEvent.Error("Invalid login credentials").toNotification()

        val error = assertIs<Notification.Error>(notification)
        assertEquals("Invalid login credentials", error.message)
    }

    @Test
    fun `a neutral notice is presented as a snackbar, never as an error`() {
        val notification = AuthUiEvent.Message("Account created. Check your email.").toNotification()

        val toast = assertIs<Notification.Toast>(notification)
        assertEquals("Account created. Check your email.", toast.message)
    }

    @Test
    fun `navigation is not a notification`() {
        assertNull(AuthUiEvent.NavigateToHome.toNotification())
    }
}
