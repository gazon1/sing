@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clearAndTypeIntoTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Full-app flow test for creating a saved agenda view.
 *
 * Regression for the "Save spins forever" bug: the screen mapped `SaveSuccess`
 * to a `Notification.Text("Saved", null)` dialog, which `ResultDialog` renders
 * as nothing (`if (text == null) return`), while the ViewModel never reset
 * `isSaving` after the write. The view landed in the database but the user was
 * stranded on the create screen with an eternal spinner.
 *
 * The test drives the production Koin graph (real repository over FakeAppDatabase),
 * so it fails if either half of the loop regresses: the ViewModel must clear
 * `isSaving`/emit `SaveSuccess`, and the screen must react by leaving the editor.
 *
 * Run with: ./gradlew :desktopApp:test
 */
@Tag("slow")
class SavedAgendaCreateFlowTest {

    @Test
    fun `saving a new agenda view leaves the editor and persists the view`() = runDesktopAppTest(checkA11y = true) { koin ->
        waitForIdle()

        // Boot lands on the Today agenda; its top bar carries the bookmark-add control.
        clickTag(TestTags.AGENDA_SAVE_CURRENT_BUTTON)
        awaitTag(TestTags.SAVED_AGENDA_SAVE_BUTTON)

        // The seeded draft is pristine, so Save is disabled — rename to dirty it.
        clearAndTypeIntoTag(TestTags.SAVED_AGENDA_NAME_INPUT, "Saved By Test")
        clickTag(TestTags.SAVED_AGENDA_SAVE_BUTTON)

        // SaveSuccess navigates back to the agenda. This is the assertion that fails
        // on the pre-fix code: the editor stayed on screen with the spinner running.
        // awaitTagGone polls, so the async write → event → navigation chain has time
        // to settle before the check passes.
        awaitTagGone(TestTags.SAVED_AGENDA_SAVE_BUTTON)

        // Navigation only fires after the repository write resolved, so a single
        // read is enough — no polling required.
        val savedNames = koin.get<SavedAgendaViewsRepository>().observeAll().first().map { it.name }
        assert("Saved By Test" in savedNames) {
            "the created view must be persisted for the current user, got: $savedNames"
        }
    }
}
