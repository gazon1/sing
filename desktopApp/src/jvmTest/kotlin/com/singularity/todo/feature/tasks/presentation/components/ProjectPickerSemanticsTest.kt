@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfText
import org.junit.jupiter.api.Tag
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Selection semantics of the task editor's Project Picker.
 *
 * The chosen row is painted with the primary colour and a decorative tick. Both
 * are invisible to a screen reader, so the row has to carry the selection as
 * semantics too: `selectable(selected = …)` on every row, including "No
 * project", with the radio role because picking one project excludes the rest.
 *
 * Tagged `slow` + `slow:compose`: a Compose harness mounts a real window and
 * counts as slow by the tag convention.
 */
@Tag("slow")
@Tag("slow:compose")
class ProjectPickerSemanticsTest {
    @Test
    fun `the chosen project reports selected and its siblings do not`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA, BETA)
            setContent {
                picker(repo, selectedId = ALPHA.id, onSelect = {})
            }

            awaitTag(TestTags.projectPickerRow(ALPHA.id.value)).assertIsSelected()
            awaitTag(TestTags.projectPickerRow(BETA.id.value)).assertIsNotSelected()
        }

    @Test
    fun `No project reports selected only when nothing is chosen`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA, BETA)
            setContent {
                picker(repo, selectedId = null, onSelect = {})
            }

            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsSelected()
            awaitTag(TestTags.projectPickerRow(ALPHA.id.value)).assertIsNotSelected()
        }

    @Test
    fun `a chosen project leaves No project unselected`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA, BETA)
            setContent {
                picker(repo, selectedId = BETA.id, onSelect = {})
            }

            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsNotSelected()
        }

    @Test
    fun `a row tap selects exactly once`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA, BETA)
            val selections = mutableListOf<ProjectId?>()
            setContent {
                picker(repo, selectedId = null, onSelect = { selections += it })
            }

            clickTagFirst(TestTags.projectPickerRow(ALPHA.id.value))

            assertEquals(listOf<ProjectId?>(ALPHA.id), selections)
        }

    @Test
    fun `a long project name stays on one line and leaves the glyph visible`() =
        runIsolatedComposeTest(width = 240, height = 800) {
            val long = project(id = "p-long", name = LONG_NAME, icon = "🧑‍💻")
            val repo = repo(long)
            setContent {
                picker(repo, selectedId = null, onSelect = {})
            }

            assertTextDisplayed(LONG_NAME)
            assertTextDisplayed("🧑‍💻")
            assertTrue(
                unclippedBoundsOfText(LONG_NAME).height() < 30.dp,
                "a picker row keeps its title on one line",
            )
        }

    @Composable
    private fun picker(
        repo: ProjectsRepository,
        selectedId: ProjectId?,
        onSelect: (ProjectId?) -> Unit,
    ) {
        // Isolated, not a global Koin: tests share a process, and a global
        // context kept the repository of the previous test alive.
        val koin = remember { koinApplication { modules(module { single { repo } }) } }
        KoinIsolatedContext(koin) {
            ProjectPickerSheet(
                selectedId = selectedId,
                onSelect = onSelect,
                onDismiss = {},
            )
        }
    }

    private fun repo(vararg projects: Project) = FakeProjectsRepository().apply { seed(*projects) }

    private fun DpRect.height(): Dp = bottom - top

    private companion object {
        val USER = UserId("test-user")

        val NOW: Instant = Instant.parse("2026-11-12T12:00:00Z")

        fun project(
            id: String,
            name: String,
            icon: String? = null,
        ) = Project(
            id = ProjectId.fromString(id),
            name = name,
            color = 0xFF3366FF.toInt(),
            icon = icon,
            createdAt = NOW,
            updatedAt = NOW,
            userId = USER,
        )

        val ALPHA = project(id = "p-alpha", name = "Alpha")

        val BETA = project(id = "p-beta", name = "Beta")

        const val LONG_NAME =
            "A project name long enough that it would wrap several times in a compact row"
    }
}
