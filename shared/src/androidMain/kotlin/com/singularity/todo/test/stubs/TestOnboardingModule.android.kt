package com.singularity.todo.test.stubs

import com.singularity.todo.core.ui.onboarding.OnboardingSettingsRepository
import com.singularity.todo.core.ui.onboarding.SpotlightContent
import com.singularity.todo.test.fakes.FakeOnboardingSettingsRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The E2E build starts as a user who has already seen the spotlight tour.
 *
 * The tour is a first-run modal: its scrim fills the screen, swallows every tap and
 * prunes the agenda out of the accessibility tree. A flow that starts on a fresh
 * install therefore cannot see — or tap — the row it just seeded, and it fails with
 * `No visible element found: id: task_item_buy_milk` instead of the assertion it
 * wrote.
 *
 * The product is right; the test environment was in the wrong state. This is the
 * Android counterpart of the desktop harness's onboarding record (ADR
 * `2026-11-09-the-desktop-harness-starts-after-the-spotlight-tour.md`): the same
 * fake, bound with the tour already seen.
 *
 * Loaded next to [testAuthModule] with `allowOverride = true`, so it wins
 * last-definition-wins over the DataStore-backed binding in
 * `com.singularity.todo.core.di.CoreDiModule`.
 */
fun testOnboardingModule(): Module =
    module {
        single<OnboardingSettingsRepository> {
            FakeOnboardingSettingsRepository(initialSeenVersion = SpotlightContent.VERSION)
        }
    }
