package com.singularity.todo.feature.flows

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertTagEnabled
import com.singularity.todo.test.helpers.assertTagNotEnabled
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clearTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openAllProjects
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression net for the migration of the app's text fields from Compose's
 * value-based API (`value:` + `onValueChange:`) to the state-based one
 * (`TextFieldState` / `rememberTextFieldState()`).
 *
 * A value-based field reported every edit through a callback, so a screen that
 * forgot to wire it lost the text silently — and the *state-based* field has no
 * callback at all: the text lives in the composable, and reaching the
 * ViewModel is a separate, explicit step. Two directions can therefore break:
 *
 * - **field → app state.** The edit never reaches the ViewModel, so the draft
 *   stays empty and the screen keeps acting as if nothing was typed. The
 *   strongest observable proof is an *effect* driven by the ViewModel state —
 *   a Save button enabling, a list filtering — not the field's own text, which
 *   the field always gets right.
 * - **app state → field.** The ViewModel rewrites the value (the chat screen
 *   clears its input on send) and the field never shows it. This is the
 *   direction a one-way read cannot catch. It is covered one level down, in
 *   `TextFieldStateBridgeUiTest`, because both fields that exercise it in the
 *   app (the chat input and the note title) need a route the desktop harness
 *   cannot reach reliably: the chat screen has no route at all, and the note
 *   editor is three navigations deep. The chat input's own screen-level
 *   coverage lives in `feature/ai/chat/ChatInputFieldTest`, which drives the
 *   screen with a fake model behind it.
 *
 * Each test below drives the real app through the desktop harness and asserts
 * both: the field shows the text, and the app's state moved with it.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TextFieldBridgeFlowTest {

    /**
     * The task title is the clearest case of the field → app state direction:
     * the editor's Save button is derived from `validate(draft)`, so it enables
     * only once the *draft* holds a non-blank title — never from the field's
     * own text.
     */
    @Test
    fun the_task_title_reaches_the_draft_and_clearing_it_disables_save() = runDesktopAppTest(checkA11y = true) {
        clickContentDescription(DesktopShell.FAB_ADD_TASK)

        typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Buy milk")

        // The field shows what was typed ...
        assertTagTextEquals(TestTags.TASK_EDITOR_TITLE_INPUT, "Buy milk")
        // ... and the draft behind it accepted it: Save is enabled only while
        // validate(draft) accepts the title, so an enabled button is proof the
        // text crossed the bridge into the ViewModel.
        awaitEnabledState(TestTags.TASK_EDITOR_SAVE, enabled = true)
        assertTagEnabled(TestTags.TASK_EDITOR_SAVE)

        // Clearing must cross the bridge as well. A title that only ever lived
        // in the field would leave the draft holding "Buy milk" and the editor
        // would happily save a task the user had emptied.
        clearTag(TestTags.TASK_EDITOR_TITLE_INPUT)
        awaitEnabledState(TestTags.TASK_EDITOR_SAVE, enabled = false)
        assertTagNotEnabled(TestTags.TASK_EDITOR_SAVE)
    }

    /**
     * The project name, same shape as the task title: `canSave` is
     * `state.name.isNotBlank() && !state.saving`, so the Save action follows
     * the ViewModel's copy of the name and not the field's.
     */
    @Test
    fun the_project_name_reaches_the_draft_and_clearing_it_disables_save() = runDesktopAppTest(checkA11y = true) {
        openAllProjects()
        clickContentDescription(DesktopShell.FAB_ADD_PROJECT)

        typeIntoTag(TestTags.PROJECT_EDITOR_NAME_INPUT, "Project Alpha")

        assertFieldText(TestTags.PROJECT_EDITOR_NAME_INPUT, "Project Alpha")
        awaitEnabledState(TestTags.PROJECT_EDITOR_SAVE, enabled = true)
        assertTagEnabled(TestTags.PROJECT_EDITOR_SAVE)

        // A blank name must not be savable, so the clear has to reach the draft.
        clearTag(TestTags.PROJECT_EDITOR_NAME_INPUT)
        awaitEnabledState(TestTags.PROJECT_EDITOR_SAVE, enabled = false)
        assertTagNotEnabled(TestTags.PROJECT_EDITOR_SAVE)
    }

    /**
     * The projects search field.
     *
     * The list is filtered by the ViewModel's copy of the query, so a query that
     * matches nothing removes the card from the list — an effect of the app's state,
     * not of the field. The card is the proof: it can only disappear if the query
     * crossed the bridge into the ViewModel.
     *
     * The list does not come back when the query is cleared, and that is *not* this
     * test's business: the projects ViewModel replaces its whole state with `Empty`
     * when the filter matches nothing, and `Empty` carries no query. The screen then
     * reads the query as blank and the list stays empty. The defect is in the
     * projects list, it predates the migration (the value-based field showed the
     * same behaviour — it even lost the typed text, because the field took its value
     * from the state that had just been replaced), and fixing it is a change to the
     * projects ViewModel rather than to the text fields.
     *
     * That the field itself keeps the query it was given is a small improvement the
     * state-based API brings: the field owns its text, so a ViewModel state that
     * loses the query cannot take the text away with it.
     */
    @Test
    fun the_project_search_query_filters_the_list() = runDesktopAppTest(checkA11y = true) {
        openAllProjects()
        clickContentDescription(DesktopShell.FAB_ADD_PROJECT)
        typeIntoTag(TestTags.PROJECT_EDITOR_NAME_INPUT, "Project Alpha")
        // The name has to reach the draft before Save means anything: the button is
        // enabled by the draft, not by the field.
        awaitEnabledState(TestTags.PROJECT_EDITOR_SAVE, enabled = true)
        clickTag(TestTags.PROJECT_EDITOR_SAVE)
        // The list comes back asynchronously after the save, so the card is waited
        // for rather than asserted once.
        awaitTag(TestTags.projectCard("Project Alpha"))

        // A query that matches nothing must reach the ViewModel: the card is
        // filtered out of the list.
        typeIntoTag(TestTags.PROJECTS_SEARCH_INPUT, "zzz")
        assertFieldText(TestTags.PROJECTS_SEARCH_INPUT, "zzz")
        awaitTagGone(TestTags.projectCard("Project Alpha"))
    }

    /**
     * The saved-agenda name. The field arrives with a seeded default, so the
     * test replaces it wholesale — the same way a rename does.
     */
    @Test
    fun the_saved_agenda_name_reaches_the_draft() = runDesktopAppTest(checkA11y = true) {
        // Boot lands on the Today agenda; its top bar carries the bookmark-add
        // control that opens the saved-agenda editor.
        clickTag(TestTags.AGENDA_SAVE_CURRENT_BUTTON)
        awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT)

        typeIntoTag(TestTags.SAVED_AGENDA_NAME_INPUT, "Saved By Test")

        assertFieldText(TestTags.SAVED_AGENDA_NAME_INPUT, "Saved By Test")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Waits until the node with [tag] reports the expected enabled state.
     *
     * A bridged field hands its text to the ViewModel through a snapshot
     * emission, so a button's enabled state flips a frame or two after the
     * keystroke. A one-shot assertion would race the very transition it is
     * about, so the wait comes first and the assertion only confirms it.
     */
    private fun DesktopComposeUiTest.awaitEnabledState(tag: String, enabled: Boolean) {
        waitUntil(
            conditionDescription = "node with testTag '$tag' is ${if (enabled) "enabled" else "disabled"}",
            timeoutMillis = TIMEOUT_MS,
        ) {
            val node = runCatching { awaitTag(tag).fetchSemanticsNode() }.getOrNull()
            // `Disabled` is a marker property: present means disabled.
            val disabled = node?.config?.getOrNull(SemanticsProperties.Disabled) != null
            node != null && disabled != enabled
        }
    }

    /**
     * Waits until the field with [tag] holds [expected].
     *
     * The editable text is read rather than the plain text, because a text
     * field's decoration (its placeholder) is merged into the same node and
     * would otherwise be mistaken for the field's own content.
     */
    private fun DesktopComposeUiTest.awaitTagText(tag: String, expected: String) {
        waitUntil(
            conditionDescription = "node with testTag '$tag' holds the text '$expected'",
            timeoutMillis = TIMEOUT_MS,
        ) {
            val node = runCatching { awaitTag(tag).fetchSemanticsNode() }.getOrNull()
            node?.editableText() == expected
        }
    }

    /**
     * Asserts that the field with [tag] holds [expected].
     *
     * The *editable* text is compared, not the node's text. A state-based field keeps the
     * value in `EditableText` and merges its placeholder into `Text`, so the merged text of
     * a field with a placeholder is "Project name" + the value — and `assertTextEquals`
     * compares that combination, not the value.
     */
    private fun DesktopComposeUiTest.assertFieldText(tag: String, expected: String) {
        val node = awaitTag(tag).fetchSemanticsNode()
        assertEquals(expected, node.editableText(), "the field '$tag' holds")
    }

    /** The text a text field holds, or null when the node is not a text field. */
    private fun SemanticsNode.editableText(): String? =
        config.getOrNull(SemanticsProperties.EditableText)?.text

    private companion object {
        /** Generous upper bound; real transitions settle in well under a second. */
        const val TIMEOUT_MS = 5_000L
    }
}
