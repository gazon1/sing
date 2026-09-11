package com.singularity.todo

import androidx.compose.runtime.Composable

/**
 * Root Composable — platform-specific actuals dispatch to the right shell.
 *
 * - androidMain: [App] → [androidShellNav3] (bottom nav + FAB + NavDisplay)
 * - jvmMain: [App] → [desktopShellNav3] (drawer + NavDisplay)
 *
 * The expect/actual at the App level (rather than at individual shell functions)
 * lets commonMain call the right shell without needing platform-branching logic.
 */
expect @Composable
fun App()
