@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tags

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import com.singularity.todo.test.helpers.unclippedBoundsOfText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.singularity.todo.feature.tags.Tag as DomainTag
import org.junit.jupiter.api.Tag as JunitTag

/**
 * The tags screen's undo action stays clear of its own "add tag" button
 * (#2814).
 *
 * `TagsScreen` owns both: a FAB and a snackbar host. The button goes in the
 * `Scaffold`'s FAB slot so Material3 can place the snackbar clear of it. When
 * it was drawn inside the content instead, the undo action landed underneath
 * the button — and so did the row's own delete control, so a tap meant for
 * either opened the add dialog.
 *
 * This is the case the ownership rule did not enumerate: it is not the shell's
 * button (`LocalFabBottomSpace` plays no part), and it is not the saved-view
 * screen's shape either — the host stays a `TaggedSnackbarHost`, because the
 * undo action needs a testTag.
 */
@JunitTag("slow")
class TagsUndoFabClearanceTest {
    @Test
    fun `the undo action clears the add button`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            val repo = FakeTagsRepository().apply { seed(*manyTags()) }
            val vm =
                TagsViewModel(
                    tagRepo = repo,
                    createTag = CreateTagUseCase(repo, FakeClock()),
                    updateTag = UpdateTagUseCase(repo, FakeClock()),
                    currentUser = FakeProfileAwareCurrentUser(),
                )
            setContent { TagsScreen(viewModel = vm) }

            // Delete a tag: the screen shows the undo snackbar. Issued on the
            // ViewModel rather than by tapping the row's trash icon — this test
            // is about where the snackbar lands, and every row carries a
            // "Delete" description, so a tap would be ambiguous.
            vm.onIntent(TagsIntent.Delete(TagId("tg1")))
            awaitText("Undo")

            val action = unclippedBoundsOfText("Undo")
            val button = unclippedBoundsOfTag(TestTags.TAGS_FAB)
            assertTrue(
                action.bottom <= button.top,
                "the undo action ends at ${action.bottom}, the add button starts at ${button.top}",
            )
        }
}

/** A phone's width — the width the button and the snackbar collide at. */
private const val PHONE_WIDTH = 360

private const val PHONE_HEIGHT = 640

/**
 * More tags than fit on the phone, so the first row's delete control is at the
 * top of the screen and not beneath the button — the click under test has to
 * land on the row, or the test measures the wrong thing.
 */
private fun manyTags(): Array<DomainTag> =
    (1..12)
        .map { index ->
            DomainTag(
                id = TagId("tg$index"),
                name = "tag $index",
                color = 0xFFE91E63.toInt(),
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
                userId = TestUsers.DEFAULT,
            )
        }.toTypedArray()
