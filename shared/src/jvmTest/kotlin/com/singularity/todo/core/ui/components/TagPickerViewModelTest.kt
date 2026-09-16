package com.singularity.todo.core.ui.components

import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.test.fakes.FakeSettingsRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [TagPickerViewModel].
 *
 * @see TagPickerViewModel for the UDF-refactored tag picker that owns
 * tags, selection, and inline-create state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagPickerViewModelTest {

    private fun createVm(
        tagsRepo: FakeTagsRepository = FakeTagsRepository(),
        settingsRepo: FakeSettingsRepository = FakeSettingsRepository("test-user"),
        initialSelectedTagIds: Set<String> = emptySet(),
    ): TagPickerViewModel = TagPickerViewModel(
        tagsRepo = tagsRepo,
        settingsRepo = settingsRepo,
        initialSelectedTagIds = initialSelectedTagIds,
        sharingStarted = { kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(0) },
    )

    private fun tag(id: String, name: String, userId: String = "test-user"): Tag = Tag(
        id = TagId.fromString(id),
        name = name,
        color = 0xFF9E9E9E.toInt(),
        createdAt = Clock.now(),
        updatedAt = Clock.now(),
        userId = userId,
    )

    @Test
    fun `initial state reflects initialSelectedTagIds`() = runTest {
        val vm = createVm(initialSelectedTagIds = setOf("t1", "t2"))
        assertEquals(setOf("t1", "t2"), vm.selected.value)
    }

    @Test
    fun `tags repository is seeded correctly`() = runTest {
        val tagsRepo = FakeTagsRepository()
        tagsRepo.seed(tag("t1", "Work"), tag("t2", "Personal"))
        // Verify the repository has the expected tags directly.
        val tags = tagsRepo.watchTags("test-user").first()
        assertEquals(2, tags.size)
    }

    @Test
    fun `toggleTag adds unselected tag`() = runTest {
        val vm = createVm(initialSelectedTagIds = emptySet())
        advanceUntilIdle()
        vm.toggleTag("t1")
        assertEquals(setOf("t1"), vm.selected.value)
    }

    @Test
    fun `toggleTag removes already selected tag`() = runTest {
        val vm = createVm(initialSelectedTagIds = setOf("t1", "t2"))
        advanceUntilIdle()
        vm.toggleTag("t1")
        assertEquals(setOf("t2"), vm.selected.value)
    }

    @Test
    fun `setCreating true then false resets newTagName`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.setNewTagName("hello world")
        vm.setCreating(true)
        assertEquals("hello world", vm.newTagName.value)
        assertTrue(vm.isCreating.value)

        vm.setCreating(false)
        assertEquals("", vm.newTagName.value)
        assertFalse(vm.isCreating.value)
    }

    @Test
    fun `createTags calls repository`() = runTest {
        val tagsRepo = FakeTagsRepository()
        val settingsRepo = FakeSettingsRepository("test-user")
        val vm = createVm(tagsRepo = tagsRepo, settingsRepo = settingsRepo)
        advanceUntilIdle()

        vm.setNewTagName("Work, Personal")
        vm.createTags()
        advanceUntilIdle()

        // Verify tags were written to the repository directly.
        val allTags = tagsRepo.watchTags("test-user").first()
        assertEquals(2, allTags.size, "tagsRepo should contain 2 tags after createTags")
    }

    @Test
    fun `createTags with blank name does nothing`() = runTest {
        val tagsRepo = FakeTagsRepository()
        val settingsRepo = FakeSettingsRepository("test-user")
        val vm = createVm(tagsRepo = tagsRepo, settingsRepo = settingsRepo)
        advanceUntilIdle()

        vm.setNewTagName("   ,  ")
        vm.createTags()
        advanceUntilIdle()

        assertEquals(emptySet(), vm.selected.value)
    }
}
