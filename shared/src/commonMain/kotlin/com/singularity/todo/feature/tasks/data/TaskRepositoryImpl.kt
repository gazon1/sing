package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toIsoOrNull
import com.singularity.todo.core.database.toLocalTimeIsoOrNull
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.search.FreeTextRule
import com.singularity.todo.core.search.SearchTextIndex
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.model.TagEditActor
import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesInvariants
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.blockingDependencyIdsForUser
import com.singularity.todo.feature.tasks.withExtras
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.database.UnitOfWork

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val textIndex: SearchTextIndex,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
    private val unitOfWork: UnitOfWork,
    private val dependencyValidator: DependencyValidator,
) : TaskRepository {

    // ── Helpers ────────────────────────────────────────────────────────────────

    // ── GenericUserScopedRepository ───────────────────────────────────────────

    override suspend fun currentUserId(): com.singularity.todo.core.ids.UserId = currentUser.scopedUserId.value

    override fun observeAll(): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        taskDao.withExtras(uid, taskDao.watchActive(uid.value))
    }

    override fun observe(id: TaskId): Flow<Task?> = currentUser.observeForCurrentUser { uid ->
        combine(
            taskDao.watchByIdForUser(id.value, uid.value),
            taskDao.blockingDependencyIdsForUser(id.value, uid.value),
            taskDao.getTagIdsForUser(id.value, uid.value),
        ) { entity, depIds, tagIds ->
            if (entity?.userId == uid.value) {
                entity.toTask(
                    blockedBy = depIds.map { TaskId.fromString(it) }.toSet(),
                    tags = tagIds.map { TagId.fromString(it) },
                )
            } else {
                null
            }
        }
    }

    override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> {
        // A filter that looks for a word asks the index, like the search does —
        // through the one port, so that the two answers cannot drift apart. The
        // index is scoped to the current user itself, so this path does not go
        // through the scoping below.
        if (filter is TaskFilter.Search) {
            // The index answers with the record and the place the word was
            // found; a filter asks for the record, and the position is the
            // search screen's business.
            return textIndex.observeTasks(FreeTextRule.singleTerm(filter.query).indexQuery())
                .map { rows -> rows.map { it.record } }
        }
        return currentUser.observeForCurrentUser { uid ->
            val today = LocalDate.fromEpochDays(
                clock.now().toEpochMilliseconds() / TimeConstants.MILLIS_PER_DAY,
            ).toString()

            val entityFlow: Flow<List<TaskEntity>> = when (filter) {
                is TaskFilter.Today -> taskDao.watchByDate(uid.value, today)

                is TaskFilter.Upcoming -> taskDao.watchUpcoming(uid.value, today, today)

                is TaskFilter.Someday -> taskDao.watchSomeday(uid.value)

                is TaskFilter.Inbox -> taskDao.watchActive(uid.value)

                is TaskFilter.Trash -> taskDao.watchTrash(uid.value)

                is TaskFilter.All -> taskDao.watchActive(uid.value)

                is TaskFilter.ByProject -> taskDao.watchByProject(uid.value, filter.id.value)

                is TaskFilter.Pinned -> taskDao.watchPinned(uid.value)

                is TaskFilter.ByTag -> taskDao.watchByTag(uid.value, filter.id.value)

                is TaskFilter.ByDateRange -> taskDao.watchByDateRange(
                    uid.value,
                    filter.from.toString(),
                    filter.to.toString(),
                )

                is TaskFilter.ByStatuses -> flowOf(emptyList())

                is TaskFilter.ByTags -> {
                    val tagIds = filter.ids.map { it.value }
                    if (filter.matchAll) {
                        taskDao.watchByAllTags(uid.value, tagIds, tagIds.size)
                    } else {
                        taskDao.watchByAnyTag(uid.value, tagIds)
                    }
                }

                is TaskFilter.ByPriorities -> taskDao.watchByPriorities(
                    uid.value,
                    filter.priorities.map { it.name },
                )

                is TaskFilter.ByRegexp -> taskDao.watchByRegexp(uid.value, filter.pattern)

                is TaskFilter.ByDateBucket -> {
                    val range = filter.bucket.toDateRange(filter.today)
                    taskDao.watchByDateRange(uid.value, range.from.toString(), range.to.toString())
                }
            }

            taskDao.withExtras(uid, entityFlow)
        }
    }

    override fun observeByDate(date: LocalDate): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        taskDao.withExtras(uid, taskDao.watchByDate(uid.value, date.toString()))
    }

    override fun observeSubtasks(parentId: TaskId): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        taskDao.withExtras(
            uid,
            taskDao.watchActive(uid.value).map { rows ->
                rows.filter { it.parentTaskId == parentId.value }
            },
        )
    }

    override suspend fun create(item: Task): Result<Task> = runCatchingCancellable {
        unitOfWork.write {
            val currentUid = currentUser.scopedUserId.value
            currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
            val toInsert = item.copy(userId = currentUid)
            taskDao.upsert(toInsert.toEntity())
            saveOutgoingLinks(toInsert.id, toInsert.description)
            toInsert.tags.forEach { tagId ->
                taskDao.upsertTagCrossRefForUser(toInsert.id.value, tagId.value, currentUid.value)
            }
            syncRepository.enqueue(toInsert)
            toInsert
        }
    }

    override suspend fun update(item: Task): Result<Task> = runCatchingCancellable {
        unitOfWork.write {
            currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
            // Read-before-write guard: reject updates to non-existent entities.
            // Prevents silent data loss from upsert-on-missing.
            taskDao.getByIdForUser(item.id.value, currentUser.scopedUserId.value.value)
                ?: throw IllegalArgumentException("Task not found: ${item.id.value}")
            // Re-stamp after the guard, exactly as `create` does: the guard has just
            // established that userId is either current or anonymous, so normalising
            // anonymous -> current cannot lose information, whereas upserting the
            // caller's anonymous id verbatim would orphan the row.
            val toUpdate = item.copy(userId = currentUser.scopedUserId.value)
            taskDao.upsert(toUpdate.toEntity())
            saveOutgoingLinks(toUpdate.id, toUpdate.description)
            realignSeries(toUpdate)
            syncRepository.enqueue(toUpdate)
            toUpdate
        }
    }

    override suspend fun createSeries(tasks: List<Task>): Result<List<Task>> = runCatchingCancellable {
        unitOfWork.write {
            val currentUid = currentUser.scopedUserId.value
            // A batch is either one series — every member carries the same id —
            // or a batch of tasks that stand alone. A batch that claims to be
            // one series and carries two ids is two stories in one write, and
            // the write path cannot know which of the two was meant.
            require(TaskSeriesInvariants.isOneSeriesOrStandalone(tasks)) {
                "A series is written with one series id, not several: ${tasks.map { it.seriesId }}"
            }
            // The alignment belongs to a series: a batch of tasks that stand
            // alone keeps what each row says about itself.
            val aligned = TaskSeriesInvariants.alignedIfOneSeries(tasks)
            aligned.map { item ->
                currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
                val toInsert = item.copy(userId = currentUid)
                taskDao.upsert(toInsert.toEntity())
                saveOutgoingLinks(toInsert.id, toInsert.description)
                toInsert.tags.forEach { tagId ->
                    taskDao.upsertTagCrossRefForUser(toInsert.id.value, tagId.value, currentUid.value)
                }
                syncRepository.enqueue(toInsert)
                toInsert
            }
        }
    }

    override suspend fun updateAll(tasks: List<Task>): Result<List<Task>> = runCatchingCancellable {
        unitOfWork.write {
            val currentUid = currentUser.scopedUserId.value
            // A batch that claims to be one series but carries two series ids is a
            // mistake in the caller, and a mistake that is written is a series that
            // cannot be described. The members are aligned to the first one before
            // they are written, so a batch that drifted apart is repaired rather than
            // written as two stories.
            require(TaskSeriesInvariants.isOneSeriesOrStandalone(tasks)) {
                "A series write carries more than one series id: ${tasks.map { it.seriesId }}"
            }
            val aligned = TaskSeriesInvariants.alignedIfOneSeries(tasks)
            aligned.map { item ->
                currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
                // The same guard the single-row update has: an upsert onto a missing
                // row would create it, and a series edit must never invent a member.
                taskDao.getByIdForUser(item.id.value, currentUid.value)
                    ?: throw IllegalArgumentException("Task not found: ${item.id.value}")
                val toUpdate = item.copy(userId = currentUid)
                taskDao.upsert(toUpdate.toEntity())
                saveOutgoingLinks(toUpdate.id, toUpdate.description)
                // The tags are a shared field of a series, and they live in the
                // cross-ref table rather than in the row. A batch write that carried
                // the tags in the task but not in the database would leave the
                // series saying two different things about the work — and the merge
                // of two series is exactly the write that changes them.
                writeTags(toUpdate, currentUid.value)
                syncRepository.enqueue(toUpdate)
                toUpdate
            }
        }
    }

    /**
     * Realigns the other rows of the series to the row that was just written.
     *
     * The fields that describe the work have to say the same thing on every row of
     * a series — otherwise "apply to all of them" has no subject. The editor writes
     * the whole series when a shared field changes, but the editor is not the only
     * writer: an MCP tool renames one occurrence, a push from another device brings
     * a row with another title. The row that was written is the row that says what
     * the work is; the others follow it.
     *
     * Only the rows that actually differ are written, so changing an occurrence — a
     * date, a completion — does not rewrite the whole series for nothing.
     */
    private suspend fun realignSeries(source: Task) {
        val seriesId = source.seriesId ?: return
        // The rows that are realigned are the current profile's rows: a series is
        // one account's work, and this device does not rewrite another account's
        // rows — it stores what arrived and leaves the rest of that series alone.
        val owner = currentUser.scopedUserId.value
        val uid = owner.value
        val members = taskDao.getSeriesForUser(seriesId.value, uid)
            .filter { it.id != source.id.value }
        if (members.isEmpty()) return

        val tagRefs = taskDao.listAllTagsForUser(uid)
            .groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.map { TagId.fromString(it.tagId) }.toSet() }

        // The tags of the source are the ones the row carries in the database —
        // not the ones the caller happened to hold in memory. A caller that read
        // the task through `get()` holds no tags at all (they live in the
        // cross-ref table), and treating that absence as the truth would strip
        // the tags off every other member of the series.
        val sourceTags = taskDao.getTagIdsForUser(source.id.value, uid).first()
            .map { TagId.fromString(it) }
            .toSet()
        val sourceTask = source.copy(tags = sourceTags.toList())

        // The rule lives in the domain: the members that already agree are not
        // written at all, and the ones that disagree take the source's shared
        // fields. The repository decides *when* the series is realigned; the
        // invariants object decides *what the series says* — including which
        // fields are shared, so a field added to the enumeration is covered here
        // by construction.
        val memberTasks = members.map { member ->
            member.toTask(tags = tagRefs[member.id].orEmpty().toList())
        }
        val realigned = TaskSeriesInvariants.membersToRealign(memberTasks, sourceTask)
        if (realigned.isEmpty()) return

        // The rows and the patches that describe them commit together. A row that
        // says one thing and a patch that says another is the divergence this
        // function exists to prevent, and the two halves of the write have to be
        // one unit of work for that to hold.
        unitOfWork.write {
            for (member in realigned) {
                val memberId = member.id.value
                // The guard before the write: the whole-row upsert carries no
                // owner in its query, so the guard is what says the row may be
                // rewritten by this profile.
                currentUser.assertCanWrite(entityId = memberId, entityUserId = member.userId)
                // The repaired row is stamped with the time of the repair, and
                // never earlier than the stamp the member already had: a member
                // that was edited later than the source keeps its newer time, so
                // the push cannot carry a stale one and lose that edit.
                val repairedAt = maxOf(member.updatedAt, clock.now())
                val stamped = member.copy(updatedAt = repairedAt)
                taskDao.upsert(stamped.toEntity())
                saveOutgoingLinks(member.id, source.description, repairedAt.toEpochMilliseconds())
                val memberTags = tagRefs[memberId].orEmpty()
                if (memberTags != sourceTags) {
                    memberTags.forEach { tagId -> taskDao.removeTagRefForUser(memberId, tagId.value, uid) }
                    sourceTags.forEach { tagId -> taskDao.upsertTagCrossRefForUser(memberId, tagId.value, uid) }
                }
                enqueueFresh(member.id)
            }
        }
    }

    /**
     * Writes the tags of [task] over the tags the row carries.
     *
     * The tags live in the cross-ref table rather than in the row, so a write that
     * carried them only in the task would leave the database saying something else
     * about the work than the task does — and for a series that is the divergence
     * this whole feature exists to prevent.
     */
    private suspend fun writeTags(task: Task, uid: String) {
        val existing = taskDao.getTagIdsForUser(task.id.value, uid).first().toSet()
        val wanted = task.tags.map { it.value }.toSet()
        if (existing == wanted) return
        existing.forEach { tagId -> taskDao.removeTagRefForUser(task.id.value, tagId, uid) }
        wanted.forEach { tagId -> taskDao.upsertTagCrossRefForUser(task.id.value, tagId, uid) }
    }

    override fun observeSeries(seriesId: TaskSeriesId): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.withExtras(uid, taskDao.observeSeriesForUser(seriesId.value, uid.value))
        }

    /**
     * Writes the outgoing links of [id] and stamps the row.
     *
     * The stamp is a parameter because the caller knows which time the row
     * carries: a row that was just repaired by the series rule carries the time
     * of the repair, not the time this helper happens to read from the clock.
     */
    private suspend fun saveOutgoingLinks(
        id: TaskId,
        description: String?,
        updatedAt: Long = clock.now().toEpochMilliseconds(),
    ) {
        val links = description?.let { extractOutgoingLinks(it) }.orEmpty()
        val rows = taskDao.setOutgoingLinksForUser(
            id = id.value,
            linksJson = links.toLinksJson(),
            updatedAt = updatedAt,
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Outgoing links not written for $id — not found or not owned by current user" }
    }

    /**
     * Re-reads [id] together with its cross-refs and pushes that state to the sync
     * outbox.
     *
     * The narrow field-update methods below write through a targeted `UPDATE` /
     * cross-ref insert rather than a whole-row upsert, so the caller's copy of the
     * task is stale by the time the write lands. Re-reading is what makes the pushed
     * payload match what is actually in the database — including for deletes, which
     * propagate as state (`archivedAt`) rather than as a tombstone. `buildPatch`
     * already ships the full snapshot, so no protocol change is involved.
     */
    private suspend fun enqueueFresh(id: TaskId) {
        val row = taskDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value) ?: return
        val tags = taskDao.getTagIdsForUser(id.value, currentUser.scopedUserId.value.value).first()
            .map { TagId.fromString(it) }
        // Only the blocking edges: the pushed task has to carry the same
        // dependency set the local model carries, or the two would describe
        // different graphs and a later pull would restore edges the model had
        // already dropped.
        val deps = taskDao.blockingDependencyIdsForUser(id.value, currentUser.scopedUserId.value.value)
            .first()
            .map { TaskId.fromString(it) }
            .toSet()
        syncRepository.enqueue(row.toTask(tags = tags, blockedBy = deps))
    }

    override suspend fun delete(id: TaskId): Result<Unit> = softDelete(id)

    /**
     * Hard-deletes every task of the active profile, archived ones included.
     *
     * ## Why the delete travels to the server as an archive
     *
     * The rows are gone locally — that is the point of the wipe — but the server
     * still holds them, and this codebase has no tombstone: a delete travels as
     * state (`archivedAt`), which is what `softDelete` pushes. So every removed
     * task is enqueued as archived, and the server's copy says what the local
     * wipe means. Enqueueing nothing would leave the server calling them live,
     * and the next pull would bring them back.
     *
     * The snapshot is taken *before* the delete: afterwards the rows are gone and
     * there is nothing left to describe.
     */
    override suspend fun deleteAllForCurrentProfile(): Result<Int> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            val doomed = taskDao.listAllForUser(uid)
            // The tag and dependency lists are read while they still exist: the
            // pushed copy of a wiped task is a faithful one, not a task that
            // never had tags.
            val tagRefs = taskDao.listAllTagsForUser(uid)
                .groupBy { it.taskId }
                .mapValues { (_, rows) -> rows.map { TagId.fromString(it.tagId) } }
            val depRefs = taskDao.listAllDependenciesForUser(uid)
                .filter { it.verb == DependencyVerb.BLOCKS.name }
                .groupBy { it.taskId }
                .mapValues { (_, rows) -> rows.map { TaskId.fromString(it.dependsOnTaskId) }.toSet() }
            val now = clock.now()
            // The cross-references first: they carry no owner column of their
            // own, so the tasks they point at can be deleted without taking them
            // along.
            taskDao.deleteTagRefsForUser(uid)
            taskDao.deleteDependenciesForUser(uid)
            taskDao.deleteChecklistItemsForUser(uid)
            val removed = taskDao.deleteAllForUser(uid)
            doomed.forEach { row ->
                val task = row.toTask(
                    tags = tagRefs[row.id].orEmpty(),
                    blockedBy = depRefs[row.id].orEmpty(),
                )
                syncRepository.enqueue(task.copy(archivedAt = task.archivedAt ?: now))
            }
            removed
        }
    }

    // ── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(task: Task): Task {
        // One unit of work: the row that arrives, the tags it carries and the
        // repair of the local series either all land or none of them do. A pull
        // that wrote the row and then lost the tags would leave the series saying
        // two things about the work — the very divergence this path exists to
        // repair.
        unitOfWork.write {
            taskDao.upsert(task.toEntity())
            // A row that arrives from another device is the newest word about the work:
            // the device that wrote it had already made the whole series say the same
            // thing. So the local series follows it, and the rows that changed are pushed
            // back — otherwise the two devices keep saying different things about one
            // piece of work, and the series is a name for nothing.
            //
            // Only the current profile's series follows. A row that arrived for another
            // account — the lost-race path writes the row's own shadow — is that
            // account's business: this device stores it and does not rewrite rows it
            // does not own.
            if (task.userId == currentUser.scopedUserId.value) {
                // The tags live in the cross-ref table, not in the row. The row that
                // arrives carries the tags of the work, and the local row has to carry
                // them too — otherwise the series would disagree about the work right
                // after the pull, and the repair would spread the disagreement.
                writeTags(task, currentUser.scopedUserId.value.value)
                realignSeries(task)
            }
        }
        return task
    }

    override suspend fun softDelete(id: TaskId): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val ts = clock.now().toEpochMilliseconds()
            val rows = taskDao.softDeleteForUser(id.value, ts, currentUser.scopedUserId.value.value)
            require(rows > 0) { "Task $id not found or not owned by current user" }
            enqueueFresh(id)
        }
    }

    override suspend fun restore(id: TaskId): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val ts = clock.now().toEpochMilliseconds()
            val rows = taskDao.restoreForUser(id.value, ts, currentUser.scopedUserId.value.value)
            require(rows > 0) { "Task $id not found or not owned by current user" }
            enqueueFresh(id)
        }
    }

    override suspend fun complete(id: TaskId, force: Boolean): Result<Boolean> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            val ts = clock.now().toEpochMilliseconds()
            val rows = if (force) {
                taskDao.markCompleteForUser(id.value, ts, uid)
            } else {
                taskDao.markCompleteIfNotBlockedForUser(id.value, ts, uid, DependencyVerb.BLOCKS.name)
            }
            if (rows > 0) {
                enqueueFresh(id)
                true
            } else {
                // Zero rows means either that the task is not ours (or is gone)
                // or that the write was refused because a prerequisite is still
                // open. The two are told apart by asking whether the task is
                // there at all.
                val exists = taskDao.getByIdForUser(id.value, uid) != null
                if (exists) {
                    false
                } else {
                    throw IllegalArgumentException("Task $id not found or not owned by current user")
                }
            }
        }
    }

    override suspend fun reopen(id: TaskId): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            val ts = clock.now().toEpochMilliseconds()
            val rows = taskDao.markIncompleteForUser(id.value, ts, uid)
            require(rows > 0) { "Task $id not found or not owned by current user" }
            enqueueFresh(id)
        }
    }

    override suspend fun completeOccurrence(
        id: TaskId,
        completedAt: Long,
        updatedAt: Long,
        force: Boolean,
    ): Result<Boolean> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            val rows = if (force) {
                taskDao.markCompleteForUser(id.value, completedAt, uid)
            } else {
                taskDao.markCompleteIfNotBlockedForUser(id.value, completedAt, uid, DependencyVerb.BLOCKS.name)
            }
            if (rows > 0) {
                enqueueFresh(id)
                true
            } else {
                // Zero rows means either that the task is not ours (or is gone)
                // or that the write was refused because a prerequisite is still
                // open. The two are told apart by asking whether the task is
                // there at all.
                val exists = taskDao.getByIdForUser(id.value, uid) != null
                if (exists) {
                    false
                } else {
                    throw IllegalArgumentException("Task $id not found or not owned by current user")
                }
            }
        }
    }

    override suspend fun moveDueDate(id: TaskId, nextDue: LocalDate, updatedAt: Long): Result<Unit> =
        runCatchingCancellable {
            unitOfWork.write {
                val uid = currentUser.scopedUserId.value.value
                val rows = taskDao.setDueDateForUser(id.value, nextDue.toIsoOrNull(), updatedAt, uid)
                require(rows > 0) { "Task $id not found or not owned by current user" }
                // dueDate is a serialised field of Task, so the roll-forward has to sync.
                enqueueFresh(id)
            }
        }

    override suspend fun blockedTaskIds(taskIds: Collection<TaskId>): Set<TaskId> {
        if (taskIds.isEmpty()) return emptySet()
        val uid = currentUser.scopedUserId.value.value
        val ids = taskDao.blockedTaskIdsForUser(taskIds.map { it.value }, uid, DependencyVerb.BLOCKS.name)
        return ids.map { TaskId.fromString(it) }.toSet()
    }

    override suspend fun togglePinned(id: TaskId): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val task = taskDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value)
                ?: throw IllegalArgumentException("Task not found: ${id.value}")
            val ts = clock.now().toEpochMilliseconds()
            val rows = taskDao.setPinnedForUser(id.value, !task.isPinned, ts, currentUser.scopedUserId.value.value)
            require(rows > 0) { "Task $id not found or not owned by current user" }
            enqueueFresh(id)
        }
    }

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> = currentUser.observeForCurrentUser { uid ->
        taskDao.getTagIdsForUser(taskId.value, uid.value).map { it.map { id -> TagId.fromString(id) } }
    }

    override suspend fun exists(id: TaskId): Boolean {
        val uid = currentUser.scopedUserId.value.value
        return taskDao.getByIdForUser(id.value, uid) != null
    }

    override suspend fun get(id: TaskId): Task? {
        // Scoped: the unscoped getById would return another user's task.
        val uid = currentUser.scopedUserId.value.value
        return taskDao.getByIdForUser(id.value, uid)?.toTask()
    }

    override suspend fun setTags(
        taskId: TaskId,
        tagIds: List<TagId>,
        actor: TagEditActor,
    ): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            // Touch the owning task first so a foreign id fails here rather than
            // silently "succeeding" with zero cross-refs written.
            val entity = taskDao.getByIdForUser(taskId.value, uid)
                ?: error("Task $taskId not found")
            val existingTagStrings = taskDao.getTagIdsForUser(taskId.value, uid).first().toSet() // Set<String>
            val existingTagIds = existingTagStrings.map { TagId.fromString(it) }.toSet() // Set<TagId>
            val newTagIds = tagIds.toSet() // Set<TagId>

            val removed = existingTagIds - newTagIds
            val added = newTagIds - existingTagIds

            // Apply cross-ref changes
            existingTagStrings.forEach { tagId -> taskDao.removeTagRefForUser(taskId.value, tagId, uid) }
            newTagIds.forEach { tagId -> taskDao.upsertTagCrossRefForUser(taskId.value, tagId.value, uid) }

            // Suppression logic only for User actor; AiProposal never touches suppressions
            val currentSuppressed = StableJson.decodeFromString(
                SetSerializer(String.serializer()),
                entity.aiSuppressedTagIds,
            ).toMutableSet()

            if (actor == TagEditActor.User) {
                // User removing a tag → record as suppressed
                currentSuppressed.addAll(removed.map { it.value })
                // User adding a tag → clear suppression for that tag
                added.forEach { currentSuppressed.remove(it.value) }
            }
            // AiProposal: suppressions unchanged

            // Persist updated suppressions back to the entity
            val updatedEntity = entity.copy(
                aiSuppressedTagIds = StableJson.encodeToString(
                    SetSerializer(String.serializer()),
                    currentSuppressed,
                ),
            )
            taskDao.upsert(updatedEntity)

            // `tags` is a serialised field of Task, so the cross-ref change is part of
            // the synced state and must be pushed.
            enqueueFresh(taskId)

            // The tags are a shared field of a series: the rows of one series have to
            // carry the same ones, or "apply to all of them" is a sentence about half
            // the series. The row whose tags were just changed says what the work is
            // tagged with; the others follow it.
            val seriesId = entity.seriesId
            if (seriesId != null) {
                realignSeries(updatedEntity.toTask(tags = newTagIds.toList()))
            }
        }
    }

    override suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            require(taskDao.getByIdForUser(taskId.value, uid) != null) { "Task $taskId not found" }
            dependencyValidator.assertNoCycles(taskId, deps).getOrThrow()

            // Read existing edges so we can preserve their verbs (fixes prior hardcoded BLOCKS bug).
            val existingEdges = taskDao.observeTypedDependenciesForUser(taskId.value, uid).first()
            val existingMap = existingEdges.associateBy({ it.dependsOnTaskId }, { it.verb })

            val desired = deps.map { it.value }.toSet()
            val existing = existingEdges.map { it.dependsOnTaskId }.toSet()

            // Remove edges no longer in the desired set
            (existing - desired).forEach { depId ->
                taskDao.removeDependencyForUser(taskId.value, depId, uid)
            }

            // Upsert remaining edges, preserving existing verb or defaulting to BLOCKS for new ones
            desired.forEach { depId ->
                val verb = existingMap[depId] ?: DependencyVerb.BLOCKS.name
                taskDao.upsertDependencyForUser(taskId.value, depId, verb, uid)
            }

            // Same as setTags: `blockedBy` is part of the synced payload.
            enqueueFresh(taskId)
        }
    }

    override suspend fun setDependency(
        from: TaskId,
        to: TaskId,
        verb: DependencyVerb,
        enabled: Boolean,
    ): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            require(taskDao.getByIdForUser(from.value, uid) != null) { "Task $from not found" }
            if (enabled) {
                taskDao.upsertDependencyForUser(from.value, to.value, verb.name, uid)
            } else {
                taskDao.removeDependencyForVerb(from.value, to.value, verb.name, uid)
            }
            enqueueFresh(from)
        }
    }
}

private fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id.value,
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId?.value,
    parentTaskId = parentTaskId?.value,
    dueDate = dueDate?.toIsoOrNull(),
    dueTime = dueTime.toLocalTimeIsoOrNull(),
    startDate = startDate?.toIsoOrNull(),
    startTime = startTime.toLocalTimeIsoOrNull(),
    endDate = endDate?.toIsoOrNull(),
    endTime = endTime.toLocalTimeIsoOrNull(),
    accentColor = accentColor,
    emoji = emoji,
    completedAt = completedAt.toEpochMillisOrNull(),
    someday = someday,
    archivedAt = archivedAt.toEpochMillisOrNull(),
    isPinned = isPinned,
    recurrenceRule = recurrence?.let { StableJson.encodeToString(RecurrenceSpec.serializer(), it) },
    seriesId = seriesId?.value,
    aiSuppressedTagIds = StableJson.encodeToString(
        SetSerializer(String.serializer()),
        aiSuppressedTagIds.map { it.value }.toSet(),
    ),
    estimateMinutes = estimateMinutes,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    userId = userId.value,
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)
