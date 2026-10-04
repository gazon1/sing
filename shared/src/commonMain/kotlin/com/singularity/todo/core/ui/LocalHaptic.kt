package com.singularity.todo.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.platform.haptics.NoOpHaptic

/**
 * The [Haptic] the current composition should use, defaulting to [NoOpHaptic].
 *
 * ## Why this exists rather than `koinInject`
 *
 * `koinInject` throws in `@Preview`, where no Koin application is started. Every call site
 * therefore had to guard itself:
 *
 * ```kotlin
 * // Guard: koinInject crashes in @Preview (no Koin app started). Skip when in preview mode.
 * val haptic = if (LocalInspectionMode.current) null else koinInject<Haptic>()
 * ```
 *
 * and the resulting `null` propagated into shared component signatures — `Celebration` took
 * `haptic: Haptic?` because a DI framework limitation had leaked into a public API. A
 * CompositionLocal with a meaningful default removes the guard, the nullability, and the
 * preview special case in one move.
 *
 * ## `staticCompositionLocalOf`, not `compositionLocalOf`
 *
 * A haptic implementation does not change within a composition — it is chosen once at the root
 * and stays put. `staticCompositionLocalOf` skips the per-read change tracking, so it is cheaper
 * and it documents the intent: this is a stable platform capability, not a theme.
 *
 * ## What does not belong here
 *
 * Repositories, use cases, and anything else with a Koin lifetime. A CompositionLocal is
 * resolved at composition time and has no lifetime story, so moving a dependency into one turns
 * injection into a service locator — the thing
 * `NoStaticProfileAwareCurrentUserRule` exists to ban. The rule: a **platform capability that
 * never varies down the tree and is needed deep inside shared components** is a Local;
 * everything else stays in DI.
 *
 * ## Distinct from the framework's own haptic local
 *
 * `androidx.compose.ui.platform.LocalHapticFeedback` already exists and is used in
 * `SwipeableTaskRow` and `ReorderableSectionList` for typed feedback such as long-press. The
 * two coexist deliberately: the framework one cannot express this project's cross-platform
 * `Haptic` port, and this one cannot express a feedback *type*. The name is `LocalHaptic`, not
 * `LocalHapticFeedback`, so the two never collide in a file that needs both.
 */
val LocalHaptic = staticCompositionLocalOf<Haptic> { NoOpHaptic }
