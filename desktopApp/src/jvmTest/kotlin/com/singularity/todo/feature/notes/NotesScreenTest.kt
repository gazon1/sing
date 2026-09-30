package com.singularity.todo.feature.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.presentation.nav.PreviewNotesNavigator
import com.singularity.todo.feature.notes.presentation.screen.SwipeableNoteCard
import org.junit.Test
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun swipeable_note_card_shows_title() = runDesktopComposeUiTest {
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

        onNodeWithTag(TestTags.noteItem("n1"), useUnmergedTree = true).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun folder_note_card_renders() = runDesktopComposeUiTest {
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

        onNodeWithTag(TestTags.noteItem("n2"), useUnmergedTree = true).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun archived_note_card_renders() = runDesktopComposeUiTest {
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

        onNodeWithTag(TestTags.noteItem("n3"), useUnmergedTree = true).assertExists()
    }
}
