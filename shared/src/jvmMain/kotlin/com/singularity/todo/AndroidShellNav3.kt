package com.singularity.todo

import androidx.compose.runtime.Composable

/**
 * JVM stub for the Android shell — never called since App uses desktopShellNav3 on JVM.
 * Required because commonMain has an expect declaration for androidShellNav3.
 */
@Composable
actual fun androidShellNav3() {
    // JVM Desktop uses desktopShellNav3() instead.
    // This stub exists only to satisfy the expect/actual contract.
}
