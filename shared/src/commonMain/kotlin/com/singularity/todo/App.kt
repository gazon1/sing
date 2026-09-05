package com.singularity.todo

import androidx.compose.runtime.Composable
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.core.platform.isDesktop
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.nav.rememberAppNavigator
import com.singularity.todo.shell.AndroidShell
import com.singularity.todo.shell.DesktopShell

@Composable
fun App() {
    SingularityTheme {
        AuthGuard {
            val navigator = rememberAppNavigator()
            if (isDesktop) {
                DesktopShell(navigator)
            } else {
                AndroidShell(navigator)
            }
        }
    }
}
