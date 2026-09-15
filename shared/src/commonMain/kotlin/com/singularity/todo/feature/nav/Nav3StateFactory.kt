package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable

/**
 * Creates the app-level [Nav3State] used by [Navigator].
 *
 * Platform-specific implementations:
 * - Android: uses [androidx.navigation3.runtime.rememberNavBackStack] without
 *   SavedStateConfiguration (no process-death persistence in the baseline).
 *   **Behavior change (2026-09-16):** Android now uses SavedStateConfiguration { }
 *   so each tab's back-stack survives process death. Previously they did not.
 *   This aligns Android with the JVM behavior and fixes the Koin per-entry
 *   VM scoping bug (ADR 2026-09-14).
 * - JVM Desktop: uses SavedStateConfiguration { } (in-memory only, no serialization).
 *
 * The [Nav3State] is owned by [App] and passed to each platform shell so the
 * shell can render [androidx.navigation3.ui.NavDisplay] independently of who
 * created the state.
 */
expect @Composable
fun rememberNav3State(): Nav3State
