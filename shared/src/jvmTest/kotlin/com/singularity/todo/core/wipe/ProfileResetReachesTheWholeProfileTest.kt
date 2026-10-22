package com.singularity.todo.core.wipe

import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationEntity
import com.singularity.todo.core.config.RemoteConfigCacheEntity
import com.singularity.todo.core.database.AgendaViewEntity
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.LlmUsageEntity
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.ProfileEntity
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.ProjectInheritedTagGroupCrossRef
import com.singularity.todo.core.database.ProjectReminderEntity
import com.singularity.todo.core.database.SavedSearchEntity
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TagGroupEntity
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskReminderEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.sync.RemoteConfigEntity
import com.singularity.todo.core.sync.SyncDeadLetterEntity
import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.core.sync.SyncShadowEntity
import com.singularity.todo.core.sync.SyncStateEntity
import com.singularity.todo.feature.agenda.data.SavedAgendaViewsRepositoryImpl
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
import com.singularity.todo.feature.notes.data.NotesRepositoryImpl
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.proposals.data.AiProposalEntity
import com.singularity.todo.feature.proposals.data.ProposalItemEntity
import com.singularity.todo.feature.tags.data.TagGroupRepositoryImpl
import com.singularity.todo.feature.tags.data.TagsRepositoryImpl
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.timetracking.data.TimeEntryEntity
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import androidx.sqlite.SQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The profile reset behind the debug/E2E `reset=content` link, against real SQLite.
 *
 * ## What this pins
 *
 * The reset has to reach *every* table the profile owns. The wipe it grew from
 * reached five things and left twenty-five behind, and a row that survives is a
 * row the next flow sees: a leftover tag shows up in the tag list, a leftover
 * saved search shows up in the search screen, a leftover attachment shows up on
 * the task it belonged to.
 *
 * So this test seeds one row in every table the reset claims to wipe, runs the
 * reset, and says what is left. It also seeds the tables the reset must *not*
 * touch — the profile row, the sync engine's bookkeeping, the remote config
 * cache, the LLM usage log — and says they are still there.
 *
 * ## Why a real database
 *
 * The defect is a *statement* that does not reach the row. A fake DAO would only
 * prove that the fake deletes what the fake deletes.
 *
 * ## The scope
 *
 * The reset is scoped to the active profile: another profile's rows survive it,
 * because the scope is the profile-scoped user id and every row of the profile
 * carries it.
 */
