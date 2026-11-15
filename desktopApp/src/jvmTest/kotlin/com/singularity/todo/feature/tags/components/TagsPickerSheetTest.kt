package com.singularity.todo.feature.tags.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTextInput
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitAnyDisplayed
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import org.junit.jupiter.api.Tag as JUnitTag

@OptIn(ExperimentalTestApi::class)
@JUnitTag("fast")
class TagsPickerSheetTest {
    @Test
    fun `tag search filters locally and clearing the query restores the list`() =
        runIsolatedComposeTest {
            val repo = FakeTagsRepository().apply { seed(tag("a", "Alpha"), tag("b", "Beta")) }
            setContent { Picker(repo) }

            awaitAnyDisplayed(TestTags.TAGS_PICKER_SEARCH)
            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))
            awaitAnyDisplayed(TagsPickerTestTags.row("Beta"))

            typeIntoTag(TestTags.TAGS_PICKER_SEARCH, "alp")
            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))
            assertTagNotExists(TagsPickerTestTags.row("Beta"))

            typeIntoTag(TestTags.TAGS_PICKER_SEARCH, "ALP")
            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))

            clickTag(TestTags.TAGS_PICKER_CLEAR)
            awaitAnyDisplayed(TagsPickerTestTags.row("Beta"))
        }

    @Test
    fun `a query that matches no tag says so and clearing it brings the rows back`() =
        runIsolatedComposeTest {
            val repo = FakeTagsRepository().apply { seed(tag("a", "Alpha")) }
            setContent { Picker(repo) }

            awaitAnyDisplayed(TestTags.TAGS_PICKER_SEARCH)
            typeIntoTag(TestTags.TAGS_PICKER_SEARCH, "zzz")

            assertTagDisplayed(TestTags.TAGS_PICKER_NO_RESULTS)
            awaitText("No tags match \"zzz\"")

            clickTag(TestTags.TAGS_PICKER_CLEAR)
            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))
        }

    @Test
    fun `done applies the temporary selection and cancel discards it`() =
        runIsolatedComposeTest {
            val repo = FakeTagsRepository().apply { seed(tag("a", "Alpha")) }
            val applied = mutableListOf<List<TagId>>()
            setContent { Picker(repo) { applied += it.toList() } }

            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))
            clickTag(TagsPickerTestTags.row("Alpha"))
            clickTag(TagsPickerTestTags.DONE)
            waitForIdle()

            assertEquals(listOf(TagId("a")), applied.single())
        }

    @Test
    fun `cancel leaves the task untouched`() =
        runIsolatedComposeTest {
            val repo = FakeTagsRepository().apply { seed(tag("a", "Alpha")) }
            val applied = mutableListOf<List<TagId>>()
            setContent { Picker(repo) { applied += it.toList() } }

            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))
            clickTag(TagsPickerTestTags.row("Alpha"))
            clickTag(TagsPickerTestTags.CANCEL)
            waitForIdle()

            assertEquals(emptyList(), applied)
        }

    @Test
    fun `a tag added while the sheet is open appears without reopening it`() =
        runIsolatedComposeTest {
            val repo = FakeTagsRepository().apply { seed(tag("a", "Alpha")) }
            setContent { Picker(repo) }

            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))
            assertTagNotExists(TagsPickerTestTags.row("Gamma"))

            runOnIdle { repo.seed(tag("g", "Gamma")) }

            awaitAnyDisplayed(TagsPickerTestTags.row("Gamma"))
        }

    @Test
    fun `a query that matches nothing still offers the create action`() =
        runIsolatedComposeTest {
            val repo = FakeTagsRepository().apply { seed(tag("a", "Alpha")) }
            setContent { Picker(repo) }

            awaitAnyDisplayed(TestTags.TAGS_PICKER_SEARCH)
            typeIntoTag(TestTags.TAGS_PICKER_SEARCH, "zzz")

            assertTagDisplayed(TestTags.TAGS_PICKER_NO_RESULTS)
            assertTagDisplayed(TagsPickerTestTags.CREATE)
        }

    @Test
    fun `a selected tag hidden by the filter is still applied on done`() =
        runIsolatedComposeTest {
            val repo =
                FakeTagsRepository().apply {
                    seed(tag("a", "Alpha"), tag("b", "Beta"))
                }
            val applied = mutableListOf<List<TagId>>()
            setContent { Picker(repo) { applied += it.toList() } }

            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))
            clickTag(TagsPickerTestTags.row("Alpha"))

            // The filter hides the selected row; the selection is not the view.
            typeIntoTag(TestTags.TAGS_PICKER_SEARCH, "Beta")
            awaitAnyDisplayed(TagsPickerTestTags.row("Beta"))
            assertTagNotExists(TagsPickerTestTags.row("Alpha"))

            clickTag(TagsPickerTestTags.DONE)
            waitForIdle()

            assertEquals(
                listOf(TagId("a")),
                applied.single(),
                "a tag selected before the query narrowed the list must survive the filter",
            )
        }

    @Test
    fun `a created tag is part of the selection handed over on done`() =
        runIsolatedComposeTest {
            val repo = FakeTagsRepository()
            val applied = mutableListOf<List<TagId>>()
            setContent { Picker(repo) { applied += it.toList() } }

            awaitAnyDisplayed(TestTags.TAGS_PICKER_EMPTY)
            clickTag(TagsPickerTestTags.CREATE)
            onAllNodes(hasSetTextAction()).onLast().performTextInput("invoice")
            clickText("Create")

            awaitAnyDisplayed(TagsPickerTestTags.row("invoice"))
            clickTag(TagsPickerTestTags.DONE)
            waitForIdle()

            assertEquals(1, applied.single().size, "the tag created in the sheet is the selection")
        }

    @Test
    fun `a tag the parent removes while the sheet is open is not handed back on done`() =
        runIsolatedComposeTest {
            // The parent owns the selection, and it can move while the sheet is open:
            // `model.tags` is derived from the reactive task state, so sync or a row's
            // own clear can change it. The sheet rebuilds its temporary copy from the
            // new list, which is what keeps Done from re-applying a tag the task no
            // longer has.
            val repo =
                FakeTagsRepository().apply {
                    seed(tag("a", "Alpha"), tag("b", "Beta"))
                }
            val applied = mutableListOf<List<TagId>>()
            var selectedIds by mutableStateOf(persistentListOf(TagId("a")))
            setContent {
                Picker(repo, selectedIds) { applied += it.toList() }
            }

            awaitAnyDisplayed(TagsPickerTestTags.row("Alpha"))
            assertTagDisplayed(TagsPickerTestTags.row("Alpha"))

            // The parent drops "a" while the sheet stays open.
            runOnIdle { selectedIds = persistentListOf() }
            waitForIdle()

            clickTag(TagsPickerTestTags.DONE)
            waitForIdle()

            assertEquals(
                emptyList(),
                applied.single(),
                "the sheet must hand back the parent's current selection, not a stale copy",
            )
        }

    @Composable
    private fun Picker(
        repo: FakeTagsRepository,
        selectedIds: ImmutableList<TagId> = persistentListOf(),
        onSelect: (ImmutableList<TagId>) -> Unit = {},
    ) {
        val currentUser: ProfileAwareCurrentUser = remember { FakeProfileAwareCurrentUser() }
        TagsPickerSheet(
            selectedIds = selectedIds,
            onSelect = onSelect,
            onDismiss = {},
            tagsRepo = repo,
            createTag = remember { CreateTagUseCase(repo, FakeClock(NOW)) },
            currentUser = currentUser,
        )
    }

    private fun tag(
        id: String,
        name: String,
    ) = Tag(
        id = TagId(id),
        name = name,
        color = 0xFF00FF00.toInt(),
        createdAt = NOW,
        updatedAt = NOW,
        userId = TestUsers.DEFAULT,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
