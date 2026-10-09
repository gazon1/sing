package com.singularity.todo.test.fakes

import com.singularity.todo.core.database.ProfileEntity
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Instant

/**
 * Deterministic seed data for agenda-view tests.
 *
 * ```
 * today = 2026-10-14 (Wednesday)
 * week: Mon Oct 12 – Sun Oct 18
 * ```
 *
 * ## Task layout
 *
 * | ID    | Title          | Due date     | Bucket      | Tags   |
 * |-------|----------------|--------------|-------------|--------|
 * | T01   | Overdue task   | 2026-10-13   | Overdue     | work   |
 * | T02   | Today's task   | 2026-10-14   | Today       | –      |
 * | T03   | Tomorrow's     | 2026-10-15   | Tomorrow    | urgent |
 * | T04   | This wk end    | 2026-10-17   | This Week   | –      |
 * | T05   | This wk fri    | 2026-10-16   | This Week   | –      |
 * | T06   | Next wk mon    | 2026-10-19   | Next Week  | work   |
 * | T07   | Next wk tue    | 2026-10-20   | Next Week  | –      |
 * | T08   | Next wk wed    | 2026-10-21   | Next Week  | –      |
 * | T09   | Next wk fri    | 2026-10-23   | Next Week  | urgent |
 * | T10   | Next wk sun    | 2026-10-25   | Next Week  | –      |
 * | T11   | Following mon   | 2026-10-26   | This Month | work   |
 * | T12   | Mid month      | 2026-10-28   | This Month | –      |
 * | T13   | Month end      | 2026-10-31   | This Month | urgent |
 * | T14   | In Nov         | 2026-11-04   | This Month | home   |
 * | T15   | No date        | null          | No Date    | –      |
 * | T16   | Inbox task     | null          | No Date    | work   |
 * | T17   | Archived task  | 2026-10-14   | (archived) | –      |
 * | T18   | Pinned today  | 2026-10-14   | Today      | –      |
 *
 * ## Projects
 *
 * | ID         | Name  | Parent    |
 * |------------|-------|-----------|
 * | proj-alpha | Alpha | null      |
 * | proj-beta  | Beta  | proj-alpha|
 *
 * ## Tags
 *
 * | ID          | Name   |
 * |-------------|--------|
 * | tag-work    | work   |
 * | tag-urgent  | urgent |
 * | tag-home    | home   |
 *
 * ## Profiles
 *
 * | ID                | Name     | Default |
 * |-------------------|----------|---------|
 * | profile-personal   | Personal | true    |
 * | profile-work       | Work     | false   |
 *
 * Active user for most tests: `profile-personal`.
 *
 * All tasks share `createdAt = updatedAt = Instant.EPOCH` (epoch 0) so the only
 * differentiator is `dueDate`.  The agenda evaluator only considers `dueDate` and
 * `completedAt` / `archivedAt` / `someday` / `isPinned` for bucketing, so this
 * is sufficient for deterministic matrix tests.
 *
 * ## Usage
 *
 * ### Via Koin (desktop flow tests)
 * ```kotlin
 * runDesktopAppTest(overrides = agendaSeedModule()) { koin ->
 *     agendaSeed(koin)  // seeds DB
 *     // …
 * }
 * ```
 *
 * ### Via FakeAppDatabase directly (unit / VM tests)
 * ```kotlin
 * val db = FakeAppDatabase()
 * runTest {
 *     AgendaSeed.installInto(db)
 * }
 * ```
 */
object AgendaSeed {

    // ─── Time constants ─────────────────────────────────────────────────────────

    /** Wednesday 2026-10-14 — the reference "today" for this seed. */
    val TODAY: LocalDate = LocalDate(2026, 10, 14)

    /** The userId string for the Personal profile. */
    const val PERSONAL_USER_ID = "profile-personal"

    // ─── Profiles ───────────────────────────────────────────────────────────────

    val PROFILE_PERSONAL = ProfileEntity(
        id = "profile-personal",
        name = "Personal",
        emoji = "🏠",
        colorIdx = 0,
        isDefault = true,
        createdAt = 0L,
        updatedAt = 0L,
    )

