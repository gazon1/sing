package com.singularity.todo

import androidx.compose.runtime.Composable

/**
 * expect declaration for the Navigation 3 Android shell.
 * commonMain: expect declaration only
 * androidMain: actual implementation (bottom nav + FAB + NavDisplay)
 */
expect @Composable
fun androidShellNav3()
