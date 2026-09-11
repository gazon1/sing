package com.singularity.todo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme
import org.koin.compose.koinInject

/**
 * Android actual implementation of [App].
 */
@Composable
actual fun App() {
    val settings: SettingsRepository = koinInject()
    val darkTheme by settings.darkTheme.collectAsState(initial = false)
    val accentName by settings.accentColor.collectAsState(initial = "blue")
    val accent = SingularityAccents.fromString(accentName)

    SingularityTheme(darkTheme = darkTheme, accent = accent) {
        AuthGuard {
            androidShellNav3()
        }
    }
}
