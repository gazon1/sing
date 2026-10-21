package com.singularity.todo.core.wipe

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowDao
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.proposals.data.ProposalDao
import com.singularity.todo.feature.proposals.data.ProposalItemDao
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.data.TimeEntryDao

/**
 * The dependencies of a profile reset.
 *
 * A bundle rather than a long parameter list: the reset touches many tables, and
 * a constructor with seventeen parameters is a constructor nobody can read.
 */
data class ProfileWipeDeps(
    val taskRepository: TaskRepository,
    val notesRepository: NotesRepository,
    val projectsRepository: ProjectsRepository,
    val savedAgendaViewsRepository: SavedAgendaViewsRepository,
    val tagsRepository: TagsRepository,
    val tagGroupRepository: TagGroupRepository,
    val reminderDao: ReminderDao,
    val projectReminderDao: ProjectReminderDao,
    val timeEntryDao: TimeEntryDao,
    val savedSearchDao: SavedSearchDao,
    val attachmentDao: AttachmentDao,
    val annotationDao: AttachmentAnnotationDao,
    val proposalDao: ProposalDao,
    val proposalItemDao: ProposalItemDao,
    val calendarImportEventDao: CalendarImportEventDao,
    val calendarSyncTaskMapDao: CalendarSyncTaskMapDao,
    val googleEventShadowDao: GoogleEventShadowDao,
)

/**
 * The Room-backed [ContentResetter].
 *
 * ## What it deletes, and what it leaves alone
 *
 * The tables it reaches are listed in [ProfileWipePlan]; the reasons for the ones
 * it leaves alone are in `docs/architecture/wipe-inventory.md`, and
 * `scripts/ci/check-profile-reset-inventory.py` fails when the three disagree.
 *
 * ## Why some tables go through a repository and others through a DAO
 *
 * A repository's bulk delete carries semantics a DAO delete does not. The task
 * repository's `deleteAllForCurrentProfile` also pushes the tombstone of every
 * task it removes, so the server converges on the same set the device lost — the
 * same goes for notes, projects, agenda views, tags and tag groups. A table whose
 * repository has no such coupling is deleted through its DAO, which grew the
 * same profile-scoped bulk delete.
 *
 * ## Why the order below is what it is
 *
 * The rows that hang off a parent go first. `ai_proposal_item` belongs to a
 * proposal and `attachment_annotations` to an attachment, and both are deleted
 * explicitly rather than left to a cascade — a cascade is invisible in the call
 * list, and the inventory is supposed to describe what the reset does.
 */
class ProfileContentResetter(
    private val deps: ProfileWipeDeps,
    private val draftStore: DraftStore,
    private val currentUser: ProfileAwareCurrentUser,
) : ContentResetter {

    override suspend fun resetContent(): ContentResetResult {
        val uid = currentUser.scopedUserId.value.value

        // The rows that hang off a parent first.
        deps.proposalItemDao.deleteAllForUser(uid)
        deps.annotationDao.deleteAllForUser(uid)

        // The rows that stand on their own.
        deps.proposalDao.deleteAllForUser(uid)
        deps.attachmentDao.deleteAllForUser(uid)
        deps.savedSearchDao.deleteAllForUser(uid)
        deps.timeEntryDao.deleteAllForUser(uid)
        deps.reminderDao.deleteAllForUser(uid)
        deps.projectReminderDao.deleteAllForUser(uid)
        deps.calendarImportEventDao.deleteAllForUser(uid)
        deps.calendarSyncTaskMapDao.clearAll(uid)
        deps.googleEventShadowDao.deleteAllForUser(uid)

        // The rows that are the profile's content proper.
        deps.tagsRepository.deleteAllForCurrentProfile().getOrThrow()
        deps.tagGroupRepository.deleteAllForCurrentProfile().getOrThrow()
        deps.taskRepository.deleteAllForCurrentProfile().getOrThrow()
        deps.notesRepository.deleteAllForCurrentProfile().getOrThrow()
        deps.projectsRepository.deleteAllForCurrentProfile().getOrThrow()
        deps.savedAgendaViewsRepository.deleteAllForCurrentProfile().getOrThrow()

        // The drafts are not in the database, so no database wipe reaches them.
        draftStore.clearPrefix("")

        return ContentResetResult(wipedTables = ProfileWipePlan.WIPED.keys.toList())
    }
}
