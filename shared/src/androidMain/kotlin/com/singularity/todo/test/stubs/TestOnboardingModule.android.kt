package com.singularity.todo.test.stubs

import com.singularity.todo.core.ui.onboarding.OnboardingSettingsRepository
import com.singularity.todo.core.ui.onboarding.SpotlightContent
import com.singularity.todo.test.fakes.FakeOnboardingSettingsRepository
import kotlinx.coroutines.flow.Flow
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
        single<OnboardingSettingsRepository> { E2eOnboardingSettingsRepository() }
    }

/**
 * The E2E build's onboarding record: the tour is seen, and it stays seen.
 *
 * The fake's own `resetSpotlight()` clears the version and asks for a replay, which is
 * what the product's "Show tips again" is supposed to do. In the E2E build that would
 * put the first-run scrim back over the screen every flow is about, and the scrim is
 * what prunes the agenda out of the accessibility tree — the very failure this
 * binding exists to remove. So the reset is inert here: the tour is never on screen
 * in the E2E build, and a flow that wants it has to bring its own record.
 *
 * The release build is untouched: it uses the DataStore-backed repository, where the
 * same action still replays the tour.
 */
private class E2eOnboardingSettingsRepository : OnboardingSettingsRepository {
    private val store = FakeOnboardingSettingsRepository(initialSeenVersion = SpotlightContent.VERSION)

    override val seenSpotlightVersion: Flow<Int> = store.seenSpotlightVersion

    override val spotlightReplayRequests: Flow<Unit> = store.spotlightReplayRequests

    override suspend fun markSpotlightSeen(version: Int) = store.markSpotlightSeen(version)

    override suspend fun resetSpotlight() = Unit
}
