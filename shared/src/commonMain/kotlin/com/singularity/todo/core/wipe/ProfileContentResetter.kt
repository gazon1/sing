package com.singularity.todo.core.wipe

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.calendarSync.data.CalendarImportEventDao
import com.singularity.todo.feature.calendarSync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendarSync.data.GoogleEventShadowDao
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
    val attachmentStorage: AttachmentStorage,
    /**
     * The transaction the whole reset runs in.
     *
     * The repositories below write through a unit of work of their own, and a nested
     * write joins the outer transaction rather than opening its own — so one
     * transaction here makes the reset atomic across all of them.
     */
    val unitOfWork: UnitOfWork,
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
 *
 * ## Why the whole reset is one transaction
 *
 * The reset touches many tables, and every repository below writes through a unit of
 * work of its own. Without an outer transaction each of them commits on its own, so
 * a failure in the middle of the reset leaves the profile half deleted: the tags are
 * gone, the tasks are not, and nothing records which half ran. A retry then starts
 * from a state nobody described.
 *
 * One transaction around the whole reset makes it all or nothing. The repositories'
 * own writes join it rather than opening a transaction of their own — that is what
 * [UnitOfWork] promises, and `UnitOfWorkIsAtomicTest` asserts it against a real
 * database.
 */
class ProfileContentResetter(
    private val deps: ProfileWipeDeps,
    private val draftStore: DraftStore,
    private val currentUser: ProfileAwareCurrentUser,
) : ContentResetter {
    override suspend fun resetContent(): ContentResetResult {
        val uid = currentUser.scopedUserId.value.value

        // The attachment files are deleted after the transaction commits, not inside
        // it: a file is not a row, so a rollback cannot bring it back. Deleting it
        // first would leave the attachment rows pointing at files that are already
        // gone — a broken attachment in a profile that is still there. A file that
        // outlives its row is the lesser evil: it is invisible, and the profile it
        // belonged to no longer exists.
        val attachmentFiles =
            deps.unitOfWork.write {
                // The rows that hang off a parent first.
                deps.proposalItemDao.deleteAllForUser(uid)
                deps.annotationDao.deleteAllForUser(uid)

                // The rows that stand on their own.
                deps.proposalDao.deleteAllForUser(uid)
                val attachmentFiles =
                    deps.attachmentDao.listAllForUser(uid).mapNotNull { row ->
                        row.localPath?.takeIf { it.isNotBlank() }
                    }
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

                attachmentFiles
            }

        attachmentFiles.forEach { deps.attachmentStorage.deleteFile(it) }

        // The drafts are not in the database, so no database wipe reaches them.
        draftStore.clearPrefix("")

        return ContentResetResult(
            wipedTables =
                ProfileWipePlan.WIPED.keys.toList() +
                    ProfileWipePlan.EMPTIED_BY_CONSEQUENCE.keys.toList(),
        )
    }
}
