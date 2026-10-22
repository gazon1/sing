package com.singularity.todo.feature.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import com.singularity.todo.feature.attachments.viewer.AttachmentViewerRoute
import com.singularity.todo.feature.ai.chat.ChatScreen
import com.singularity.todo.feature.ai.usage.AiUsageScreen
import com.singularity.todo.feature.archive.ArchiveScreen
import com.singularity.todo.feature.auth.presentation.AttachAccountScreen
import com.singularity.todo.feature.auth.presentation.MagicLinkConfirmScreen
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.pomodoro.PomodoroScreen
import com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider
import com.singularity.todo.feature.pomodoro.PomodoroTimer
import com.singularity.todo.feature.profile.ProfileSwitcherScreen
import com.singularity.todo.feature.profile.presentation.ChangePasswordScreen
import com.singularity.todo.feature.proposals.presentation.ReviewScreen
import com.singularity.todo.feature.statistics.StatisticsScreen

/**
 * The app-level entries that belong to no single feature.
 *
 * The pomodoro timer is injected as a parameter rather than resolved inside the
 * entry's content (#414): resolving the Koin graph from entry content runs
 * `Scope.get` deep inside `NavDisplay`'s composition stack, and a factory binding
 * recreated the timer on every visit to the tab.
 *
 * @param nav the outer navigation callbacks.
 * @param pomodoroTimer the pomodoro timer — resolved once per shell composition.
 * @param pomodoroTaskList the provider of the pomodoro task list.
 */
fun EntryProviderScope<AppDestination>.appEntries(
    nav: NavCallbacks,
    pomodoroTimer: PomodoroTimer,
    pomodoroTaskList: PomodoroTaskListProvider,
) {
    entry<AppDestination.Pomodoro> {
        PomodoroScreen(
            timer = pomodoroTimer,
            taskListProvider = pomodoroTaskList,
        )
    }

    entry<AppDestination.Statistics> {
        StatisticsScreen(navCallbacks = nav)
    }

    entry<AppDestination.AiChat> {
        ChatScreen()
    }

    entry<AppDestination.Review> {
        ReviewScreen()
    }

    entry<AppDestination.Archive> {
        ArchiveScreen(onBack = { nav.goBack() })
    }

    entry<AppDestination.AiUsage> {
        AiUsageScreen()
    }

    entry<AppDestination.ProfileSwitcher> {
        ProfileSwitcherScreen(onBack = { nav.goBack() })
    }

    entry<AppDestination.AttachAccount> {
        AttachAccountScreen()
    }

    entry<AppDestination.ChangePassword> {
        ChangePasswordScreen()
    }

    entry<AppDestination.MagicLinkConfirm> { route ->
        MagicLinkConfirmScreen(prefillEmail = route.email, prefillToken = route.token)
    }

    entry<AppDestination.AttachmentViewer> { route ->
        AttachmentViewerRoute(
            attachmentId = route.attachmentId,
            onBack = nav.goBack,
        )
    }
}