    val PROFILE_WORK = ProfileEntity(
        id = "profile-work",
        name = "Work",
        emoji = "💼",
        colorIdx = 1,
        isDefault = false,
        createdAt = 0L,
        updatedAt = 0L,
    )

    // ─── Tags ─────────────────────────────────────────────────────────────────

    val TAG_WORK = TagEntity(
        id = "tag-work",
        userId = PERSONAL_USER_ID,
        name = "work",
        color = 0xFF2196F3.toInt(),
        createdAt = 0L,
        updatedAt = 0L,
        groupId = null,
        sortOrder = 0,
        deletedAt = null,
        sync = SyncColumns(),
    )

    val TAG_URGENT = TagEntity(
        id = "tag-urgent",
        userId = PERSONAL_USER_ID,
        name = "urgent",
        color = 0xFFF44336.toInt(),
        createdAt = 0L,
        updatedAt = 0L,
        groupId = null,
        sortOrder = 1,
        deletedAt = null,
        sync = SyncColumns(),
    )

    val TAG_HOME = TagEntity(
        id = "tag-home",
        userId = PERSONAL_USER_ID,
        name = "home",
        color = 0xFF4CAF50.toInt(),
        createdAt = 0L,
        updatedAt = 0L,
        groupId = null,
        sortOrder = 2,
        deletedAt = null,
        sync = SyncColumns(),
    )

    // ─── Projects ──────────────────────────────────────────────────────────────

    val PROJECT_ALPHA = ProjectEntity(
        id = "proj-alpha",
        userId = PERSONAL_USER_ID,
        name = "Alpha",
        color = 0xFF673AB7.toInt(),
        icon = null,
        description = null,
        createdAt = 0L,
        updatedAt = 0L,
        isDefault = false,
        dueDate = null,
        team = null,
        isDeleted = false,
        deletedAt = null,
        parentId = null,
        sortOrder = 0,
        idempotencyKey = null,
        externalId = null,
        sync = SyncColumns(),
    )

    val PROJECT_BETA = ProjectEntity(
        id = "proj-beta",
        userId = PERSONAL_USER_ID,
        name = "Beta",
        color = 0xFF03A9F4.toInt(),
        icon = null,
        description = null,
        createdAt = 0L,
        updatedAt = 0L,
        isDefault = false,
        dueDate = null,
        team = null,
        isDeleted = false,
        deletedAt = null,
        parentId = "proj-alpha",
        sortOrder = 1,
        idempotencyKey = null,
        externalId = null,
        sync = SyncColumns(),
    )

    // ─── Tasks ────────────────────────────────────────────────────────────────

    private const val U = PERSONAL_USER_ID

    private fun task(
        id: String,
        title: String,
        dueDate: LocalDate?,
        completedAt: Long? = null,
        archivedAt: Long? = null,
        someday: Boolean = false,
        isPinned: Boolean = false,
        tagIds: List<String> = emptyList(),
        projectId: String? = null,
    ): Pair<TaskEntity, List<TaskTagCrossRef>> {
        val tags = tagIds.map { TaskTagCrossRef("task-$id", it) }
        val ent = TaskEntity(
            id = "task-$id",
            title = title,
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            projectId = projectId,
            parentTaskId = null,
            dueDate = dueDate?.toString(),
            dueTime = null,
            startDate = null,
            startTime = null,
            endDate = null,
            endTime = null,
            accentColor = null,
            emoji = null,
            estimateMinutes = null,
            completedAt = completedAt,
            someday = someday,
            archivedAt = archivedAt,
            isPinned = isPinned,
            recurrenceRule = null,
            outgoingLinks = "[]",
            aiSuppressedTagIds = "[]",
            createdAt = 0L,
            updatedAt = 0L,
            userId = U,
            sync = SyncColumns(),
        )
        return ent to tags
    }

