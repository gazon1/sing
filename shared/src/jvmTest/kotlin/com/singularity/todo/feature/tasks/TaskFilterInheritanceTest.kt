package com.singularity.todo.feature.tasks

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.ProjectInheritedTagGroupCrossRef
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TagGroupEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Verifies that [TaskDao.watchByTag], [TaskDao.watchByAnyTag], and
 * [TaskDao.watchByAllTags] account for tag inheritance via projects.
 *
 * Inheritance chain: project → project_tag_groups → tag_group → tags
 *
 * Semantics (confirmed with product owner 2026-10-08):
 * - matchAny: task is included if ANY filter tag is either directly assigned
 *   OR belongs to any group inherited by the task's project.
 * - matchAll: task is included if EVERY filter tag is covered by at least one
 *   direct assignment OR at least one inherited group — the coverage sets are
 *   merged; a task matching `{t1}` via direct tag and `{t3}` via an inherited
 *   group satisfies `matchAll({t1, t3})`.
 */
@Tag("fast")
class TaskFilterInheritanceTest {

    private lateinit var db: AppDatabase
    private lateinit var tempDir: File

    // Non-archived tasks only
    private val t1 = task("t1", UID, projectId = "p1") // directly tagged tg1
    private val t2 = task("t2", UID, projectId = "p2") // project inherits gg1 → has tg2 via inheritance
    private val t3 = task("t3", UID, projectId = "p1") // directly tagged tg1
    private val t4 = task("t4", UID, projectId = "p3") // project inherits gg2 → has tg3 via inheritance
    private val t5 = task("t5", UID, archived = true) // archived — never returned

    // Tag groups
    private val gg1 = tagGroup("gg1", UID) // :work:
    private val gg2 = tagGroup("gg2", UID) // :urgent:

    // Tags — tg1,tg2 in gg1; tg3 in gg2; tg4 has no group
    private val tg1 = tag("tg1", UID, groupId = gg1.id)
    private val tg2 = tag("tg2", UID, groupId = gg1.id)
    private val tg3 = tag("tg3", UID, groupId = gg2.id)
    private val tg4 = tag("tg4", UID, groupId = null) // ungrouped — not inherited

    // Projects
    // p1 — no inheritance
    // p2 — inherits gg1 (contains tg1, tg2)
    // p3 — inherits gg2 (contains tg3)
    private val p1 = project("p1", UID)
    private val p2 = project("p2", UID)
    private val p3 = project("p3", UID)

    // Direct cross-refs: t1→tg1, t3→tg1
    private val directRefs = listOf(
        TaskTagCrossRef(t1.id, tg1.id),
        TaskTagCrossRef(t3.id, tg1.id),
    )

    // Inheritance joins
    private val inheritanceJoins = listOf(
        ProjectInheritedTagGroupCrossRef(p2.id, gg1.id),
        ProjectInheritedTagGroupCrossRef(p3.id, gg2.id),
    )

