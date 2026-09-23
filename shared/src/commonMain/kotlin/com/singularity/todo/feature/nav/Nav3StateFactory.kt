package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable

/**
 * Creates the app-level [Nav3State] used by [Navigator].
 *
 * Platform-specific implementations:
 * - **Android:** uses `SavedStateConfiguration` via [navSavedStateConfig] so each tab's
 *   back-stack survives process death and configuration changes. Callers use
 *   `rememberNavBackStack(savedStateConfig, key)`.
 * - **JVM Desktop:** uses [rememberInMemoryNavBackStack] — a plain `remember { NavBackStack(key) }`.
 *   There is no process death on Desktop, and `LocalSaveableStateRegistry` resolves to `null`
 *   (the `savedstate-compose-desktop` artifact is deliberately empty), making any
 *   `SavedStateConfiguration` dead code that also triggers a polymorphic-serialization
 *   foot-gun. See `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md`.
 *
 * The [Nav3State] is owned by [App] and passed to each platform shell so the
 * shell can render [androidx.navigation3.ui.NavDisplay] independently of who
 * created the state.
 */
@Composable expect fun rememberNav3State(): Nav3State