    // T01–T18: see table in KDoc above.
    private val T01 = task("T01", "Overdue task", LocalDate(2026, 10, 13), tagIds = listOf("tag-work"))
    private val T02 = task("T02", "Today's task", LocalDate(2026, 10, 14))
    private val T03 = task("T03", "Tomorrow's task", LocalDate(2026, 10, 15), tagIds = listOf("tag-urgent"))
    private val T04 = task("T04", "This wk end", LocalDate(2026, 10, 17)) // Saturday
    private val T05 = task("T05", "This wk fri", LocalDate(2026, 10, 16)) // Friday
    private val T06 = task("T06", "Next wk mon", LocalDate(2026, 10, 19), tagIds = listOf("tag-work"))
    private val T07 = task("T07", "Next wk tue", LocalDate(2026, 10, 20))
    private val T08 = task("T08", "Next wk wed", LocalDate(2026, 10, 21))
    private val T09 = task("T09", "Next wk fri", LocalDate(2026, 10, 23), tagIds = listOf("tag-urgent")) // Friday
    private val T10 = task("T10", "Next wk sun", LocalDate(2026, 10, 25)) // Sunday
    private val T11 = task("T11", "Following mon", LocalDate(2026, 10, 26), tagIds = listOf("tag-work"))
    private val T12 = task("T12", "Mid month", LocalDate(2026, 10, 28))
    private val T13 = task("T13", "Month end", LocalDate(2026, 10, 31), tagIds = listOf("tag-urgent"))
    private val T14 = task("T14", "In Nov", LocalDate(2026, 11, 4), tagIds = listOf("tag-home"))
    private val T15 = task("T15", "No date", null) // No Date bucket
    private val T16 = task("T16", "Inbox task", null, tagIds = listOf("tag-work")) // No Date bucket
    private val T17 = task("T17", "Archived task", LocalDate(2026, 10, 14), archivedAt = 1L)
    private val T18 = task("T18", "Pinned today", LocalDate(2026, 10, 14), isPinned = true)

    private val ALL_TASK_PAIRS: List<Pair<TaskEntity, List<TaskTagCrossRef>>> = listOf(
        T01, T02, T03, T04, T05, T06, T07, T08, T09, T10,
        T11, T12, T13, T14, T15, T16, T17, T18,
    )

    // ─── Aggregate collections ─────────────────────────────────────────────────

    val tasks: List<TaskEntity> = ALL_TASK_PAIRS.map { it.first }
    val tagCrossRefs: List<TaskTagCrossRef> = ALL_TASK_PAIRS.flatMap { it.second }
    val profiles: List<ProfileEntity> = listOf(PROFILE_PERSONAL, PROFILE_WORK)
    val projects: List<ProjectEntity> = listOf(PROJECT_ALPHA, PROJECT_BETA)
    val tags: List<TagEntity> = listOf(TAG_WORK, TAG_URGENT, TAG_HOME)

    // ─── Expected agenda-bucket counts ─────────────────────────────────────────
    // Used for matrix assertions without re-computing the evaluator.

    /** Tasks in the Overdue bucket (dueDate < today, not archived). */
    val OVERDUE_IDS: Set<String> = setOf("task-T01")

    /** Tasks in the Today bucket (dueDate == today, not archived). */
    val TODAY_IDS: Set<String> = setOf("task-T02", "task-T18")

    /** Tasks in the Tomorrow bucket. */
    val TOMORROW_IDS: Set<String> = setOf("task-T03")

    /** Tasks in This Week (Mon Oct 12 – Sun Oct 19, not in narrower buckets). */
    val THIS_WEEK_IDS: Set<String> = setOf("task-T04", "task-T05")

    /** Tasks in Next Week (Oct 19 – Oct 25, not in narrower buckets). */
    val NEXT_WEEK_IDS: Set<String> = setOf("task-T06", "task-T07", "task-T08", "task-T09", "task-T10")

    /** Tasks in This Month (Oct 26 – Oct 31, not in narrower buckets). */
    val THIS_MONTH_IDS: Set<String> = setOf("task-T11", "task-T12", "task-T13", "task-T14")

    /** Tasks with no due date (not archived). */
    val NO_DATE_IDS: Set<String> = setOf("task-T15", "task-T16")

    // ─── Installation ─────────────────────────────────────────────────────────

