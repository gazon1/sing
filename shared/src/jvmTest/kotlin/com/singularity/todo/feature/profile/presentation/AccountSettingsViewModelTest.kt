package com.singularity.todo.feature.profile.presentation

import com.singularity.todo.test.fakes.FakeProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [AccountSettingsViewModel].
 *
 * The VM is a thin pass-through: it exposes the repository's `activeProfile()` Flow.
 * This test verifies the VM correctly surfaces the active profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsViewModelTest {

    @Test
    fun `activeProfile emits profile from repository`() = runTest {
        val fakeProfileRepo = FakeProfileRepository()
        // Create a second profile and switch to it so we have a non-default active profile.
        val newId = fakeProfileRepo.create("Alice", "😀", 0)
        fakeProfileRepo.switchTo(newId)

        val vm = AccountSettingsViewModel(fakeProfileRepo)

        val profile = vm.activeProfile.first()
        assertEquals("Alice", profile?.name)
        assertEquals("😀", profile?.emoji)
    }

    @Test
    fun `activeProfile emits default profile when none is explicitly active`() = runTest {
        val fakeProfileRepo = FakeProfileRepository()
        // Default profile is "Personal" from FakeProfileRepository's initial state.
        val vm = AccountSettingsViewModel(fakeProfileRepo)

        val profile = vm.activeProfile.first()
        assertEquals("Personal", profile?.name)
        assertEquals("🏠", profile?.emoji)
    }
}
