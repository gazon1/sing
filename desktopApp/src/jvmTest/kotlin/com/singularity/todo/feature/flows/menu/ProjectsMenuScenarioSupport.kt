package com.singularity.todo.feature.flows.menu

import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectDocument
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.test.fakes.FakeClock
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
}
