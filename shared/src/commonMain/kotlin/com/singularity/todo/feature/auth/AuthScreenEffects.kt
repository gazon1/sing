package com.singularity.todo.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The one collection of the auth event bus, and the two ways an event can reach
 * the user. Shared by every screen of the auth feature.
 *
 * ## Why the events are presented here and not copied into local flags
 *
 * The screens used to keep `errorMessage` and `message` as `remember` flags and
 * copy each event into one of them, which meant the flag had to be cleared by
 * hand wherever the screen moved on — the mode toggle, the resend, the back
 * button, the submit path — each of them a chance to leave a stale error on the
 * form. The event says what happened once; the screen shows it where it arrives
 * and forgets it. That is the shape `BackupScreen` already has for
 * `BackupUiEvent`.
 *
 * ## Why the notifications travel over a channel of their own
 *
 * The bus is a channel, and a channel has one consumer: a second collector on
 * `events` takes its share of the events away from the first. The screen owns
 * the single collection, so the notifications it maps are handed to the host
 * over a channel of their own and the host is the only consumer of those.
 *
 * @param events the auth event bus. Collected exactly once — a channel has one
 *   consumer, and a second collector would take the events away from the first.
 * @param onNavigateHome what the screen does with the one event that is a
 *   destination rather than a notice.
 * @param extraNotifications notices the screen raises itself, outside the event
 *   bus — the attach screen's refusal when the user has not answered what
 *   happens to their offline data. They join the same host, so there is one
 *   queue of notices and one dialog on the screen.
 */
@Composable
fun AuthScreenEffects(
    events: Flow<AuthUiEvent>,
    onNavigateHome: () -> Unit,
    extraNotifications: Flow<Notification> = emptyFlow(),
) {
    val notificationChannel = remember { Channel<Notification>(Channel.BUFFERED) }
    val notificationFlow = remember(notificationChannel) { notificationChannel.receiveAsFlow() }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                // A destination is the screen's business, not a notification.
                AuthUiEvent.NavigateToHome -> onNavigateHome()

                // Everything else is something to tell the user, and it is told
                // where it arrives rather than copied into a flag.
                else -> event.toNotification()?.let { notificationChannel.send(it) }
            }
        }
    }

    NotificationHost(events = merge(notificationFlow, extraNotifications))
}

/**
 * How an auth event reaches the user.
 *
 * An error is a dialog and a neutral notice is a snackbar: the two are told apart
 * on purpose, so "your account was created" is never read as a failure, and a
 * rejected password is never read as a success.
 *
 * [AuthUiEvent.NavigateToHome] is not a notification — it is a destination, and
 * the screen handles it itself.
 */
internal fun AuthUiEvent.toNotification(): Notification? =
    when (this) {
        is AuthUiEvent.Error -> Notification.Error(message)
        is AuthUiEvent.Message -> Notification.Toast(message)
        AuthUiEvent.NavigateToHome -> null
    }
