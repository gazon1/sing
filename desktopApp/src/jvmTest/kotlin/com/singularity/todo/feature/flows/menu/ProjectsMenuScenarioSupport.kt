package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectDocument
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.awaitTag
import kotlin.time.Instant

/**
 * Shared fixtures for the menu scenarios `MENU-01` … `MENU-06`.
 *
 * The traceability rule is one scenario per test file: two display-name
 * annotations carrying two scenario ids in one file are reported as two
 * scenarios claimed by one file. The scenarios therefore live in their own
 * `ProjectsMenu…ScenarioTest` carriers, and everything they both need — the
 * seeded project, the clock — lives here, where no scenario id is claimed. The
 * file names no scenario and carries no display-name annotation, so the
 * traceability scanner does not read it as a claimant.
 *
 * The projects are seeded through the repository rather than through the UI,
 * because a tree is about the arrangement of projects the user already has,
 * and building that arrangement through the editor would be a slower way of
 * saying the same thing.
 */
internal object ProjectsMenuScenarioSupport {
    val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))

    /** Seeds the project [id] named [name], under [parent] when given, closed when [closed]. */
    suspend fun seedProject(
        koin: org.koin.core.Koin,
        id: String,
        name: String,
        parent: String? = null,
        closed: Boolean = false,
    ) {
        val epoch = Instant.fromEpochMilliseconds(0)
        koin.get<ProjectsRepository>().upsert(
            ProjectDocument(
                Project(
                    id = ProjectId.fromString(id),
                    name = name,
                    color = 0xFF2196F3.toInt(),
                    createdAt = epoch,
                    updatedAt = epoch,
                    parentId = parent?.let { ProjectId.fromString(it) },
                    closedAt = if (closed) epoch else null,
                    userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value,
                ),
                emptySet(),
            ),
        )
    }

    /**
     * Seeds [count] projects named after their number.
     *
     * The menu of a profile with a hundred projects is the case the collapsed
     * section exists for, and it cannot be staged by tapping a hundred times.
     *
     * The ids and the names are the ones the Maestro fixture writes, so both
     * carriers of the scenario speak about the same projects.
     */
    suspend fun seedProjects(
        koin: org.koin.core.Koin,
        count: Int,
    ) {
        repeat(count) { index ->
            val number = (index + 1).toString().padStart(3, '0')
            seedProject(koin, "bulk-$number", "Bulk $number")
        }
    }
}

/**
 * Unfolds the menu's Projects section.
 *
 * The section is folded by default — a tree longer than the screen would push
 * the entries the user came for below the fold — so a test that wants the tree
 * has to unfold it, the way a person does.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.expandProjectsSection() {
    awaitTag(TestTags.MENU_PROJECTS_EXPAND).performScrollTo().performClick()
}

/**
 * Folds the menu's Projects section away again.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.foldProjectsSection() {
    awaitTag(TestTags.MENU_PROJECTS_COLLAPSE).performScrollTo().performClick()
}