@Tag("slow")
@Tag("slow:data")
class ProfileResetReachesTheWholeProfileTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val ownerId = "owner-1"
    private val profileId = "profile-1"

    private lateinit var tempDir: File
    private lateinit var driver: SQLiteDriver
    private lateinit var dbPath: String
    private lateinit var db: AppDatabase
    private lateinit var currentUser: ProfileAwareCurrentUser

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-reset-").toFile()
        dbPath = File(tempDir, "singularity.db").absolutePath
        driver = createSqlDriver()
        db = AppDatabaseFactory.build(driver, dbPath)
        currentUser = FakeProfileAwareCurrentUser(initialUserId = alice)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    // ─── The claim ──────────────────────────────────────────────────────────────

    @Test
    fun `the reset empties every table it claims to wipe`() = runTest {
        seedProfileContent(alice.value)
        seedTablesThatAreNotProfileContent(alice.value)

        resetter().resetContent()

        wipedTables().forEach { (table, rows) ->
            assertEquals(0, rows(), "the reset must leave no row of $table behind")
        }
        assertNull(
            db.proposalDao().getProposal("proposal-${alice.value}"),
            "the reset must leave no ai_proposal row behind",
        )
        assertNull(
            db.proposalItemDao().getItem("proposal-item-${alice.value}"),
            "the reset must leave no ai_proposal_item row behind",
        )
    }

    @Test
    fun `the reset leaves the tables that are not profile content alone`() = runTest {
        seedProfileContent(alice.value)
        seedTablesThatAreNotProfileContent(alice.value)

        resetter().resetContent()

        tablesThatAreNotProfileContent().forEach { (table, rows) ->
            assertTrue(rows(), "the reset must not touch $table")
        }
    }

    @Test
    fun `the reset leaves another profile's rows alone`() = runTest {
        seedProfileContent(bob.value)

        resetter().resetContent()

        assertEquals(
            listOf("task-${bob.value}"),
            db.taskDao().listAllForUser(bob.value).map { it.id },
            "the reset must not reach another profile's rows",
        )
        assertEquals(
            1,
            db.tagDao().listAllForUser(bob.value).size,
            "the reset must not reach another profile's tags",
        )
    }

    @Test
    fun `the reset takes the trashed rows with it`() = runTest {
        db.tagDao().upsert(
            TagEntity(
                id = "tag-trashed",
                userId = alice.value,
                name = "Trashed",
                color = 0,
                createdAt = 0L,
                updatedAt = 0L,
                deletedAt = 1L,
            ),
        )

        resetter().resetContent()

        assertTrue(
            db.tagDao().listAllForUser(alice.value).isEmpty(),
            "a trashed tag is a row the next flow still sees",
        )
    }

    @Test
    fun `the reset reports the tables it reached`() = runTest {
        val result = resetter().resetContent()

        assertEquals(
            ProfileWipePlan.WIPED.keys.toList() +
                ProfileWipePlan.EMPTIED_BY_CONSEQUENCE.keys.toList(),
            result.wipedTables,
            "the reset must report exactly the tables the plan says it reaches",
        )
    }

    @Test
    fun `the reset empties the index of the rows it deleted`() = runTest {
        seedProfileContent(alice.value)

        indexTables().forEach { (table, content) ->
            assertTrue(
                countRows(table) > 0,
                "the index of $content must hold a row before the reset, or the " +
                    "count after it proves nothing",
            )
        }

        resetter().resetContent()

        indexTables().forEach { (table, content) ->
            assertEquals(
                0,
                countRows(table),
                "the reset must leave no row of $table behind: the index of " +
                    "$content mirrors the rows that are gone",
            )
        }
    }

    @Test
    fun `the reset leaves another profile's index rows alone`() = runTest {
        seedProfileContent(alice.value)
        seedProfileContent(bob.value)

        resetter().resetContent()

        assertEquals(
            1,
            countRows("tasks_fts"),
            "the index holds the rows of both profiles, and the reset may only " +
                "take its own",
        )
    }

    // ─── The tables the reset claims ────────────────────────────────────────────

    /**
     * The tables the reset wipes, and how to count what is left in each.
     *
     * A pair rather than a plain list, because "the table is empty" has to be
     * read through a query that reaches the row — the same reason the wipe is a
     * bulk delete and not a sweep.
     */
    private fun wipedTables(): List<Pair<String, suspend () -> Int>> = listOf(
        "agenda_views" to { db.agendaViewDao().listAllForUser(alice.value).size },
        "attachments" to { db.attachmentDao().listAllForUser(alice.value).size },
        "attachment_annotations" to { db.annotationDao().listAllForUser(alice.value).size },
        "calendar_import_event" to { db.calendarImportEventDao().getAll(alice.value).size },
        "calendar_sync_task_map" to { db.calendarSyncTaskMapDao().getAll(alice.value).size },
        "checklist_items" to { db.checklistDao().listAllForUser(alice.value).size },
        "google_event_shadow" to { db.googleEventShadowDao().getAll(alice.value).size },
        "notes" to { db.noteDao().listAllForUser(alice.value).size },
        "project_reminders" to { db.projectReminderDao().listAllForUser(alice.value).size },
        "projects" to { db.projectDao().listAllForUser(alice.value).size },
        "project_tag_groups" to { db.projectInheritedTagGroupDao().listAllForUser(alice.value).size },
        "saved_searches" to { db.savedSearchDao().listAllForUser(alice.value).size },
        "tag_groups" to { db.tagGroupDao().listAllForUser(alice.value).size },
        "tags" to { db.tagDao().listAllForUser(alice.value).size },
        "task_dependencies" to { db.taskDao().listAllDependenciesForUser(alice.value).size },
        "task_reminders" to { db.reminderDao().listAllForUser(alice.value).size },
        "tasks" to { db.taskDao().listAllForUser(alice.value).size },
        "task_tags" to { db.taskDao().listAllTagsForUser(alice.value).size },
        "time_entries" to { db.timeEntryDao().listAllForUser(alice.value).size },
    )

    /**
     * The tables the reset must leave alone, and how to tell that it did.
     *
     * Each one is a thing a profile reset must not touch, and the reason is in
     * `docs/architecture/wipe-inventory.md`.
     */
    private fun tablesThatAreNotProfileContent(): List<Pair<String, suspend () -> Boolean>> = listOf(
        "profiles" to { db.profileDao().listAll().isNotEmpty() },
        "llm_usage" to { db.llmUsageDao().observeRecent(profileId, 10).first().isNotEmpty() },
        "remote_configs" to { db.remoteConfigDao().getDefault() != null },
        "remote_config_cache" to { db.remoteConfigCacheDao().getDefault() != null },
        "sync_state" to { db.syncStateDao().get(ownerId, profileId) != null },
        "sync_shadow" to { db.syncShadowDao().get(ownerId, profileId, "task", "task-${alice.value}") != null },
        "sync_outbox" to { db.syncOutboxDao().countForOwner(ownerId) > 0 },
        "sync_dead_letter" to { db.syncDeadLetterDao().countForOwner(ownerId) > 0 },
        "calendar_sync_state" to {
            db.calendarSyncStateDao().get(alice.value, "google", "primary") != null
        },
    )

    /**
     * The index tables, and the table each one mirrors.
     *
     * Read from the plan rather than written here, so the test and the reset
     * cannot disagree about which index mirrors what.
     */
    private fun indexTables(): List<Pair<String, String>> =
        ProfileWipePlan.EMPTIED_BY_CONSEQUENCE.entries.map { (index, content) ->
            index to content
        }

    /**
     * The rows of a table, counted by the database itself.
     *
     * An index table has no DAO of its own — nothing writes to it, so there is
     * nothing to write through — and a DAO method with one caller and one
     * purpose would be a method that exists only for this test. The count is a
     * fact about the table, so it is asked of the table.
     */
    private fun countRows(table: String): Int {
        val connection = driver.open(dbPath)
        try {
            val statement = connection.prepare("SELECT count(*) FROM $table")
            try {
                statement.step()
                return statement.getLong(0).toInt()
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }

    // ─── Seeding ────────────────────────────────────────────────────────────────

    private suspend fun seedProfileContent(uid: String) {
        seedTaskGraph(uid)
        seedNotesAndProjects(uid)
        seedTags(uid)
        seedRemindersAndTime(uid)
        seedSearchesAndAttachments(uid)
        seedProposals(uid)
        seedCalendar(uid)
    }

    private suspend fun seedTaskGraph(uid: String) {
        db.taskDao().upsert(
            TaskEntity(
                id = "task-$uid",
                title = "Task",
                description = null,
                priority = TaskPriority.None,
                kind = TaskKind.Task,
                projectId = null,
                dueDate = null,
                dueTime = null,
                startDate = null,
                startTime = null,
                endDate = null,
                endTime = null,
                accentColor = null,
                emoji = null,
                completedAt = null,
                archivedAt = null,
                createdAt = 0L,
                updatedAt = 0L,
                userId = uid,
            ),
        )
        db.taskDao().upsertTagCrossRef(TaskTagCrossRef(taskId = "task-$uid", tagId = "tag-$uid"))
        db.taskDao().upsertDependency(
            TaskDependencyCrossRef(taskId = "task-$uid", dependsOnTaskId = "task-$uid"),
        )
        db.checklistDao().upsert(
            ChecklistItemEntity(
                id = "checklist-$uid",
                taskId = "task-$uid",
                title = "Subtask",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
    }

    private suspend fun seedNotesAndProjects(uid: String) {
        db.noteDao().upsert(
            NoteEntity(
                id = "note-$uid",
                userId = uid,
                title = "Note",
                bodyMarkdown = "body",
                bodyHtml = null,
                parentNoteId = null,
                createdAt = 0L,
                updatedAt = 0L,
                deletedAt = null,
                archivedAt = null,
            ),
        )
        db.projectDao().upsert(
            ProjectEntity(
                id = "project-$uid",
                userId = uid,
                name = "Project",
                color = 0,
                icon = null,
                description = null,
                createdAt = 0L,
                updatedAt = 0L,
                dueDate = null,
                team = null,
                isDeleted = false,
                deletedAt = null,
                parentId = null,
                externalId = null,
            ),
        )
        db.projectInheritedTagGroupDao().insert(
            ProjectInheritedTagGroupCrossRef(projectId = "project-$uid", tagGroupId = "tag-group-$uid"),
        )
        db.agendaViewDao().upsert(
            AgendaViewEntity(
                id = "view-$uid",
                userId = uid,
                name = "View",
                sectionsJson = "[]",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
    }

    private suspend fun seedTags(uid: String) {
        db.tagDao().upsert(
            TagEntity(
                id = "tag-$uid",
                userId = uid,
                name = "Tag",
                color = 0,
                createdAt = 0L,
                updatedAt = 0L,
                deletedAt = null,
            ),
        )
        db.tagGroupDao().upsert(
            TagGroupEntity(
                id = "tag-group-$uid",
                userId = uid,
                name = "Group",
                color = 0,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
    }

    private suspend fun seedRemindersAndTime(uid: String) {
        db.reminderDao().upsert(
            TaskReminderEntity(
                id = "reminder-$uid",
                taskId = "task-$uid",
                userId = uid,
                type = "gentle",
                offsetMinutes = -10,
                fireAt = 0L,
                recurringPattern = null,
                viewId = null,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        db.projectReminderDao().upsert(
            ProjectReminderEntity(
                id = "project-reminder-$uid",
                projectId = "project-$uid",
                userId = uid,
                fireAt = 0L,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        db.timeEntryDao().upsert(
            TimeEntryEntity(
                id = "time-$uid",
                taskId = "task-$uid",
                userId = uid,
                startedAt = 0L,
                endedAt = 1L,
                kind = "Work",
                source = "Manual",
                note = null,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
    }

    private suspend fun seedSearchesAndAttachments(uid: String) {
        db.savedSearchDao().upsert(
            SavedSearchEntity(
                id = "search-$uid",
                userId = uid,
                name = "Search",
                queryString = "q",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        db.attachmentDao().upsert(
            AttachmentEntity(
                id = "attachment-$uid",
                taskId = "task-$uid",
                userId = uid,
                type = "File",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        db.annotationDao().upsert(
            AttachmentAnnotationEntity(
                id = "annotation-$uid",
                attachmentId = "attachment-$uid",
                userId = uid,
                rangeStart = 0,
                rangeEnd = 1,
                quote = "q",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
    }

    private suspend fun seedProposals(uid: String) {
        db.proposalDao().upsertProposal(
            AiProposalEntity(
                id = "proposal-$uid",
                userId = uid,
                source = "ai",
                status = "Pending",
                targetKind = "TASK",
                targetId = "task-$uid",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        db.proposalItemDao().upsertItem(
            ProposalItemEntity(
                id = "proposal-item-$uid",
                proposalId = "proposal-$uid",
                kindJson = "{}",
                targetId = "task-$uid",
                humanSummary = "A change",
                status = "Pending",
                fingerprint = "fp-$uid",
                sortOrder = 0,
            ),
        )
    }

    private suspend fun seedCalendar(uid: String) {
        db.calendarImportEventDao().upsert(
            CalendarImportEventEntity(
                userId = uid,
                eventId = "event-$uid",
                calendarId = "primary",
                lastSyncedAt = 0L,
                importedAt = 0L,
            ),
        )
        db.calendarSyncTaskMapDao().upsert(
            CalendarSyncTaskMapEntity(
                taskId = "task-$uid",
                userId = uid,
                calendarId = "primary",
                eventId = 1L,
                syncedAt = 0L,
            ),
        )
        db.googleEventShadowDao().upsert(
            GoogleEventShadowEntity(
                userId = uid,
                eventId = "event-$uid",
                calendarId = "primary",
                baseJson = "{}",
                lastSyncedAt = 0L,
            ),
        )
    }

    private suspend fun seedTablesThatAreNotProfileContent(uid: String) {
        db.profileDao().upsert(
            ProfileEntity(
                id = profileId,
                name = "Default",
                emoji = "🔹",
                colorIdx = 0,
                isDefault = true,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        db.llmUsageDao().upsert(
            LlmUsageEntity(
                id = "llm-$uid",
                profileId = profileId,
                toolName = "tool",
                modelId = "model",
                inputTokens = 1,
                outputTokens = 1,
                totalTokens = 2,
                costUsdMicros = null,
                durationMs = 1L,
                createdAt = 0L,
                error = null,
            ),
        )
        db.remoteConfigDao().upsert(
            RemoteConfigEntity(
                id = "default",
                supabaseUrl = "https://example.invalid",
                anonKey = "key",
                updatedAt = 0L,
            ),
        )
        db.remoteConfigCacheDao().upsert(
            RemoteConfigCacheEntity(snapshotJson = "{}", fetchedAtEpochMillis = 0L),
        )
        db.syncStateDao().insertIfAbsent(SyncStateEntity(ownerId = ownerId, profileId = profileId))
        db.syncShadowDao().upsert(
            SyncShadowEntity(
                ownerId = ownerId,
                profileId = profileId,
                entityType = "task",
                entityId = "task-$uid",
                confirmedJson = "{}",
            ),
        )
        db.syncOutboxDao().insert(
            SyncOutboxEntity(
                patchId = "patch-$uid",
                ownerId = ownerId,
                entityId = "task-$uid",
                entityType = "task",
                payload = "{}",
                createdAt = 0L,
            ),
        )
        db.syncDeadLetterDao().insert(
            SyncDeadLetterEntity(
                patchId = "dead-$uid",
                ownerId = ownerId,
                entityId = "task-$uid",
                entityType = "task",
                payload = "{}",
                createdAt = 0L,
                failedAt = 0L,
                attempts = 1,
            ),
        )
        db.calendarSyncStateDao().upsert(
            CalendarSyncStateEntity(
                userId = uid,
                provider = "google",
                calendarId = "primary",
            ),
        )
    }

    // ─── The resetter under test ────────────────────────────────────────────────

    private fun resetter() = ProfileContentResetter(
        deps = ProfileWipeDeps(
            taskRepository = taskRepo(),
            notesRepository = notesRepo(),
            projectsRepository = projectsRepo(),
            savedAgendaViewsRepository = SavedAgendaViewsRepositoryImpl(
                agendaViewDao = db.agendaViewDao(),
                currentUser = currentUser,
                clock = Clock.System,
            ),
            tagsRepository = TagsRepositoryImpl(
                tagDao = db.tagDao(),
                clock = Clock.System,
                currentUser = currentUser,
                syncRepository = FakeSyncRepository(),
                unitOfWork = FakeUnitOfWork(),
            ),
            tagGroupRepository = TagGroupRepositoryImpl(
                tagGroupDao = db.tagGroupDao(),
                inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
                projectDao = db.projectDao(),
                tagDao = db.tagDao(),
                clock = Clock.System,
                currentUser = currentUser,
                syncRepository = FakeSyncRepository(),
                unitOfWork = FakeUnitOfWork(),
            ),
            reminderDao = db.reminderDao(),
            projectReminderDao = db.projectReminderDao(),
            timeEntryDao = db.timeEntryDao(),
            savedSearchDao = db.savedSearchDao(),
            attachmentDao = db.attachmentDao(),
            annotationDao = db.annotationDao(),
            proposalDao = db.proposalDao(),
            proposalItemDao = db.proposalItemDao(),
            calendarImportEventDao = db.calendarImportEventDao(),
            calendarSyncTaskMapDao = db.calendarSyncTaskMapDao(),
            googleEventShadowDao = db.googleEventShadowDao(),
        ),
        draftStore = FakeDraftStore(),
        currentUser = currentUser,
    )

    private fun taskRepo() = TaskRepositoryImpl(
        taskDao = db.taskDao(),
        textIndex = db.textIndex(currentUser),
        clock = Clock.System,
        currentUser = currentUser,
        syncRepository = FakeSyncRepository(),
        dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
        unitOfWork = FakeUnitOfWork(),
    )

    private fun notesRepo() = NotesRepositoryImpl(
        noteDao = db.noteDao(),
        textIndex = db.textIndex(currentUser),
        clock = Clock.System,
        currentUser = currentUser,
        syncRepository = FakeSyncRepository(),
        unitOfWork = FakeUnitOfWork(),
    )

    private fun projectsRepo() = ProjectsRepositoryImpl(
        projectDao = db.projectDao(),
        inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
        clock = Clock.System,
        currentUser = currentUser,
        syncRepository = FakeSyncRepository(),
        unitOfWork = FakeUnitOfWork(),
    )
}
