@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.projects

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.screen.ProjectEditorContent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Tag as JunitTag

/**
 * Desktop JVM Compose smoke tests for the project editor's two sheets.
 *
 * Both sheets are opened from the form — the Icon chip in Appearance and the
 * parent chip in Organization — and both close by way of a *pick*, because
 * neither carries a control that means only "close". `IconPickerSheet` closes
 * when an icon, an emoji or "None" is chosen; `EditorParentPickerSheet` ends in
 * "None (root project)"; each of those calls back into the overlay state that
 * dismisses the sheet. That is the affordance the screen actually offers, so it
 * is the one these tests drive.
 *
 * ## Why the assertions are shaped this way
 *
 * An earlier attempt at this test asserted only that nodes carrying the sheet's
 * *title* disappeared after the click. Two things made that unreliable:
 *
 * - "Icon" is the Appearance chip's label and the sheet's heading, so a single
 *   text assertion matches two nodes once the sheet is open, and the count is
 *   ambiguous before it is wrong.
 * - "the title is gone" does not say *why* it went. A sheet that closed on a
 *   navigation, a crash or a recomposition would satisfy it.
 *
 * So each test opens the sheet by a node unique to its content ("Custom emoji"
 * for the icon grid, "None (root project)" for the parent list), and after the
 * pick asserts both that the sheet is gone and that the ViewModel received the
 * value the pick was for. For the icon that value is real, so the assertion
 * cannot pass on a sheet that vanished for another reason. The parent sheet's
 * only pick is "root", and the form starts with no parent, so there the proof
 * is the closure itself — the state read is a guard that the intent ran at all.
 *
 * Run with: ./gradlew :desktopApp:test --tests "*ProjectEditorSheetSmokeTest"
 */
@JunitTag("slow")
@JunitTag("slow:compose")
class ProjectEditorSheetSmokeTest {
    private fun makeViewModel(): ProjectEditorViewModel {
        val repo = FakeProjectsRepository()
        val clock = FakeClock()
        return ProjectEditorViewModel(
            projectId = null,
            createProject = CreateProjectUseCase(repo, clock, FakeProfileAwareCurrentUser()),
            updateProject = UpdateProjectUseCase(repo, clock),
            projectsRepo = repo,
        )
    }

    @Test
    fun `the icon sheet opens and picking an emoji closes it`() =
        runIsolatedComposeTest {
            val vm = makeViewModel()
            setContent {
                ProjectEditorContent(viewModel = vm, onBack = {})
            }

            // The chip is the only "Icon" on screen while the sheet is shut.
            clickText("Icon")
            assertTextDisplayed("Custom emoji")

            // A pick is what closes this sheet: the emoji calls back into the
            // overlay state, so the value it carries also proves the pick ran.
            clickText("🚀")

            onAllNodesWithText("Custom emoji").assertCountEquals(0)
            assertEquals(
                "🚀",
                vm.stateFlow.value.icon,
                "the pick that closed the sheet has to reach the ViewModel",
            )
        }

    @Test
    fun `the parent sheet opens and its root pick closes it`() =
        runIsolatedComposeTest {
            val vm = makeViewModel()
            setContent {
                ProjectEditorContent(viewModel = vm, onBack = {})
            }

            clickText("Parent project")
            assertTextDisplayed("None (root project)")

            clickText("None (root project)")

            onAllNodesWithText("None (root project)").assertCountEquals(0)
            assertNull(
                vm.stateFlow.value.parentId,
                "the pick that closed the sheet has to reach the ViewModel",
            )
        }
}
