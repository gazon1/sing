package com.singularity.todo.core.backup

import androidx.room3.withWriteTransaction
import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.SavedSearchEntity
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.tasks.TasksSettingsRepository
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Reads an archive back.
 *
 * The repository's half of the contract with the importer: it asks for an archive at a
 * path and gets a result. The port exists so the repository can be tested without the
 * importer's twenty-odd database dependencies — a repository that cannot be tested is a
 * repository whose decisions nobody checks.
 */
interface ArchiveImporter {
    /** Restores the archive at [options]'s source path. */
    suspend fun import(options: ImportOptions): Result<RestoreResult>
}

class BackupImporter(
    private val log: Logger,
    private val taskDao: TaskDao,
    private val noteDao: NoteDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val reminderDao: ReminderDao,
    private val projectReminderDao: ProjectReminderDao,
    private val checklistDao: ChecklistDao,
    private val tagGroupDao: TagGroupDao,
    private val projectTagGroupDao: ProjectInheritedTagGroupDao,
    private val savedSearchDao: SavedSearchDao,
    private val timeEntryDao: TimeEntryDao,
    private val profileDao: ProfileDao,
    private val agendaViewDao: AgendaViewDao,
    private val attachmentDao: AttachmentDao,
    private val annotationDao: AttachmentAnnotationDao,
    private val attachmentStorage: AttachmentStorage,
    private val codec: BackupCodec,
    private val clock: Clock,
    private val createFileSource: FileSourceFactory,
    private val tasksSettings: TasksSettingsRepository,
    private val database: com.singularity.todo.core.database.AppDatabase? = null,
) : ArchiveImporter {
    private val json = StableJson

    private suspend fun <T> runTransaction(block: suspend () -> T): T =
        if (database != null) database.withWriteTransaction { block() } else block()

    /**
     * Restores the archive, and the changes that follow it.
     *
     * ## What a restore is, now
     *
     * An archive may be a **chain**: a base and the changes that follow it. The
     * base is imported first and every change is applied after it, in order —
     * through the **same** code path the base goes through, because a change is
     * a set of whole rows, and applying a set of whole rows is what the base is.
     *
     * The result counts the **merged** state, not the base's: a restore that
     * reports the base's numbers says less than the account that was restored.
     */
    override suspend fun import(options: ImportOptions): Result<RestoreResult> =
        runCatchingCancellable {
            val now = clock.now().toEpochMilliseconds()

            // 1. Read zip — use FileSource so content:// URIs from SAF work on Android
            val read = codec.importFromSource(createFileSource(options.sourcePath)).getOrThrow()

            // 2. Validate manifest
            val manifest: BackupManifest =
                json.decodeFromString(
                    BackupManifest.serializer(),
                    read.manifestBytes.decodeToString(),
                )
            BackupDomain.validateManifest(manifest, read.payloadBytes).getOrThrow()

            // 3. Decode payload
            val payload: BackupPayload =
                json.decodeFromString(
                    BackupPayload.serializer(),
                    read.payloadBytes.decodeToString(),
                )

            // 4. Apply migrations if needed
            val migrated = migrate(payload, read.payloadBytes)

            // 5. Fail-closed validation of the whole chain BEFORE any write.
            //
            // The base is already decoded and migrated above. The changes are
            // decoded and migrated here, outside the transaction, so a corrupt
            // or schema-incompatible entry fails the whole import before a single
            // row is touched — the previous data stays intact (no partial apply).
            // shortcut: validation is structural/decode only; per-row constraints
            // are still enforced at apply time, upgrade when per-row pre-checks
            // become a requirement.
            val decodedChanges =
                options.entries.map { entry ->
                    val delta = json.decodeFromString(BackupDelta.serializer(), entry.decodeToString())
                    migrate(delta.payload, json.encodeToString(delta.payload).encodeToByteArray())
                }

            // 6. The base, and the changes that follow it.
            //
            // The base goes in first and the changes go in after it, in order.
            // Both go in through the same path: a change carries whole rows, and
            // whole rows are what the base carries too.
            // What the account already holds, when the caller asked for it to be
            // kept. Read once, before anything is written: "already there" is the
            // state the restore found, not the archive's own earlier entry — the
            // base and the changes behind it stay one story, in their own order.
            val kept = KeptRows()
            val (outcome, merged) =
                runTransaction {
                    var merged = migrated
                    val ownerId = options.ownerId ?: options.ownerUserId
                    // Every task id this restore will write, base and changes
                    // together: a parent link is resolved against all of them, so
                    // a change may name a parent the base wrote.
                    val knownTaskIds =
                        (migrated.tasks + decodedChanges.flatMap { it.tasks })
                            .mapTo(HashSet()) { it.id }
                    val existing =
                        if (options.keepExistingRows) existingRowKeys(options.targetUserId, ownerId) else null
                    val outcome =
                        applyPayload(
                            migrated,
                            options.targetUserId,
                            ownerId,
                            now,
                            read.attachments,
                            knownTaskIds,
                            existing,
                            kept,
                        )
                    for (changePayload in decodedChanges) {
                        applyPayload(
                            changePayload,
                            options.targetUserId,
                            ownerId,
                            now,
                            emptyMap(),
                            knownTaskIds,
                            existing,
                            kept,
                        )
                        merged = merge(merged, changePayload)
                    }
                    outcome to merged
                }

            // A restored archive can contain rows from before the series model: a
            // rule with no series id. The launch repair is gated by a per-profile
            // marker, and the profile it is restoring into may already carry that
            // marker — a backup restored after the upgrade would otherwise leave
            // the legacy rows unvisited forever. The marker is cleared here, so
            // the next launch repairs the restored rows.
            val scope = options.targetUserId.value
            runCatchingCancellable { tasksSettings.setRecurringSeriesRepairVersion(scope, 0) }
                .onFailure { error ->
                    // The rows are already committed. A settings write that failed
                    // must not turn a completed restore into a failed one — the
                    // archive would be deleted by the caller — so the marker reset
                    // is best-effort: a profile that keeps its marker pays one
                    // skipped repair, not a lost restore.
                    log.w(error) { "The recurring-series repair marker was not cleared for $scope" }
                }

            RestoreResult(
                manifest = manifest,
                entityCounts = BackupDomain.countsOf(merged),
                restoredAttachmentCount = outcome.restored,
                missingAttachmentIds = outcome.missing,
                keptEntityCounts = kept.counts(),
            )
        }

    /**
     * The payload as this build understands it.
     *
     * A payload written by an older build is migrated before it is applied; a
     * payload of this build's own schema is left alone.
     */
    private fun migrate(
        payload: BackupPayload,
        payloadBytes: ByteArray,
    ): BackupPayload =
        if (payload.schemaVersion > BackupFormat.SCHEMA_VERSION) {
            throw BackupPayloadError.UnsupportedSchemaVersion(payload.schemaVersion)
        } else if (payload.schemaVersion < BackupFormat.MIN_SUPPORTED_SCHEMA_VERSION) {
            // A version below the first one is not an older build's payload, it
            // is a payload nobody can read — and it says so the way every other
            // unreadable payload says so.
            throw BackupPayloadError.SchemaTooOld(payload.schemaVersion)
        } else if (payload.schemaVersion < BackupFormat.SCHEMA_VERSION) {
            // The same decode the test that joins the chain to the payload type
            // uses — one home, so the reader the test asserts on cannot become a
            // lenient stand-in for the one a restore uses.
            decodeMigratedPayload(payloadBytes, payload.schemaVersion)
        } else {
            payload
        }

    /**
     * The rows of [payload], written into the database.
     *
     * ## The one path
     *
     * The base of a chain and every change in it come through here. That is
     * deliberate: a restore that applies the base one way and the changes
     * another way is a restore with two sets of rules, and the change is the
     * part nobody tested.
     *
     * LAYER EXCEPTION — this writes DAOs directly instead of going through
     * repositories, and that is deliberate rather than an oversight.
     *
     * Every user-scoped repository resolves its target from the *ambient*
     * profile (`ProfileAwareCurrentUser.scopedUserId`) and its writes are
     * constrained to that user: `assertCanWrite` rejects a foreign `userId`,
     * and the `*ForUser` DAO queries filter on it. An import, by contrast,
     * targets an arbitrary `options.targetUserId` — restoring a backup on
     * behalf of another account is the whole point of the feature. Routing
     * this through the repositories would either be rejected by the guard or
     * would require temporarily switching the active profile, which is a
     * user-visible side effect for the duration of the import.
     *
     * It is also why the two unscoped `@Upsert` cross-ref methods in TaskDao
     * are retained: `upsertTagCrossRef` and `upsertDependency` have no
     * user-scoped counterpart because only this path needs them.
     *
     * Tracked as known debt in docs/decisions/2026-09-27-write-layer-soundness.md
     * (ledger #2). The proper resolution is a dedicated bulk-import port that
     * takes an explicit userId, not a pass through the ambient-scoped
     * repositories.
     */
    private suspend fun applyPayload(
        payload: BackupPayload,
        targetUserId: UserId,
        ownerId: UserId?,
        now: Long,
        attachmentBytes: Map<String, ByteArray>,
        knownTaskIds: Set<String>,
        existing: Map<BackupEntityKind, Set<String>>?,
        kept: KeptRows,
    ): AttachmentOutcome {
        // A restore that keeps existing rows narrows the payload before it is
        // applied, rather than branching inside every writer: the one path stays
        // one path, and what it writes is exactly what the account did not have.
        val applied = if (existing == null) payload else withoutExistingRows(payload, existing, kept)

        applyRows(applied, targetUserId, now, knownTaskIds)
        applyLinks(applied)
        applyViews(applied, targetUserId, now)
        applyGroups(applied, targetUserId, now)
        applySavedSearches(applied.savedSearches, targetUserId, now)
        applyReminders(applied, targetUserId, now)
        applyChecklistItems(applied.checklistItems, now)
        applyTimeEntries(applied.timeEntries, targetUserId, now)
        val attachments = applyAttachments(applied.attachments, targetUserId, now, attachmentBytes)

        // The removals go last, and they go last for the same reason the merge
        // puts them after the upserts: everything this payload carries is written
        // before anything is taken out. The link tables have no `user_id` of their
        // own, so their statements reach their row through the parent that owns
        // it — and a parent this payload has just written is alive, while one this
        // payload is about to remove would take the statement's guard with it and
        // leave the link behind.
        //
        // The removals come from `applied`, not from the payload as it arrived: a
        // restore that keeps existing rows has already dropped them, and reading
        // the original here would delete exactly the rows the person asked to keep.
        applyRemovals(applied.removals, targetUserId, ownerId)

        return attachments
    }

    /**
     * The rows the payload says are gone, taken out of the database.
     *
     * ## Why the restore deletes rows
     *
     * Everywhere else a restore is additive: the rows the archive holds are
     * written, and the rows the account already had are left alone. A removal is
     * the one statement that cannot be additive — the row has to go, or the
     * account keeps a task the person deleted on the other device.
     *
     * ## The order comes from the kinds
     *
     * The order the removals go in is a fact about the tables, and it is
     * declared next to the kinds: every kind says which phase it is removed in,
     * and the phases have an order of their own. This method reads that
     * declaration instead of the reader counting lines.
     *
     * See `docs/decisions/2026-10-27-the-removal-order-is-a-declaration.md`.
     */
    private suspend fun applyRemovals(
        removals: Map<BackupEntityKind, List<String>>,
        userId: UserId,
        ownerId: UserId?,
    ) {
        for (phase in BackupRemovalPhase.entries) {
            for (kind in phase.kinds) {
                removals[kind]?.forEach { key -> removeRow(kind, key, userId, ownerId) }
            }
        }
    }

    /**
     * One row of one kind, taken out of the table it belongs to.
     *
     * ## One kind, one statement
     *
     * Every kind is taken out of the table it belongs to, under the profile being
     * restored, by the statement that knows that table. There is no generic
     * "delete a row" here: a removal names a key, and only the table's own
     * statement knows what the key means.
     *
     * ## The keys
     *
     * A removal travels as a key — the key of the row that is not there any more
     * — so the link tables' keys, which are the pair of ids they join, are taken
     * apart again here.
     *
     * ## The `when` is exhaustive
     *
     * A kind this build does not know how to delete does not compile: the branch
     * for it is missing, and the compiler says so before a restore ever runs.
     */
    private suspend fun removeRow(
        kind: BackupEntityKind,
        key: String,
        userId: UserId,
        ownerId: UserId?,
    ) {
        when (kind) {
            BackupEntityKind.TASK_TAGS -> {
                val (taskId, tagId) = key.linkParts()
                taskDao.removeTagRefForUser(taskId, tagId, userId.value)
            }

            BackupEntityKind.TASK_DEPENDENCIES -> {
                val (taskId, dependsOnTaskId) = key.linkParts()
                taskDao.removeDependencyForUser(taskId, dependsOnTaskId, userId.value)
            }

            BackupEntityKind.CHECKLIST_ITEMS -> {
                checklistDao.deleteForUser(key, userId.value)
            }

            BackupEntityKind.PROJECT_TAG_GROUPS -> {
                val (projectId, tagGroupId) = key.linkParts()
                projectTagGroupDao.delete(projectId, tagGroupId, userId.value)
            }

            BackupEntityKind.ATTACHMENT_ANNOTATIONS -> {
                annotationDao.deleteForUser(key, userId.value)
            }

            BackupEntityKind.ATTACHMENTS -> {
                attachmentDao.deleteForUser(key, userId.value)
            }

            BackupEntityKind.AGENDA_VIEWS -> {
                agendaViewDao.delete(userId.value, key)
            }

            BackupEntityKind.SAVED_SEARCHES -> {
                savedSearchDao.delete(userId.value, key)
            }

            BackupEntityKind.TASK_REMINDERS -> {
                reminderDao.delete(key, userId.value)
            }

            BackupEntityKind.PROJECT_REMINDERS -> {
                projectReminderDao.delete(key, userId.value)
            }

            BackupEntityKind.TIME_ENTRIES -> {
                timeEntryDao.delete(key, userId.value)
            }

            BackupEntityKind.TAGS -> {
                tagDao.deleteForUser(key, userId.value)
            }

            BackupEntityKind.TAG_GROUPS -> {
                tagGroupDao.deleteForUser(key, userId.value)
            }

            BackupEntityKind.TASKS -> {
                taskDao.deleteForUser(key, userId.value)
            }

            BackupEntityKind.NOTES -> {
                noteDao.deleteForUser(key, userId.value)
            }

            BackupEntityKind.PROJECTS -> {
                projectDao.deleteForUser(key, userId.value)
            }

            BackupEntityKind.PROFILES -> {
                val owner = ownerId ?: throw IllegalStateException("ownerId required to remove profile")
                profileDao.deleteForUser(key, owner.value)
            }
        }
    }

    // Keep in sync with BackupEntityKind.entries / withoutExistingRows / KeptRows.counts / removeRow.
    private suspend fun existingRowKeys(
        userId: UserId,
        ownerId: UserId?,
    ): Map<BackupEntityKind, Set<String>> =
        mapOf(
            BackupEntityKind.TASKS to taskDao.listAllForUser(userId.value).mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.NOTES to noteDao.listAllForUser(userId.value).mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.PROJECTS to projectDao.listAllForUser(userId.value).mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.TAGS to tagDao.listAllForUser(userId.value).mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.TAG_GROUPS to tagGroupDao.listAllForUser(userId.value).mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.PROJECT_TAG_GROUPS to
                projectTagGroupDao
                    .listAllForUser(userId.value)
                    .mapTo(mutableSetOf()) { it.projectId.link(it.tagGroupId) },
            BackupEntityKind.ATTACHMENTS to attachmentDao.listAllForUser(userId.value).mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.ATTACHMENT_ANNOTATIONS to
                annotationDao
                    .listAllForUser(userId.value)
                    .mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.TASK_TAGS to
                taskDao
                    .listAllTagsForUser(userId.value)
                    .mapTo(mutableSetOf()) { it.taskId.link(it.tagId) },
            BackupEntityKind.TASK_DEPENDENCIES to
                taskDao
                    .listAllDependenciesForUser(userId.value)
                    .mapTo(mutableSetOf()) { it.taskId.link(it.dependsOnTaskId) },
            BackupEntityKind.AGENDA_VIEWS to agendaViewDao.listAllForUser(userId.value).mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.TASK_REMINDERS to
                reminderDao
                    .listAllForUser(userId.value)
                    .mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.PROJECT_REMINDERS to
                projectReminderDao
                    .listAllForUser(userId.value)
                    .mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.CHECKLIST_ITEMS to
                checklistDao
                    .listAllForUser(userId.value)
                    .mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.SAVED_SEARCHES to
                savedSearchDao
                    .listAllForUser(userId.value)
                    .mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.TIME_ENTRIES to timeEntryDao.listAllForUser(userId.value).mapTo(mutableSetOf()) { it.id },
            BackupEntityKind.PROFILES to
                (if (ownerId != null) profileDao.listOwnedBy(ownerId.value) else profileDao.listAll())
                    .mapTo(mutableSetOf()) { it.id },
        )

    /**
     * [payload], less the rows the account already holds.
     *
     * A row whose key is in [existing] is left out and counted as kept; the rest
     * travel. The removals are dropped whole: a restore that keeps what is there
     * cannot take a row out, because the row it would take out is one of the
     * rows it was asked to leave alone.
     *
     * Keep in sync with BackupEntityKind.entries / existingRowKeys / KeptRows.counts.
     */
    private fun withoutExistingRows(
        payload: BackupPayload,
        existing: Map<BackupEntityKind, Set<String>>,
        kept: KeptRows,
    ): BackupPayload =
        payload.copy(
            tasks = keepNewRows(payload.tasks, BackupEntityKind.TASKS, existing, kept) { it.id },
            notes = keepNewRows(payload.notes, BackupEntityKind.NOTES, existing, kept) { it.id },
            projects = keepNewRows(payload.projects, BackupEntityKind.PROJECTS, existing, kept) { it.id },
            tags = keepNewRows(payload.tags, BackupEntityKind.TAGS, existing, kept) { it.id },
            attachments = keepNewRows(payload.attachments, BackupEntityKind.ATTACHMENTS, existing, kept) { it.id },
            attachmentAnnotations =
                keepNewRows(
                    payload.attachmentAnnotations,
                    BackupEntityKind.ATTACHMENT_ANNOTATIONS,
                    existing,
                    kept,
                ) { it.id },
            taskTags =
                keepNewRows(payload.taskTags, BackupEntityKind.TASK_TAGS, existing, kept) {
                    it.taskId.link(it.tagId)
                },
            taskDependencies =
                keepNewRows(payload.taskDependencies, BackupEntityKind.TASK_DEPENDENCIES, existing, kept) {
                    it.taskId.link(it.dependsOnTaskId)
                },
            agendaViews = keepNewRows(payload.agendaViews, BackupEntityKind.AGENDA_VIEWS, existing, kept) { it.id },
            taskReminders =
                keepNewRows(payload.taskReminders, BackupEntityKind.TASK_REMINDERS, existing, kept) { it.id },
            projectReminders =
                keepNewRows(payload.projectReminders, BackupEntityKind.PROJECT_REMINDERS, existing, kept) { it.id },
            checklistItems =
                keepNewRows(payload.checklistItems, BackupEntityKind.CHECKLIST_ITEMS, existing, kept) { it.id },
            tagGroups = keepNewRows(payload.tagGroups, BackupEntityKind.TAG_GROUPS, existing, kept) { it.id },
            projectTagGroups =
                keepNewRows(payload.projectTagGroups, BackupEntityKind.PROJECT_TAG_GROUPS, existing, kept) {
                    it.projectId.link(it.tagGroupId)
                },
            savedSearches =
                keepNewRows(payload.savedSearches, BackupEntityKind.SAVED_SEARCHES, existing, kept) { it.id },
            timeEntries = keepNewRows(payload.timeEntries, BackupEntityKind.TIME_ENTRIES, existing, kept) { it.id },
            profiles = keepNewRows(payload.profiles, BackupEntityKind.PROFILES, existing, kept) { it.id },
            removals = emptyMap(),
        )

    /** The rows of [rows] the account does not have, with each kept row recorded once. */
    private fun <T> keepNewRows(
        rows: List<T>,
        kind: BackupEntityKind,
        existing: Map<BackupEntityKind, Set<String>>,
        kept: KeptRows,
        key: (T) -> String,
    ): List<T> {
        if (rows.isEmpty()) return rows
        val present = existing[kind].orEmpty()
        return rows.filterNot { row ->
            val rowKey = key(row)
            when {
                // The account held it at restore start: leave it alone and count it once.
                rowKey in present -> {
                    kept.add(kind, rowKey)
                    true
                }

                // An earlier payload in this same restore wrote it (e.g. the base): do
                // not re-insert — plain @Insert link rows would collide on their PK.
                kept.wasWritten(kind, rowKey) -> {
                    true
                }

                else -> {
                    kept.markWritten(kind, rowKey)
                    false
                }
            }
        }
    }

    /**
     * The rows a restore left alone, by kind.
     *
     * A set per kind, not a counter: a row that travels in the base and again in
     * a change is matched twice and is still one kept row. The counts are the
     * sizes of the sets.
     */
    private class KeptRows {
        private val keys = mutableMapOf<BackupEntityKind, MutableSet<String>>()
        private val written = mutableMapOf<BackupEntityKind, MutableSet<String>>()

        // Compile canary: if a BackupEntityKind is added, update this exhaustive when,
        // existingRowKeys(), withoutExistingRows(), counts(), and the DAO/export reads.
        @Suppress("unused")
        private fun allKindsHandled(kind: BackupEntityKind): Unit =
            when (kind) {
                BackupEntityKind.TASK_TAGS,
                BackupEntityKind.TASK_DEPENDENCIES,
                BackupEntityKind.CHECKLIST_ITEMS,
                BackupEntityKind.PROJECT_TAG_GROUPS,
                BackupEntityKind.ATTACHMENT_ANNOTATIONS,
                BackupEntityKind.ATTACHMENTS,
                BackupEntityKind.AGENDA_VIEWS,
                BackupEntityKind.SAVED_SEARCHES,
                BackupEntityKind.TASK_REMINDERS,
                BackupEntityKind.PROJECT_REMINDERS,
                BackupEntityKind.TIME_ENTRIES,
                BackupEntityKind.TAGS,
                BackupEntityKind.TAG_GROUPS,
                BackupEntityKind.TASKS,
                BackupEntityKind.NOTES,
                BackupEntityKind.PROJECTS,
                BackupEntityKind.PROFILES,
                -> Unit
            }

        fun add(
            kind: BackupEntityKind,
            key: String,
        ) {
            keys.getOrPut(kind) { mutableSetOf() }.add(key)
        }

        fun markWritten(
            kind: BackupEntityKind,
            key: String,
        ) {
            written.getOrPut(kind) { mutableSetOf() }.add(key)
        }

        fun wasWritten(
            kind: BackupEntityKind,
            key: String,
        ): Boolean = written[kind]?.contains(key) == true

        fun counts(): EntityCounts {
            val c = BackupEntityKind.entries.associateWith { size(it) }
            // Exhaustive: a new BackupEntityKind must be mapped here or this won't compile-cleanly by intent?
            // Build explicitly so every kind is named.
            return EntityCounts(
                tasks = c.value(BackupEntityKind.TASKS),
                notes = c.value(BackupEntityKind.NOTES),
                projects = c.value(BackupEntityKind.PROJECTS),
                tags = c.value(BackupEntityKind.TAGS),
                attachments = c.value(BackupEntityKind.ATTACHMENTS),
                attachmentAnnotations = c.value(BackupEntityKind.ATTACHMENT_ANNOTATIONS),
                taskTags = c.value(BackupEntityKind.TASK_TAGS),
                taskDependencies = c.value(BackupEntityKind.TASK_DEPENDENCIES),
                agendaViews = c.value(BackupEntityKind.AGENDA_VIEWS),
                taskReminders = c.value(BackupEntityKind.TASK_REMINDERS),
                projectReminders = c.value(BackupEntityKind.PROJECT_REMINDERS),
                checklistItems = c.value(BackupEntityKind.CHECKLIST_ITEMS),
                tagGroups = c.value(BackupEntityKind.TAG_GROUPS),
                projectTagGroups = c.value(BackupEntityKind.PROJECT_TAG_GROUPS),
                savedSearches = c.value(BackupEntityKind.SAVED_SEARCHES),
                timeEntries = c.value(BackupEntityKind.TIME_ENTRIES),
                profiles = c.value(BackupEntityKind.PROFILES),
            ).also { assertAllKindsCounted(c) }
        }

        private fun assertAllKindsCounted(c: Map<BackupEntityKind, Int>) {
            for (kind in BackupEntityKind.entries) {
                require(
                    kind in c,
                ) { "BackupEntityKind.$kind missing from kept counts" }
            }
        }

        private fun Map<BackupEntityKind, Int>.value(kind: BackupEntityKind): Int = getValue(kind)

        private fun size(kind: BackupEntityKind): Int = keys[kind]?.size ?: 0
    }

    /**
     * The rows that carry the account itself: tasks, projects, notes and tags.
     *
     * The projects land before the notes because `notes.project_id` is a foreign key:
     * a note that belongs to a project the archive brings with it fails to insert
     * otherwise ("FOREIGN KEY constraint failed"). Tasks go first for the same reason.
     */
    private suspend fun applyRows(
        payload: BackupPayload,
        userId: UserId,
        now: Long,
        knownTaskIds: Set<String>,
    ) {
        for (task in payload.tasks) {
            taskDao.upsert(
                task.withResolvedParent(knownTaskIds).toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = task.createdAt,
                ),
            )
        }
        for (project in payload.projects) {
            projectDao.upsert(
                project.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = project.createdAt,
                ),
            )
        }
        for (note in payload.notes) {
            noteDao.upsert(
                note.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = note.createdAt,
                ),
            )
        }
        // The tags, with names made unique the way the account's index demands.
        // `tags` carries a unique `(user_id, name_normalized)`, and two tags of the
        // same name are legitimate on the way in: a foreign backup can hold both,
        // and one of them may already be in the account under another id. The
        // second row keeps its id and its links and takes a numbered name;
        // dropping it would take the tasks tagged with it along.
        val owners =
            tagDao
                .listAllForUser(userId.value)
                .associateTo(HashMap()) { it.nameNormalized to it.id }
        for (tag in payload.tags) {
            var entity =
                tag.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = tag.createdAt,
                )
            val owner = owners[entity.nameNormalized]
            if (owner != null && owner != entity.id) {
                val base = entity.name.trim()
                var n = 2
                // A re-import is stable: the row may already own the numbered
                // name an earlier restore gave it.
                while (owners["$base ($n)".lowercase()]?.let { it != entity.id } == true) n++
                val name = "$base ($n)"
                entity = entity.copy(name = name, nameNormalized = name.lowercase())
            }
            owners[entity.nameNormalized] = entity.id
            tagDao.upsert(entity)
        }
    }

    /** The link tables: the pairs that join one row to another. */
    private suspend fun applyLinks(payload: BackupPayload) {
        for (taskTag in payload.taskTags) {
            taskDao.upsertTagCrossRef(taskTag.toEntity())
        }
        for (dep in payload.taskDependencies) {
            taskDao.upsertDependency(dep.toEntity())
        }
    }

    /**
     * The saved views and the annotations.
     *
     * The annotations land after the rows they reference, though nothing enforces the order:
     * the table has no foreign key (see AttachmentAnnotationEntity), and adding one
     * would block restoring a note whose file is not in the archive.
     */
    private suspend fun applyViews(
        payload: BackupPayload,
        userId: UserId,
        now: Long,
    ) {
        for (view in payload.agendaViews) {
            agendaViewDao.upsert(
                view.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = view.createdAt,
                ),
            )
        }
        for (annotation in payload.attachmentAnnotations) {
            annotationDao.upsert(
                annotation.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = annotation.createdAt,
                ),
            )
        }
    }

    /**
     * The profiles and the groups.
     *
     * The profiles are restored with their original userId intact (not stamped
     * with the target's). The tag groups land before the tags that reference
     * them, and the project's link to a group lands after the groups.
     */
    private suspend fun applyGroups(
        payload: BackupPayload,
        userId: UserId,
        now: Long,
    ) {
        for (profile in payload.profiles) {
            profileDao.upsert(profile.toEntity())
        }
        for (tagGroup in payload.tagGroups) {
            tagGroupDao.upsert(
                tagGroup.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = tagGroup.createdAt,
                ),
            )
        }
        for (ptg in payload.projectTagGroups) {
            projectTagGroupDao.insert(ptg.toEntity())
        }
    }

    /** Saved searches are standalone, with no cross-entity references. */
    private suspend fun applySavedSearches(
        searches: List<SavedSearchDto>,
        userId: UserId,
        now: Long,
    ) {
        for (search in searches) {
            savedSearchDao.upsert(
                SavedSearchEntity(
                    id = search.id,
                    userId = userId.value,
                    name = search.name,
                    queryString = search.queryString,
                    createdAt = search.createdAt,
                    updatedAt = now,
                ),
            )
        }
    }

    /** Task reminders and project reminders have no cross-entity foreign keys. */
    private suspend fun applyReminders(
        payload: BackupPayload,
        userId: UserId,
        now: Long,
    ) {
        for (reminder in payload.taskReminders) {
            reminderDao.upsert(
                reminder.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = reminder.createdAt,
                ),
            )
        }
        for (reminder in payload.projectReminders) {
            projectReminderDao.upsert(
                reminder.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = reminder.createdAt,
                ),
            )
        }
    }

    /** Checklist items reference the restoring user's tasks, so upsert is safe. */
    private suspend fun applyChecklistItems(
        items: List<ChecklistItemDto>,
        now: Long,
    ) {
        for (item in items) {
            checklistDao.upsert(
                ChecklistItemEntity(
                    id = item.id,
                    taskId = item.taskId,
                    title = item.title,
                    isCompleted = item.isCompleted,
                    sortOrder = item.sortOrder,
                    createdAt = item.createdAt,
                    updatedAt = now,
                    checkedBy = item.checkedBy,
                    checkedAt = item.checkedAt,
                    rowVersion = item.rowVersion,
                ),
            )
        }
    }

    /** Time entries reference the restoring user's tasks. */
    private suspend fun applyTimeEntries(
        entries: List<TimeEntryDto>,
        userId: UserId,
        now: Long,
    ) {
        for (entry in entries) {
            timeEntryDao.upsert(
                entry.toEntity(userId.value).copy(
                    updatedAt = now,
                    createdAt = entry.createdAt,
                ),
            )
        }
    }

    /**
     * The attachment files, written next to their rows.
     *
     * A change carries no bytes — the file is in the snapshot, not in the entry
     * — so a change's payload holds no attachments at all, and this writes
     * nothing for it.
     */
    private suspend fun applyAttachments(
        attachments: List<AttachmentDto>,
        userId: UserId,
        now: Long,
        attachmentBytes: Map<String, ByteArray>,
    ): AttachmentOutcome {
        var restoredCount = 0
        val missingIds = mutableListOf<String>()

        for (att in attachments) {
            val entryName = "${att.id}.dat"
            val bytes = attachmentBytes[entryName]
            if (bytes != null && isIntact(att, bytes)) {
                try {
                    val ext = att.mimeType?.substringAfterLast('/') ?: ""
                    // Both halves matter, and neither subsumes the other.
                    //
                    // The row, not just the bytes: until it was inserted the importer used
                    // the attachment DTO only to find the entry in the zip, so a restore
                    // put every file on disk with nothing pointing at it — and every
                    // annotation restored below at a file the database had no record of.
                    //
                    // `getOrThrow()` because the try/catch below is written against a
                    // throwing contract: `saveBytes` returns a Result, so without the
                    // unwrap a failed write fell through to `restoredCount++` and the catch
                    // was never entered. The restore report then counted an attachment that
                    // was not on disk.
                    //
                    // Order matters: the write has to follow the copy, because `localPath`
                    // comes back from `saveBytes`.
                    val localPath =
                        attachmentStorage
                            .saveBytes(att.taskId, att.id, bytes, ext)
                            .getOrThrow()
                    attachmentDao.upsert(
                        att.toEntity(userId.value).copy(
                            localPath = localPath,
                            updatedAt = now,
                            createdAt = att.createdAt,
                        ),
                    )
                    restoredCount++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Attachment restore failed [id=${att.id}]" }
                    missingIds.add(att.id)
                }
            } else {
                // The row is still inserted when the bytes are absent — or when they
                // are not the bytes the row describes: the attachment exists as a
                // record of what was there, marked by a null `localPath` rather than by
                // its absence, and dropping it would also orphan the annotations written
                // against it.
                attachmentDao.upsert(
                    att.toEntity(userId.value).copy(localPath = null, updatedAt = now),
                )
                missingIds.add(att.id)
            }
        }

        return AttachmentOutcome(restored = restoredCount, missing = missingIds)
    }

    /**
     * Whether the bytes in the archive are the bytes the row describes.
     *
     * The payload carries the size and the checksum of every attachment, and
     * neither was checked: an entry that was truncated on the way in was written
     * to the disk and counted as restored. "Present" is not "intact" — the record
     * that says how big the file is and what it hashes to is in the same row as
     * the entry, and it was sitting there unused.
     *
     * A row that says nothing about the file cannot say it is damaged, so only
     * the emptiness of the entry is a verdict in that case — an entry with no
     * bytes is a truncated one.
     */
    private fun isIntact(
        att: AttachmentDto,
        bytes: ByteArray,
    ): Boolean {
        if (bytes.isEmpty()) return false
        val expectedChecksum = att.checksum
        if (expectedChecksum != null) {
            return BackupDomain.sha256Hex(bytes).equals(expectedChecksum, ignoreCase = true)
        }
        val expectedSize = att.fileSizeBytes
        return expectedSize <= 0L || bytes.size.toLong() == expectedSize
    }

    /** What the attachment files did: how many landed, and which did not. */
    private class AttachmentOutcome(val restored: Int, val missing: List<String>)
}