    private fun buildDb() {
        tempDir = Files.createTempDirectory("singularity-filter-inheritance-").toFile()
        val dbFile = File(tempDir, "test.db")
        db = AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = dbFile.absolutePath,
        )
    }

    private suspend fun seed() {
        db.projectDao().upsert(p1)
        db.projectDao().upsert(p2)
        db.projectDao().upsert(p3)
        db.tagGroupDao().upsert(gg1)
        db.tagGroupDao().upsert(gg2)
        db.tagDao().upsert(tg1)
        db.tagDao().upsert(tg2)
        db.tagDao().upsert(tg3)
        db.tagDao().upsert(tg4)
        for (ref in directRefs) {
            db.taskDao().upsertTagCrossRef(ref)
        }
        for (join in inheritanceJoins) {
            db.projectInheritedTagGroupDao().insertForUser(join.projectId, join.tagGroupId, UID)
        }
        db.taskDao().upsert(t1)
        db.taskDao().upsert(t2)
        db.taskDao().upsert(t3)
        db.taskDao().upsert(t4)
        db.taskDao().upsert(t5)
    }

    @AfterTest
    fun tearDown() {
        if (::db.isInitialized) {
            db.close()
        }
        if (::tempDir.isInitialized) {
            tempDir.deleteRecursively()
        }
    }

    // ─── watchByTag ────────────────────────────────────────────────────────────

    @Test
    fun `watchByTag — direct assignment — returns task`() = runTest {
        buildDb()
        seed()
        db.taskDao().watchByTag(UID, tg1.id).test {
            val ids = awaitItem().map { it.id }.toSet()
            assertTrue(t1.id in ids, "t1 should be found (direct tag)")
            assertTrue(t3.id in ids, "t3 should be found (direct tag)")
            cancel()
        }
    }

    @Test
    fun `watchByTag — inherited via project — returns task`() = runTest {
        buildDb()
        seed()
        // tg2 belongs to gg1, and p2 inherits gg1; t2 is in p2
        db.taskDao().watchByTag(UID, tg2.id).test {
            val ids = awaitItem().map { it.id }.toSet()
            assertTrue(t2.id in ids, "t2 should be found (inherited via p2→gg1)")
            cancel()
        }
    }

    @Test
    fun `watchByTag — no association — returns empty`() = runTest {
        buildDb()
        seed()
        // tg4 has no group → never inherited; no task directly tagged tg4
        db.taskDao().watchByTag(UID, tg4.id).test {
            assertEquals(emptyList<Any>(), awaitItem())
            cancel()
        }
    }

    // ─── watchByAnyTag ─────────────────────────────────────────────────────────

    @Test
    fun `watchByAnyTag — union of direct and inherited`() = runTest {
        buildDb()
        seed()
        // tg1 (direct via t1/t3) ∪ tg2 (inherited via t2 in p2)
        db.taskDao().watchByAnyTag(UID, listOf(tg1.id, tg2.id)).test {
            val ids = awaitItem().map { it.id }.toSet()
            assertTrue(t1.id in ids, "t1 (direct) should be found")
            assertTrue(t2.id in ids, "t2 (inherited via p2) should be found")
            assertTrue(t3.id in ids, "t3 (direct) should be found")
            cancel()
        }
    }

    @Test
    fun `watchByAnyTag — only inherited tags — returns tasks`() = runTest {
        buildDb()
        seed()
        // tg3 is only via p3's inheritance (gg2), no task directly tagged tg3
        db.taskDao().watchByAnyTag(UID, listOf(tg3.id)).test {
            val ids = awaitItem().map { it.id }.toSet()
            assertTrue(t4.id in ids, "t4 should be found (inherited via p3→gg2)")
            cancel()
        }
    }

    @Test
    fun `watchByAnyTag — no match — returns empty`() = runTest {
        buildDb()
        seed()
        db.taskDao().watchByAnyTag(UID, listOf(tg4.id)).test {
            assertEquals(emptyList<Any>(), awaitItem())
            cancel()
        }
    }

    // ─── watchByAllTags ────────────────────────────────────────────────────────

    @Test
    fun `watchByAllTags — all direct tags — returns task`() = runTest {
        buildDb()
        seed()
        db.taskDao().watchByAllTags(UID, listOf(tg1.id), 1).test {
            val ids = awaitItem().map { it.id }.toSet()
            assertTrue(t1.id in ids, "t1 should be found")
            assertTrue(t3.id in ids, "t3 should be found")
            cancel()
        }
    }

    @Test
    fun `watchByAllTags — both tags inherited via same group — satisfies matchAll`() = runTest {
        buildDb()
        seed()
        // t2 in p2 which inherits gg1 (tg1, tg2); both filter tags are covered → matchAll
        db.taskDao().watchByAllTags(UID, listOf(tg1.id, tg2.id), 2).test {
            val ids = awaitItem().map { it.id }.toSet()
            assertTrue(t2.id in ids, "t2 should be found (both covered via gg1 inheritance)")
            cancel()
        }
    }

    @Test
    fun `watchByAllTags — partial coverage — excluded`() = runTest {
        buildDb()
        seed()
        // t2 inherits gg1 (tg1, tg2) but not gg2 (tg3); cannot satisfy {tg1, tg3}
        db.taskDao().watchByAllTags(UID, listOf(tg1.id, tg3.id), 2).test {
            val ids = awaitItem().map { it.id }.toSet()
            assertTrue(t2.id !in ids, "t2 should NOT be found (incomplete coverage)")
            cancel()
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun task(
        id: String,
        userId: String,
        projectId: String? = null,
        archived: Boolean = false,
    ) = TaskEntity(
        id = id,
        title = id,
        description = null,
        priority = TaskPriority.None,
        kind = TaskKind.Task,
        projectId = projectId,
        parentTaskId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        estimateMinutes = null,
        completedAt = null,
        someday = false,
        archivedAt = if (archived) System.currentTimeMillis() else null,
        isPinned = false,
        recurrenceRule = null,
        outgoingLinks = "[]",
        aiSuppressedTagIds = "[]",
        createdAt = 0L,
        updatedAt = 0L,
        userId = userId,
        sync = SyncColumns(),
    )

    private fun tag(id: String, userId: String, groupId: String?) = TagEntity(
        id = id,
        userId = userId,
        name = id,
        color = 0xff0000,
        createdAt = 0L,
        updatedAt = 0L,
        groupId = groupId,
        sortOrder = 0,
        deletedAt = null,
        sync = SyncColumns(),
    )

    private fun tagGroup(id: String, userId: String) = TagGroupEntity(
        id = id,
        userId = userId,
        name = id,
        color = 0xff0000,
        createdAt = 0L,
        updatedAt = 0L,
        deletedAt = null,
        sync = SyncColumns(),
    )

    private fun project(id: String, userId: String) = ProjectEntity(
        id = id,
        userId = userId,
        name = id,
        color = 0,
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

    companion object {
        private const val UID = "user-1"
    }
}
