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
import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.model.TagEditActor
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesInvariants
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.model.TaskSharedField
import com.singularity.todo.feature.tasks.blockingDependencyIdsForUser
import com.singularity.todo.feature.tasks.followUpDependencyIdsForUser
import com.singularity.todo.feature.tasks.freshTask
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
import co.touchlab.kermit.Logger
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
    private val log: Logger = Logger.withTag("TaskRepository"),
) : TaskRepository {

    private val documentWriter: TaskDocumentWriter =
        TaskDocumentWriter(
            taskDao = taskDao,
            currentUser = currentUser,
            dependencyValidator = dependencyValidator,
            log = log,
            clock = clock,
        )

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
            taskDao.followUpDependencyIdsForUser(id.value, uid.value),
            taskDao.getTagIdsForUser(id.value, uid.value),
        ) { entity, depIds, followUpIds, tagIds ->
            if (entity?.userId == uid.value) {
                entity.toTask(
                    blockedBy = depIds.map { TaskId.fromString(it) }.toSet(),
                    followsUp = followUpIds.map { TaskId.fromString(it) }.toSet(),
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
        //
        // The filter states the mode it searches in (F6, F8). In the word mode
        // the index answers; in the contains mode the index cannot answer at
        // all — it stores words, not character positions — so the rows are read
        // and matched in memory, the same answer the search screen gives.
        if (filter is TaskFilter.Search) {
            // The index answers with the record and the place the word was
            // found; a filter asks for the record, and the position is the
            // search screen's business.
            val rule = FreeTextRule.singleTerm(filter.query)
            return when (rule.plan(filter.mode)) {
                is TextMatchPlan.Narrowed -> textIndex.observeTasks(rule.indexQuery(filter.mode))
                    .map { rows -> rows.map { it.record } }

                TextMatchPlan.BroadScan -> observeAll().map { tasks ->
                    tasks.filter { task -> TaskDomain.matches(filter.mode, task, filter.query) }
                }
            }
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
            // A rule implies a series (FR-001). Every writer passes here — the two
            // UI doors, an AI tool, the MCP server — so the refusal lives here
            // rather than in the doors.
            TaskSeriesInvariants.requireRuleLivesInSeries(item)
            val toInsert = item.copy(userId = currentUid)
            taskDao.upsert(toInsert.toEntity())
            saveOutgoingLinks(toInsert.id, toInsert.description)
            toInsert.tags.forEach { tagId ->
                taskDao.upsertTagCrossRefForUser(toInsert.id.value, tagId.value, currentUid.value)
            }
            // The push carries what the database holds. The create wrote the tags
            // a moment ago, and the edges it did not write are the ones the
            // database does not have — an edge the caller carried but the create
            // did not write would be a claim about an edge that is not there.
            enqueueFresh(toInsert.id)
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
            TaskSeriesInvariants.requireRuleLivesInSeries(item)
            // Re-stamp after the guard, exactly as `create` does: the guard has just
            // established that userId is either current or anonymous, so normalising
            // anonymous -> current cannot lose information, whereas upserting the
            // caller's anonymous id verbatim would orphan the row.
            val toUpdate = item.copy(userId = currentUser.scopedUserId.value)
            taskDao.upsert(toUpdate.toEntity())
            saveOutgoingLinks(toUpdate.id, toUpdate.description)
            // The tags are NOT written here, and that is a rule rather than an
            // omission. The tags live in the cross-ref table, and the task this
            // path is given was read through `get()` — which does not load them,
            // because the caller that reads a task to edit it is not the caller
            // that sets its tags. Writing them here would take the emptiness of
            // the read for the truth about the work and strip the tags off the
            // task.
            //
            // The tags are changed by `setTags`, which is the one path that knows
            // what they should be. If `get()` ever starts loading them, this
            // comment is the thing that has to be read again.
            realignSeries(toUpdate)
            // The push carries the state the database holds, not the state the
            // caller held. The caller read the task through `get()`, which does
            // not load the tags and the blocking edges — they live in cross-ref
            // tables — so the task in hand carries two empty collections that
            // say nothing about the work. On the wire an empty collection is a
            // claim: "the work has no tags", "the work waits for nothing". The
            // server applies the claim, and every other device loses the tags
            // on the next pull. The same rule the local write follows — the
            // emptiness of a read is not the truth about the work — has to
            // follow the push.
            enqueueFresh(toUpdate.id)
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
            aligned.forEach(TaskSeriesInvariants::requireRuleLivesInSeries)
            aligned.map { item ->
                currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
                val toInsert = item.copy(userId = currentUid)
                taskDao.upsert(toInsert.toEntity())
                saveOutgoingLinks(toInsert.id, toInsert.description)
                toInsert.tags.forEach { tagId ->
                    taskDao.upsertTagCrossRefForUser(toInsert.id.value, tagId.value, currentUid.value)
                }
                // The push carries what the database holds — the row, the tags
                // just written, and the edges the create did not write and the
                // database therefore does not have.
                enqueueFresh(toInsert.id)
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
            aligned.forEach(TaskSeriesInvariants::requireRuleLivesInSeries)
            val written = aligned.map { item ->
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
                writeTags(toUpdate.id, toUpdate.tags, currentUid.value)
                // The push carries the state the database holds. The batch
                // writes the tags — they are a shared field of a series — but it
                // does not write the blocking edges, and the task in hand may
                // carry no edges at all: a batch built from a task read through
                // `get()` holds none. On the wire an empty set is a claim, and
                // the server would apply it as "the work waits for nothing".
                enqueueFresh(toUpdate.id)
                toUpdate
            }
            // The batch is a series write, and a series write repairs the series:
            // the members that were not in the batch follow the ones that were.
            // The single-row update does the same, and the asymmetry was the
            // kind of thing that reads as harmless until a partial batch left
            // the series saying two things about the work.
            //
            // The batch has a claim about the tags: it wrote them a moment ago,
            // and the repair propagates what the batch wrote. The claim is the
            // one the caller gave the batch — a batch built from a task read
            // through `get()` carries no tags, and writing that emptiness is
            // the caller's mistake, the same one the single-row write refuses.
            aligned.firstOrNull()?.let { source -> realignSeries(source, tagsClaimed = true) }
            written
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
     *
     * @param tagsClaimed whether the writer had a claim about the tags. A writer
     *   that did not — a document that carries no `tags` key — says nothing about
     *   the tags of the other members either, so the repair leaves them alone.
     *   Without this, the arrival of a row with no tags claim would take the tags
     *   off every other member of its series: the absence of a claim about a row
     *   that is new here would read as "the work has no tags".
     */
    private suspend fun realignSeries(source: Task, tagsClaimed: Boolean = true) {
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
        //
        // A writer with no claim about the tags repairs the other fields only:
        // the tags of the members are not the source's to give away.
        val fields: Collection<TaskSharedField> = if (tagsClaimed) {
            TaskSharedField.entries
        } else {
            TaskSharedField.entries - TaskSharedField.Tags
        }
        val memberTasks = members.map { member ->
            member.toTask(tags = tagRefs[member.id].orEmpty().toList())
        }
        val realigned = TaskSeriesInvariants.membersToRealign(memberTasks, sourceTask, fields)
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
                // The member's row carries the *source's* links: the repair makes the
                // members of a series say the same thing about the work, and the
                // source is what they say it now. The mapper derives the links from
                // the task's own description, which is not what this row carries.
                taskDao.upsert(stamped.toEntity(outgoingLinks = linksJsonOf(source.description)))
                saveOutgoingLinks(member.id, source.description, repairedAt.toEpochMilliseconds())
                val memberTags = tagRefs[memberId].orEmpty()
                if (tagsClaimed && memberTags != sourceTags) {
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
    private suspend fun writeTags(id: TaskId, tags: List<TagId>, uid: String) {
        val existing = taskDao.getTagIdsForUser(id.value, uid).first().toSet()
        val wanted = tags.map { it.value }.toSet()
        if (existing == wanted) return
        existing.forEach { tagId -> taskDao.removeTagRefForUser(id.value, tagId, uid) }
        wanted.forEach { tagId -> taskDao.upsertTagCrossRefForUser(id.value, tagId, uid) }
    }

    override fun observeSeries(seriesId: TaskSeriesId): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.withExtras(uid, taskDao.observeSeriesForUser(seriesId.value, uid.value))
        }

    override suspend fun legacyRecurringTasks(): List<Task> {
        val uid = currentUser.scopedUserId.value.value
        val rows = taskDao.legacyRecurringForUser(uid)
        if (rows.isEmpty()) return emptyList()
        // The tags live in a cross-ref table, so the rows are completed with them:
        // the occurrences the repair builds inherit the work, tags included.
        val tagRefs = taskDao.listAllTagsForUser(uid)
            .groupBy { it.taskId }
            .mapValues { (_, refs) -> refs.map { TagId.fromString(it.tagId) } }
        return rows.map { row -> row.toTask(tags = tagRefs[row.id].orEmpty()) }
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
        val rows = taskDao.setOutgoingLinksForUser(
            id = id.value,
            linksJson = linksJsonOf(description),
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
        val task = taskDao.freshTask(id.value, currentUser.scopedUserId.value) ?: return
        syncRepository.enqueue(task)
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

    override suspend fun upsert(document: TaskDocument): Task {
        val task = document.task
        // One unit of work: the document that arrives either lands whole or does
        // not land at all. A pull that wrote the row and then lost the tags
        // would leave the series saying two things about the work — the very
        // divergence this path exists to repair.
        unitOfWork.write {
            // A pulled document is a write like any other: a rule that arrived
            // without a series id is the forbidden shape (FR-001), and adopting it
            // would leave the launch repair to clean up after the network.
            TaskSeriesInvariants.requireRuleLivesInSeries(task)
            if (task.userId == currentUser.scopedUserId.value) {
                // The row that arrived.
                taskDao.upsert(task.toEntity())
                // The parts of the document that are not the row — and only the
                // parts the document speaks about.
                documentWriter.writeParts(task, document)
                // A row that arrives from another device is the newest word about the work:
                // the device that wrote it had already made the whole series say the same
                // thing. So the local series follows it, and the rows that changed are pushed
                // back — otherwise the two devices keep saying different things about one
                // piece of work, and the series is a name for nothing.
                //
                // The tags are the exception when the document says nothing about them:
                // the arrival of a row with no claim about the tags says nothing about
                // the tags of the other members either, so the repair leaves them be.
                realignSeries(task, tagsClaimed = document.tags != null)
            } else {
                // A row of another account — the lost-race path writes the row's own
                // shadow — is that account's business: this device stores it and does
                // not rewrite rows it does not own. The links it already carried are
                // kept, because this device cannot write them for a row of another
                // profile and an `@Upsert` would reset them to nothing. A row that
                // is new here has no links to keep: this device does not derive them
                // either, because deriving them would be a write to another
                // account's row.
                val previous = taskDao.getByIdForUser(task.id.value, task.userId.value)
                taskDao.upsert(task.toEntity(outgoingLinks = previous?.outgoingLinks ?: EMPTY_LINKS))
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

            // Nothing changed: the tags the row carries are the tags the caller
            // asked for. Nothing is written and nothing is pushed — a write that
            // changes nothing is not a claim, and a claim about a set that did
            // not move says nothing new. Removing a tag the task does not carry
            // lands here.
            if (removed.isEmpty() && added.isEmpty()) return@write

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

            // `SetDependencies` carries the *blocking* set, and the table also holds
            // the ordering edges (FOLLOWS_UP and friends). Filtering by verb is what
            // keeps a save of the blocking set from erasing a follow-up edge the
            // picker never mentioned: the old code read every edge and removed
            // everything outside `desired`, so the two verbs could not coexist.
            val desired = deps.map { it.value }.toSet()
            val existingBlocking = taskDao.observeTypedDependenciesForUser(taskId.value, uid).first()
                .filter { it.verb == DependencyVerb.BLOCKS.name }
                .map { it.dependsOnTaskId }
                .toSet()

            (existingBlocking - desired).forEach { depId ->
                taskDao.removeDependencyForVerb(taskId.value, depId, DependencyVerb.BLOCKS.name, uid)
            }

            desired.forEach { depId ->
                taskDao.upsertDependencyForUser(taskId.value, depId, DependencyVerb.BLOCKS.name, uid)
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

    override suspend fun setDependencyRelations(
        taskId: TaskId,
        relations: Map<TaskId, DependencyVerb>,
    ): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            require(taskDao.getByIdForUser(taskId.value, uid) != null) { "Task $taskId not found" }

            // Validate the resulting blocking set before any write.
            val blockingSet = relations.filterValues { it == DependencyVerb.BLOCKS }.keys.toSet()
            dependencyValidator.assertNoCycles(taskId, blockingSet).getOrThrow()

            val existingEdges = taskDao.observeTypedDependenciesForUser(taskId.value, uid).first()
            val existing = existingEdges.map { it.dependsOnTaskId }.toSet()
            val desired = relations.keys.map { it.value }.toSet()

            // Remove edges no longer desired (any verb).
            (existing - desired).forEach { depId ->
                taskDao.removeDependencyForUser(taskId.value, depId, uid)
            }

            // Upsert desired edges with their verbs (verb change = one upsert).
            desired.forEach { depId ->
                val verb = relations[TaskId.fromString(depId)] ?: DependencyVerb.BLOCKS
                taskDao.upsertDependencyForUser(taskId.value, depId, verb.name, uid)
            }

            enqueueFresh(taskId)
        }
    }
}

/**
 * The row of a task.
 *
 * [outgoingLinks] is passed in rather than taken from the domain: the links
 * live in the row, and the domain task has no field for them — they are
 * *derived* from the description by whoever writes the row. The default derives
 * them from this task's own description, which is right for every write that
 * writes the row of the task it is given. A writer that writes the row of
 * *another* task — the repair of a series, the row of a profile this device is
 * not on — passes the links that belong to that row instead.
 *
 * A mapper that left the column out would let `@Upsert` reset it to "[]", and
 * the links of a task would vanish with every write that did not mention them.
 */
private fun Task.toEntity(outgoingLinks: String = linksJsonOf(description)): TaskEntity = TaskEntity(
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
    workflowState = workflowState.name,
    aiSuppressedTagIds = StableJson.encodeToString(
        SetSerializer(String.serializer()),
        aiSuppressedTagIds.map { it.value }.toSet(),
    ),
    estimateMinutes = estimateMinutes,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    userId = userId.value,
    outgoingLinks = outgoingLinks,
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)

/**
 * The value the links column carries when nothing wrote them.
 *
 * A row that has never had a link carries this; a row that had one carries the
 * links, and the writer that owns them decides.
 */
private const val EMPTY_LINKS = "[]"

/**
 * The links a description implies, as the column carries them.
 *
 * One derivation, two callers: the mapper that writes the row and the write
 * that rewrites the links of a row that is already there. Two derivations would
 * be two truths about the same description.
 */
internal fun linksJsonOf(description: String?): String =
    description?.let { extractOutgoingLinks(it) }.orEmpty().toLinksJson()