    /**
     * Installs all seed data into [db], assigning all entities to the Personal
     * profile (`profile-personal`).
     *
     * Call this in `beforeEach` or test setup:
     * ```kotlin
     * val db = FakeAppDatabase()
     * runTest {
     *     AgendaSeed.installInto(db)
     * }
     * ```
     */
    suspend fun installInto(db: FakeAppDatabase) {
        db.seedProfiles(profiles)
        db.seedProjects(projects)
        db.seedTags(tags)
        db.seedTasks(tasks)
        // Tag cross-refs need to go through the DAO since FakeTaskDao stores them separately.
        db.taskDao().let { dao ->
            tagCrossRefs.forEach { ref -> dao.upsertTagCrossRef(ref) }
        }
    }

    /**
     * Returns a Koin [Module] that:
     * - overrides [ProfileRepository] to return `ProfileId("personal")` as the active profile;
     * - overrides [ProfileAwareCurrentUser] to return `scopedUserId = "profile-personal"`.
     *
     * The module is loaded after `domainModule()`, so it wins Koin's
     * last-definition-wins rule.
     *
     * Usage:
     * ```kotlin
     * runDesktopAppTest(overrides = agendaSeedModule()) { koin ->
     *     agendaSeed(koin)  // seeds DB
     *     // …
     * }
     * ```
     *
     * After [agendaSeed] is called, all seeded tasks are visible under
     * `profile-personal`.
     */
    fun agendaSeedModule(): Module = module {
        // Inline ProfileRepository that always reports ProfileId("personal") as active.
        // GenericUserScopedRepository CRUD is stubbed — only activeProfileId matters
        // for scopedUserId computation in ProfileAwareCurrentUser.
        val personalProfileId = ProfileId("personal")
        val personalProfile = Profile(
            id = personalProfileId,
            name = "Personal",
            emoji = "🏠",
            colorIdx = 0,
            isDefault = true,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
        val profileStore = MutableStateFlow<Map<ProfileId, Profile>>(mapOf(personalProfileId to personalProfile))
        val activeProfileId = MutableStateFlow(personalProfileId)

        single<ProfileRepository> {
            object : ProfileRepository {
                override fun observeAll(): Flow<List<Profile>> =
                    profileStore.map { it.values.toList() }

                override fun observe(id: ProfileId): Flow<Profile?> =
                    profileStore.map { it[id] }

                override suspend fun get(id: ProfileId): Profile? =
                    profileStore.value[id]

                override suspend fun create(item: Profile): Result<Profile> =
                    Result.success(item)

                override suspend fun update(item: Profile): Result<Profile> =
                    Result.success(item)

                override suspend fun delete(id: ProfileId): Result<Unit> =
                    Result.success(Unit)

                override fun activeProfile(): Flow<Profile> =
                    profileStore.map { it[activeProfileId.value]!! }

                override val activeProfileId: StateFlow<ProfileId> = activeProfileId

                override suspend fun switchTo(id: ProfileId): Result<Unit> {
                    activeProfileId.value = id
                    return Result.success(Unit)
                }

                override suspend fun findByName(name: String): Profile? =
                    profileStore.value.values.find { it.name.equals(name, ignoreCase = true) }

                override suspend fun ensureDefaults(
                    extraProfiles: List<Triple<String, String, Int>>,
                ) {
                    // no-op
                }
            }
        }

        // FakeProfileAwareCurrentUser creates its own CurrentUser with FakeAuthRepository
        // internally, and overrides scopedUserId to the desired value.
        // We pass our ProfileRepository so profileId is also correct.
        single<ProfileAwareCurrentUser> {
            FakeProfileAwareCurrentUser(
                initialUserId = UserId(PERSONAL_USER_ID),
                profileRepository = get(),
            )
        }
    }
}

/**
 * Seeds [db] with the [AgendaSeed] fixture data.
 *
 * Typical usage inside a `runDesktopAppTest` body:
 * ```kotlin
 * runDesktopAppTest(overrides = agendaSeedModule()) { koin ->
 *     agendaSeed(koin.get<FakeAppDatabase>())
 *     // …
 * }
 * ```
 *
 * @see AgendaSeed.agendaSeedModule
 */
suspend fun agendaSeed(db: FakeAppDatabase) {
    AgendaSeed.installInto(db)
}
