@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.notes

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.presentation.nav.PreviewNotesNavigator
import com.singularity.todo.feature.notes.presentation.screen.SwipeableNoteCard
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop JVM Compose UI tests for [SwipeableNoteCard].
 *
 * [SwipeableNoteCard] is a public Composable that renders a single note card
 * with swipe-to-dismiss. We test it directly without Koin wiring — construct
 * sample data inline and use [PreviewNotesNavigator] for navigation.
 *
 * Run with: ./gradlew :desktopApp:test
 */
@Tag("slow")
@Tag("slow:compose")
class NotesScreenTest {

    private val testUserId = UserId("test-user")
    private val epoch = Instant.fromEpochMilliseconds(0)

    /** Replicates [com.singularity.todo.core.ui.preview.PreviewSamples.note] but public. */
    private fun sampleNote(
        id: String = "n1",
        title: String = "Ideas",
        body: String = "Hello **markdown**",
    ) = Note(
        id = NoteId(id),
        userId = testUserId,
        title = title,
        bodyMarkdown = body,
        bodyHtml = "<p>Hello <strong>markdown</strong></p>",
        createdAt = epoch,
        updatedAt = epoch,
    )

    /** Replicates [com.singularity.todo.core.ui.preview.PreviewSamples.folderNote] but public. */
    private fun sampleFolderNote(
        id: String = "n2",
        title: String = "Work",
    ) = Note(
        id = NoteId(id),
        userId = testUserId,
        title = title,
        bodyMarkdown = null,
        bodyHtml = null,
        isFolder = true,
        parentNoteId = null,
        createdAt = epoch,
        updatedAt = epoch,
    )

    /** Replicates [com.singularity.todo.core.ui.preview.PreviewSamples.archivedNote] but public. */
    private fun sampleArchivedNote(
        id: String = "n3",
        title: String = "Old Note",
        body: String = "This note was archived.",
    ) = Note(
        id = NoteId(id),
        userId = testUserId,
        title = title,
        bodyMarkdown = body,
        bodyHtml = "<p>This note was archived.</p>",
        createdAt = epoch,
        updatedAt = epoch,
        archivedAt = epoch,
    )

    @Test
    fun swipeable_note_card_shows_title() = runIsolatedComposeTest {
        val note = sampleNote("n1", "Meeting Notes", "Discuss **Q4 goals** with the team")

        setContent {
            SwipeableNoteCard(
                note = note,
                isSelected = false,
                isSelectionMode = false,
                navigator = PreviewNotesNavigator(),
                onLongClick = {},
                onDelete = {},
                onTogglePin = {},
                onToggleSelection = {},
            )
        }

        assertTagExists(TestTags.noteItem("n1"))
    }

    @Test
    fun folder_note_card_renders() = runIsolatedComposeTest {
        val folderNote = sampleFolderNote("n2", "Work Folder")

        setContent {
            SwipeableNoteCard(
                note = folderNote,
                isSelected = false,
                isSelectionMode = false,
                navigator = PreviewNotesNavigator(),
                onLongClick = {},
                onDelete = {},
                onTogglePin = {},
                onToggleSelection = {},
            )
        }

        assertTagExists(TestTags.noteItem("n2"))
    }

    @Test
    fun archived_note_card_renders() = runIsolatedComposeTest {
        val archivedNote = sampleArchivedNote("n3", "Old Note")

        setContent {
            SwipeableNoteCard(
                note = archivedNote,
                isSelected = false,
                isSelectionMode = false,
                navigator = PreviewNotesNavigator(),
                onLongClick = {},
                onDelete = {},
                onTogglePin = {},
                onToggleSelection = {},
            )
        }

        assertTagExists(TestTags.noteItem("n3"))
    }

    /**
     * The card is addressable by *both* of its identifiers in the merged tree.
     *
     * A Material3 Card is a Surface, and a Surface merges its descendants: a tag
     * below the card exists in the unmerged tree only, where no end-to-end test can
     * address it — the Maestro flows drive the app through the accessibility tree,
     * which holds the merged nodes. The title tag therefore lives on the item's
     * outer node, and the id tag on the card, which is the merged node itself.
     *
     * The title tag is the one an end-to-end test needs: it types the title, and it
     * never learns the generated ULID.
     */
    @Test
    fun note_card_is_addressable_by_id_and_by_title() = runIsolatedComposeTest {
        val note = sampleNote("n1", "Meeting Notes", "Discuss **Q4 goals** with the team")

        setContent {
            SwipeableNoteCard(
                note = note,
                isSelected = false,
                isSelectionMode = false,
                navigator = PreviewNotesNavigator(),
                onLongClick = {},
                onDelete = {},
                onTogglePin = {},
                onToggleSelection = {},
            )
        }

        assertTagExists(TestTags.noteItem("n1"))
        assertTagExists(TestTags.noteItemByTitle("Meeting Notes"))
    }
}
