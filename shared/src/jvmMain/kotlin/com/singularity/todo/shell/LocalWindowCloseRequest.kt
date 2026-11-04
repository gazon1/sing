package com.singularity.todo.shell

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How the Desktop shell asks the window to go away.
 *
 * The navigation half of the question — "is there anything left to go back to?" — is
 * answered by the shared `Navigator`, whose `goBack()` returns `false` at the ultimate
 * root. The window half belongs to the desktop entry point, which is the only place that
 * owns one. This local is the join between them, so [DesktopShellNav3Root] can close the
 * window the way the Android shell finishes its activity (see `ShellNavigationIcon`),
 * without the common `App` signature growing a parameter Android has no use for.
 *
 * The default is a no-op on purpose: a shell mounted without a window — a test, a
 * preview, a component harness — must not be the thing that decides the process ends.
 * `desktopApp`'s `main` provides the real request.
 */
val LocalWindowCloseRequest = staticCompositionLocalOf<() -> Unit> { {} }
