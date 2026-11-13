@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clearTag
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
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
 * Selection and hierarchy semantics of the task editor's Project Picker.
 *
 * The chosen row is painted with the primary colour and a decorative tick. Both
 * are invisible to a screen reader, so every row carries selection as semantics:
 * `selectable(selected = …)` with the radio role, including "No project" and
 * Inbox because picking one destination excludes the rest.
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
            setContent { picker(repo, selectedId = ALPHA.id, onSelect = {}) }

            awaitTag(TestTags.projectPickerRow(ALPHA.id.value)).assertIsSelected()
            awaitTag(TestTags.projectPickerRow(BETA.id.value)).assertIsNotSelected()
        }

    @Test
    fun `No project reports selected only when nothing is chosen`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA, BETA)
            setContent { picker(repo, selectedId = null, onSelect = {}) }

            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsSelected()
            awaitTag(TestTags.projectPickerRow(ALPHA.id.value)).assertIsNotSelected()
        }

    @Test
    fun `a chosen project leaves No project unselected`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA, BETA)
            setContent { picker(repo, selectedId = BETA.id, onSelect = {}) }

            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsNotSelected()
            awaitTag(TestTags.projectPickerRow(BETA.id.value)).assertIsSelected()
        }

    @Test
    fun `choosing an ordinary project leaves Inbox unselected when Inbox exists`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(INBOX, ALPHA)
            setContent { picker(repo, selectedId = ALPHA.id, onSelect = {}) }

            awaitTag(TestTags.PROJECT_PICKER_INBOX).assertIsNotSelected()
            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsNotSelected()
            awaitTag(TestTags.projectPickerRow(ALPHA.id.value)).assertIsSelected()
        }

    @Test
    fun `Inbox is absent when there is no unambiguous default project`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { picker(repo(ALPHA, BETA), selectedId = null, onSelect = {}) }

            awaitTag(TestTags.PROJECT_PICKER_NONE)
            assertTagNotExists(TestTags.PROJECT_PICKER_INBOX)
        }

    @Test
    fun `Inbox is a distinct selected destination when the inbox project is chosen`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(INBOX, ALPHA)
            setContent { picker(repo, selectedId = INBOX.id, onSelect = {}) }

            awaitTag(TestTags.PROJECT_PICKER_INBOX).assertIsSelected()
            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsNotSelected()
            awaitTag(TestTags.projectPickerRow(ALPHA.id.value)).assertIsNotSelected()
        }

    @Test
    fun `Inbox is present only as a system destination and not duplicated as a project row`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(INBOX, ALPHA)
            setContent { picker(repo, selectedId = null, onSelect = {}) }

            awaitTag(TestTags.PROJECT_PICKER_INBOX)
            assertTagNotExists(TestTags.projectPickerRow(INBOX.id.value))
            awaitTag(TestTags.projectPickerRow(ALPHA.id.value))
        }

    @Test
    fun `selecting No project passes null`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA)
            val selections = mutableListOf<ProjectId?>()
            setContent { picker(repo, selectedId = ALPHA.id, onSelect = { selections += it }) }

            clickTagFirst(TestTags.PROJECT_PICKER_NONE)

            assertEquals(listOf<ProjectId?>(null), selections)
        }

    @Test
    fun `selecting Inbox assigns the inbox project id`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(INBOX, ALPHA)
            val selections = mutableListOf<ProjectId?>()
            setContent { picker(repo, selectedId = null, onSelect = { selections += it }) }

            clickTagFirst(TestTags.PROJECT_PICKER_INBOX)

            assertEquals(listOf<ProjectId?>(INBOX.id), selections)
        }

    @Test
    fun `multiple default projects do not create a misleading Inbox destination`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val first = project(id = "p-default-a", name = "Default A", isDefault = true)
            val second = project(id = "p-default-b", name = "Default B", isDefault = true)
            setContent { picker(repo(first, second), selectedId = null, onSelect = {}) }

            assertTagNotExists(TestTags.PROJECT_PICKER_INBOX)
            awaitTag(TestTags.projectPickerRow(first.id.value))
            awaitTag(TestTags.projectPickerRow(second.id.value))
        }

    @Test
    fun `a row tap selects exactly once`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA, BETA)
            val selections = mutableListOf<ProjectId?>()
            setContent { picker(repo, selectedId = null, onSelect = { selections += it }) }

            clickTagFirst(TestTags.projectPickerRow(ALPHA.id.value))

            assertEquals(listOf<ProjectId?>(ALPHA.id), selections)
        }

    @Test
    fun `tapping a nested project selects that project and not its parent`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val parent = project(id = "p-parent", name = "Parent")
            val child = project(id = "p-child", name = "Child", parentId = parent.id)
            val repo = repo(parent, child)
            val selections = mutableListOf<ProjectId?>()
            setContent { picker(repo, selectedId = null, onSelect = { selections += it }) }

            awaitTag(TestTags.projectPickerRow(child.id.value))
            clickTagFirst(TestTags.projectPickerRow(child.id.value))

            assertEquals(listOf<ProjectId?>(child.id), selections)
        }

    @Test
    fun `user projects render in hierarchy order with depth indentation`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val root = project(id = "p-root", name = "Root", sortOrder = 0)
            val child = project(id = "p-child", name = "Child", parentId = root.id, sortOrder = 0)
            val leaf = project(id = "p-leaf", name = "Leaf", parentId = child.id, sortOrder = 0)
            val sibling = project(id = "p-sibling", name = "Sibling", sortOrder = 1)
            val repo = repo(root, leaf, sibling, child)
            setContent { picker(repo, selectedId = null, onSelect = {}) }

            awaitTag(TestTags.projectPickerRow(root.id.value))
            awaitTag(TestTags.projectPickerRow(child.id.value))
            awaitTag(TestTags.projectPickerRow(leaf.id.value))
            awaitTag(TestTags.projectPickerRow(sibling.id.value))

            val rootX = unclippedBoundsOfText("Root").left
            val childX = unclippedBoundsOfText("Child").left
            val leafX = unclippedBoundsOfText("Leaf").left
            val siblingX = unclippedBoundsOfText("Sibling").left

            assertTrue(childX > rootX, "a child row is indented past its parent")
            assertTrue(leafX > childX, "depth increases down the hierarchy")
            assertEquals(rootX, siblingX, "roots share the same indent")

            val rootY = awaitTag(TestTags.projectPickerRow(root.id.value)).fetchSemanticsNode().boundsInRoot.top
            val childY = awaitTag(TestTags.projectPickerRow(child.id.value)).fetchSemanticsNode().boundsInRoot.top
            val leafY = awaitTag(TestTags.projectPickerRow(leaf.id.value)).fetchSemanticsNode().boundsInRoot.top
            val siblingY = awaitTag(TestTags.projectPickerRow(sibling.id.value)).fetchSemanticsNode().boundsInRoot.top
            assertTrue(rootY < childY && childY < leafY && leafY < siblingY, "rows follow parent-child display order")
        }

    @Test
    fun `deleted and closed projects are excluded from normal choices`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val open = project(id = "p-open", name = "Open")
            val deleted = project(id = "p-deleted", name = "Deleted").copy(isDeleted = true, deletedAt = NOW)
            val closed = project(id = "p-closed", name = "Closed").copy(closedAt = NOW)
            setContent { picker(repo(open, deleted, closed), selectedId = null, onSelect = {}) }

            awaitTag(TestTags.projectPickerRow(open.id.value))
            assertTagNotExists(TestTags.projectPickerRow(deleted.id.value))
            assertTagNotExists(TestTags.projectPickerRow(closed.id.value))
        }

    @Test
    fun `a child with a missing parent remains selectable`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val orphan = project(id = "p-orphan", name = "Orphan", parentId = ProjectId("missing"))
            setContent { picker(repo(orphan), selectedId = null, onSelect = {}) }

            awaitTag(TestTags.projectPickerRow(orphan.id.value))
            assertTextDisplayed("Orphan")
        }

    @Test
    fun `the currently selected closed project remains visibly selected`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val closed = project(id = "p-closed", name = "Closed project").copy(closedAt = NOW)
            setContent { picker(repo(closed), selectedId = closed.id, onSelect = {}) }

            awaitTag(TestTags.projectPickerRow(closed.id.value)).assertIsSelected()
        }

    @Test
    fun `ordinary picker rows keep a stable touch target and one-line titles`() =
        runIsolatedComposeTest(width = 260, height = 800) {
            val repo = repo(project(id = "p-long", name = LONG_NAME, icon = "🧑‍💻"))
            setContent { picker(repo, selectedId = null, onSelect = {}) }

            assertTextDisplayed(LONG_NAME)
            assertTextDisplayed("🧑‍💻")

            awaitTag(TestTags.projectPickerRow("p-long")).assertHeightIsAtLeast(48.dp)

            val titleBounds = unclippedBoundsOfText(LONG_NAME)
            assertTrue(
                (titleBounds.bottom - titleBounds.top) < 30.dp,
                "a picker row keeps its title on one line",
            )
        }

    @Test
    fun `deeply nested long names stay on one line with a stable touch target`() =
        runIsolatedComposeTest(width = 260, height = 900) {
            val root = project(id = "p-root", name = LONG_NAME, sortOrder = 0)
            val child = project(id = "p-child", name = LONG_NAME, parentId = root.id, sortOrder = 0)
            val leaf = project(id = "p-leaf", name = LONG_NAME, parentId = child.id, sortOrder = 0)
            setContent { picker(repo(root, child, leaf), selectedId = null, onSelect = {}) }

            awaitTag(TestTags.projectPickerRow(root.id.value)).assertHeightIsAtLeast(48.dp)
            awaitTag(TestTags.projectPickerRow(child.id.value)).assertHeightIsAtLeast(48.dp)
            awaitTag(TestTags.projectPickerRow(leaf.id.value)).assertHeightIsAtLeast(48.dp)

            val rootX =
                unclippedBoundsOfTextInRow(TestTags.projectPickerRow(root.id.value), LONG_NAME).left
            val childX =
                unclippedBoundsOfTextInRow(TestTags.projectPickerRow(child.id.value), LONG_NAME).left
            val leafX =
                unclippedBoundsOfTextInRow(TestTags.projectPickerRow(leaf.id.value), LONG_NAME).left
            assertTrue(rootX < childX && childX < leafX, "deep rows keep increasing indentation")

            val leafTitle =
                unclippedBoundsOfTextInRow(TestTags.projectPickerRow(leaf.id.value), LONG_NAME)
            assertTrue(
                (leafTitle.bottom - leafTitle.top) < 30.dp,
                "a deeply nested picker row keeps its title on one line",
            )
        }

    @Test
    fun `missing and blank icons use the fallback while stored icons render`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val withIcon = project(id = "p-icon", name = "Iconed", icon = "📦")
            val missing = project(id = "p-missing", name = "Missing")
            val blank = project(id = "p-blank", name = "Blank", icon = "   ")
            setContent { picker(repo(withIcon, missing, blank), selectedId = null, onSelect = {}) }

            awaitText("📦")
            awaitTag(TestTags.projectPickerRow(missing.id.value))
            awaitTag(TestTags.projectPickerRow(blank.id.value))
            waitUntil(timeoutMillis = 5_000) {
                countNodes(TestTags.PROJECT_ICON_FALLBACK, useUnmergedTree = true) >= 2
            }
        }

    @Test
    fun `empty project list shows the empty state and still offers system destinations`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { picker(repo(), selectedId = null, onSelect = {}) }

            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsSelected()
            assertTagDisplayed(TestTags.PROJECT_PICKER_EMPTY)
            assertTextDisplayed("No projects yet")
        }

    @Test
    fun `a root project row is selectable and uses its stored icon`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val root = project(id = "p-root", name = "Work", icon = "🧑‍💻")
            val repo = repo(root)
            val selections = mutableListOf<ProjectId?>()
            setContent { picker(repo, selectedId = null, onSelect = { selections += it }) }

            awaitText("🧑‍💻")
            clickTagFirst(TestTags.projectPickerRow(root.id.value))

            assertEquals(listOf<ProjectId?>(root.id), selections)
        }

    @Test
    fun `a large hierarchy is scrollable and items remain selectable after scrolling`() =
        runIsolatedComposeTest(width = 360, height = 500) {
            val projects =
                (1..60).map { index ->
                    project(
                        id = "p-$index",
                        name = "Project $index",
                        sortOrder = index,
                        parentId = if (index > 1 && index % 10 == 0) ProjectId("p-${index - 1}") else null,
                    )
                }
            val selections = mutableListOf<ProjectId?>()
            setContent { picker(repo(*projects.toTypedArray()), selectedId = null, onSelect = { selections += it }) }

            awaitTag(TestTags.projectPickerRow("p-1"))
            onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(59)
            val last = projects.last()
            awaitTag(TestTags.projectPickerRow(last.id.value))
            clickTagFirst(TestTags.projectPickerRow(last.id.value))

            assertEquals(listOf<ProjectId?>(last.id), selections)
        }

    @Test
    fun `search finds nested projects and shows ancestor path context`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val work = project(id = "p-work", name = "Work")
            val android = project(id = "p-android", name = "Android", parentId = work.id)
            val ui = project(id = "p-ui", name = "UI", parentId = android.id)
            val ios = project(id = "p-ios", name = "iOS", parentId = work.id)
            setContent { picker(repo(work, android, ui, ios), selectedId = null, onSelect = {}) }

            awaitTag(TestTags.PROJECT_PICKER_SEARCH)
            typeIntoTag(TestTags.PROJECT_PICKER_SEARCH, "ui")

            awaitTag(TestTags.projectPickerRow(ui.id.value))
            assertTextDisplayed("Work → Android")
            assertTagNotExists(TestTags.projectPickerRow(work.id.value))
            assertTagNotExists(TestTags.projectPickerRow(android.id.value))
            assertTagNotExists(TestTags.projectPickerRow(ios.id.value))
        }

    @Test
    fun `search distinguishes same-name projects under different parents`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val work = project(id = "p-work", name = "Work")
            val home = project(id = "p-home", name = "Home")
            val workUi = project(id = "p-work-ui", name = "UI", parentId = work.id)
            val homeUi = project(id = "p-home-ui", name = "UI", parentId = home.id)
            setContent { picker(repo(work, home, workUi, homeUi), selectedId = null, onSelect = {}) }

            typeIntoTag(TestTags.PROJECT_PICKER_SEARCH, "ui")

            awaitTag(TestTags.projectPickerRow(workUi.id.value))
            awaitTag(TestTags.projectPickerRow(homeUi.id.value))
            assertTextDisplayed("Work")
            assertTextDisplayed("Home")
        }

    @Test
    fun `search shows a no-match state and keeps system destinations selectable`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(ALPHA)
            val selections = mutableListOf<ProjectId?>()
            setContent { picker(repo, selectedId = null, onSelect = { selections += it }) }

            typeIntoTag(TestTags.PROJECT_PICKER_SEARCH, "zzznomatch")

            awaitTag(TestTags.PROJECT_PICKER_NO_MATCHES)
            assertTextDisplayed("No projects match \"zzznomatch\"")
            assertTagNotExists(TestTags.projectPickerRow(ALPHA.id.value))
            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsSelected()

            clickTagFirst(TestTags.PROJECT_PICKER_NONE)
            assertEquals(listOf<ProjectId?>(null), selections)
        }

    @Test
    fun `clearing the search restores the normal hierarchy`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val root = project(id = "p-root", name = "Root", sortOrder = 0)
            val child = project(id = "p-child", name = "Child", parentId = root.id, sortOrder = 0)
            setContent { picker(repo(root, child), selectedId = null, onSelect = {}) }

            typeIntoTag(TestTags.PROJECT_PICKER_SEARCH, "child")
            awaitTag(TestTags.projectPickerRow(child.id.value))
            assertTagNotExists(TestTags.projectPickerRow(root.id.value))
            assertTextDisplayed("Root")

            clearTag(TestTags.PROJECT_PICKER_SEARCH)

            awaitTag(TestTags.projectPickerRow(root.id.value))
            awaitTag(TestTags.projectPickerRow(child.id.value))
            val rootX = unclippedBoundsOfText("Root").left
            val childX = unclippedBoundsOfText("Child").left
            assertTrue(childX > rootX, "clearing search restores indented hierarchy")
            assertTagNotExists(TestTags.PROJECT_PICKER_NO_MATCHES)
        }

    @Test
    fun `search does not change selection and clearing restores the selected hierarchy row`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val root = project(id = "p-root", name = "Root", sortOrder = 0)
            val selected = project(id = "p-selected", name = "Selected", parentId = root.id, sortOrder = 0)
            val other = project(id = "p-other", name = "Other", sortOrder = 1)
            val selections = mutableListOf<ProjectId?>()
            setContent {
                picker(
                    repo(root, selected, other),
                    selectedId = selected.id,
                    onSelect = { selections += it },
                )
            }

            awaitTag(TestTags.projectPickerRow(selected.id.value)).assertIsSelected()

            typeIntoTag(TestTags.PROJECT_PICKER_SEARCH, "other")
            awaitTag(TestTags.projectPickerRow(other.id.value))
            assertTagNotExists(TestTags.projectPickerRow(selected.id.value))
            assertTrue(selections.isEmpty(), "typing a query must not change selectedId")

            clearTag(TestTags.PROJECT_PICKER_SEARCH)

            awaitTag(TestTags.projectPickerRow(root.id.value))
            awaitTag(TestTags.projectPickerRow(selected.id.value)).assertIsSelected()
            assertTrue(selections.isEmpty(), "clearing a query must not change selectedId")
        }

    @Test
    fun `searching for inbox does not duplicate it and keeps system destinations available`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val repo = repo(INBOX, ALPHA)
            setContent { picker(repo, selectedId = INBOX.id, onSelect = {}) }

            typeIntoTag(TestTags.PROJECT_PICKER_SEARCH, "inbox")

            awaitTag(TestTags.PROJECT_PICKER_NO_MATCHES)
            awaitTag(TestTags.PROJECT_PICKER_INBOX).assertIsSelected()
            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsNotSelected()
            assertTagNotExists(TestTags.projectPickerRow(INBOX.id.value))
            assertTagNotExists(TestTags.projectPickerRow(ALPHA.id.value))
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

    private companion object {
        val USER = UserId("test-user")

        val NOW: Instant = Instant.parse("2026-11-12T12:00:00Z")

        fun project(
            id: String,
            name: String,
            icon: String? = null,
            parentId: ProjectId? = null,
            sortOrder: Int = 0,
            isDefault: Boolean = false,
        ) = Project(
            id = ProjectId.fromString(id),
            name = name,
            color = 0xFF3366FF.toInt(),
            icon = icon,
            createdAt = NOW,
            updatedAt = NOW,
            userId = USER,
            parentId = parentId,
            sortOrder = sortOrder,
            isDefault = isDefault,
        )

        val ALPHA = project(id = "p-alpha", name = "Alpha")

        val BETA = project(id = "p-beta", name = "Beta")

        val INBOX = project(id = "p-inbox", name = "Inbox", isDefault = true, icon = "📥")

        const val LONG_NAME =
            "A project name long enough that it would wrap several times in a compact row"
    }
}

private fun DesktopComposeUiTest.unclippedBoundsOfTextInRow(
    tag: String,
    text: String,
): DpRect {
    awaitTag(tag)
    return onAllNodes(
        hasText(text) and hasAnyAncestor(hasTestTag(tag)),
        useUnmergedTree = true,
    ).onFirst().getUnclippedBoundsInRoot()
}
