package com.singularity.todo.importer

import com.singularity.todo.core.backup.ArchiveBudget
import com.singularity.todo.core.backup.BackupDomain
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyntheticBackupGeneratorTest {
    @Test
    fun `same seed and params produce same logical data ids and relationships`() {
        val params = SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 42L)
        val a = SyntheticBackupGenerator.generate(params)
        val b = SyntheticBackupGenerator.generate(params)

        assertEquals(a.payload, b.payload, "payload must be deterministic for same seed/params")
        assertEquals(a.manifest.actualCounts, b.manifest.actualCounts)
        assertEquals(a.manifest.payloadChecksum, b.manifest.payloadChecksum)
    }

    @Test
    fun `different seed produces different payload`() {
        val a =
            SyntheticBackupGenerator.generate(
                SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 1L),
            )
        val b =
            SyntheticBackupGenerator.generate(
                SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 2L),
            )
        assertTrue(a.payload != b.payload, "different seeds should change generated data")
    }

    @Test
    fun `counts and relationships match requested params`() {
        val params =
            GenerateParams(
                seed = 7L,
                profile = "custom",
                tasks = 120,
                projects = 8,
                taskHierarchyDepth = 3,
                subtaskShare = 0.25,
                tags = 12,
                dependencyDensity = 0.2,
                notes = 15,
                checklistItems = 40,
            )
        val generated = SyntheticBackupGenerator.generate(params)
        val payload = generated.payload

        assertEquals(120, payload.tasks.size)
        assertEquals(8, payload.projects.size)
        assertEquals(12, payload.tags.size)
        assertEquals(15, payload.notes.size)
        assertEquals(40, payload.checklistItems.size)
        assertTrue(payload.taskTags.isNotEmpty(), "should generate some task-tag links")
        assertTrue(payload.taskDependencies.isNotEmpty(), "should generate some dependencies")

        val taskIds = payload.tasks.map { it.id }.toSet()
        val projectIds = payload.projects.map { it.id }.toSet()
        val tagIds = payload.tags.map { it.id }.toSet()
        assertEquals(taskIds.size, payload.tasks.size)
        assertEquals(projectIds.size, payload.projects.size)
        assertEquals(tagIds.size, payload.tags.size)

        payload.tasks.forEach { task ->
            task.parentTaskId?.let { assertTrue(it in taskIds, "missing parent $it") }
            task.projectId?.let { assertTrue(it in projectIds, "missing project $it") }
        }
        payload.taskTags.forEach { ref ->
            assertTrue(ref.taskId in taskIds)
            assertTrue(ref.tagId in tagIds)
        }
        payload.taskDependencies.forEach { dep ->
            assertTrue(dep.taskId in taskIds)
            assertTrue(dep.dependsOnTaskId in taskIds)
            assertTrue(dep.taskId != dep.dependsOnTaskId)
        }
        payload.notes.forEach { note ->
            note.taskId?.let { assertTrue(it in taskIds) }
            note.projectId?.let { assertTrue(it in projectIds) }
        }
        payload.checklistItems.forEach { item ->
            assertTrue(item.taskId in taskIds)
        }
    }

    @Test
    fun `invalid parameters fail clearly`() {
        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(
                GenerateParams(
                    seed = 1,
                    profile = null,
                    tasks = 0,
                    projects = 0,
                    taskHierarchyDepth = 1,
                    subtaskShare = 0.0,
                    tags = 0,
                    dependencyDensity = 0.0,
                    notes = 0,
                    checklistItems = 0,
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(
                SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 1).copy(subtaskShare = 1.5),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(
                SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 1).copy(taskHierarchyDepth = 0),
            )
        }
    }

    @Test
    fun `dependency graph is acyclic`() {
        val payload =
            SyntheticBackupGenerator
                .generate(
                    SyntheticBackupGenerator
                        .defaultParams(GenerateProfile.TASKS_10K, seed = 99L)
                        .copy(dependencyDensity = 0.8),
                ).payload

        val outgoing = payload.taskDependencies.groupBy({ it.taskId }) { it.dependsOnTaskId }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()

        fun dfs(id: String) {
            if (!visiting.add(id)) error("cycle at $id")
            if (id in visited) {
                visiting.remove(id)
                return
            }
            outgoing[id].orEmpty().forEach(::dfs)
            visiting.remove(id)
            visited.add(id)
        }
        payload.tasks.forEach { dfs(it.id) }
    }

    /**
     * A dataset with fewer than three tags used to generate forever: the per-task
     * tag count could ask for two or three distinct tags out of a pool of one, and
     * the draw below it only stops when the set holds that many.
     *
     * The test is a hang detector, which is why it asserts on the result rather than
     * on a timeout: a regression fails the suite instead of wedging it if the build
     * runs with a test timeout.
     */
    @Test
    fun `small tag pool does not hang the generator`() {
        for (tagCount in 0..3) {
            val params =
                GenerateParams(
                    seed = 5L,
                    profile = null,
                    tasks = 200,
                    projects = 4,
                    taskHierarchyDepth = 2,
                    subtaskShare = 0.25,
                    tags = tagCount,
                    dependencyDensity = 0.1,
                    notes = 5,
                    checklistItems = 10,
                )
            val payload = SyntheticBackupGenerator.generate(params).payload

            assertEquals(tagCount, payload.tags.size)
            val tagIds = payload.tags.map { it.id }.toSet()
            payload.taskTags.forEach { ref -> assertTrue(ref.tagId in tagIds, "dangling tag ${ref.tagId}") }
            // No task may carry the same tag twice, whatever the pool size was.
            val perTask = payload.taskTags.groupBy { it.taskId }.mapValues { (_, refs) -> refs.map { it.tagId } }
            perTask.forEach { (taskId, tagIdsOfTask) ->
                assertEquals(tagIdsOfTask.size, tagIdsOfTask.toSet().size, "duplicate tag on $taskId")
                assertTrue(tagIdsOfTask.size <= tagCount, "$taskId has more tags than the pool holds")
            }
        }
    }

    /**
     * The issue asks for the date, priority and status distributions to be
     * parameterised, so a performance scenario can ask for a mostly-overdue, mostly
     * high-priority or mostly-completed dataset without editing the generator.
     */
    @Test
    fun `date and status distributions follow the requested shares`() {
        val params =
            GenerateParams(
                seed = 11L,
                profile = null,
                tasks = 2_000,
                projects = 10,
                taskHierarchyDepth = 2,
                subtaskShare = 0.0,
                tags = 5,
                dependencyDensity = 0.0,
                notes = 0,
                checklistItems = 0,
                dueDateShare = 1.0,
                dueDateWindowStartDays = -10,
                dueDateWindowEndDays = -1,
                completedShare = 0.5,
                nextUpShare = 0.0,
                inProgressShare = 0.0,
            )
        val tasks = SyntheticBackupGenerator.generate(params).payload.tasks

        assertEquals(tasks.size, tasks.count { it.dueDate != null }, "dueDateShare=1.0 must date every task")
        // Every window day is before the generator's fixed base epoch (2025-01-01),
        // so no task can be due on or after it — which is what a "mostly overdue"
        // scenario needs.
        assertTrue(
            tasks.all { it.dueDate!! < BASE_EPOCH_DATE },
            "window -10..-1 from the base epoch must stay before it, got ${tasks.map { it.dueDate }.distinct()}",
        )
        val completed = tasks.count { it.completedAt != null }
        assertTrue(completed in 800..1_200, "completedShare=0.5 should give ~1000 of 2000, got $completed")
        assertTrue(tasks.all { it.completedAt == null || it.workflowState == "open" })
        assertTrue(tasks.all { it.completedAt != null || it.workflowState == "open" })

        // A disjoint request: nothing completed, all claimed as in_progress.
        val allInProgress =
            SyntheticBackupGenerator
                .generate(params.copy(completedShare = 0.0, nextUpShare = 0.0, inProgressShare = 1.0))
                .payload
                .tasks
        assertTrue(allInProgress.none { it.completedAt != null })
        assertTrue(allInProgress.all { it.workflowState == "in_progress" }, "inProgressShare=1.0 must claim every task")

        val noneDue = SyntheticBackupGenerator.generate(params.copy(dueDateShare = 0.0)).payload.tasks
        assertTrue(noneDue.none { it.dueDate != null }, "dueDateShare=0.0 must date no task")
    }

    /**
     * A deep subtask share on a shallow hierarchy is feasible — every subtask can
     * hang off the one top-level task while the depth stays at 2 — but the first
     * version drew parents uniformly from all tasks, so most attempts hit a task
     * already at max depth and the attempt budget ran out. `validate()` accepted the
     * parameters and `generate()` threw, which is the worst shape of failure.
     */
    @Test
    fun `a near-total subtask share terminates on a shallow hierarchy`() {
        val params =
            GenerateParams(
                seed = 1L,
                profile = null,
                tasks = 1_000,
                projects = 0,
                taskHierarchyDepth = 2,
                subtaskShare = 0.999,
                tags = 0,
                dependencyDensity = 0.0,
                notes = 0,
                checklistItems = 0,
            )
        val tasks = SyntheticBackupGenerator.generate(params).payload.tasks

        assertEquals(1_000, tasks.size)
        val roots = tasks.filter { it.parentTaskId == null }
        assertEquals(1, roots.size, "0.999 subtask share leaves a single top-level task")
        // Depth 2 means every child's parent is that root, and nothing is deeper.
        assertEquals(999, tasks.count { it.parentTaskId == roots.single().id })
        SyntheticBackupValidator.validate(SyntheticBackupGenerator.generate(params).payload)
    }

    @Test
    fun `priority weights steer the generated priorities`() {
        val params = SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 5L)

        val allUrgent =
            SyntheticBackupGenerator
                .generate(params.copy(priorityWeights = listOf(0.0, 0.0, 0.0, 0.0, 1.0)))
                .payload
                .tasks
        assertTrue(allUrgent.all { it.priority == "urgent" }, "a single weighted priority must be used for every task")

        val noMedium =
            SyntheticBackupGenerator
                .generate(params.copy(priorityWeights = listOf(1.0, 1.0, 0.0, 1.0, 1.0)))
                .payload
                .tasks
        assertTrue(noMedium.none { it.priority == "medium" }, "a zero weight must never be drawn")
        assertEquals(1_000, noMedium.size)

        // The default is uniform: all five values should appear in 1000 draws.
        val uniform =
            SyntheticBackupGenerator
                .generate(params)
                .payload.tasks
                .map { it.priority }
                .toSet()
        assertEquals(
            setOf("none", "low", "medium", "high", "urgent"),
            uniform,
            "uniform weights should exercise every priority",
        )
    }

    @Test
    fun `incoherent priority weights are rejected`() {
        val base = SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 1L)
        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(base.copy(priorityWeights = listOf(1.0, 1.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(base.copy(priorityWeights = listOf(0.0, 0.0, 0.0, 0.0, 0.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(base.copy(priorityWeights = listOf(-1.0, 1.0, 1.0, 1.0, 1.0)))
        }
    }

    @Test
    fun `incoherent status shares are rejected`() {
        val base = SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 1L)
        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(base.copy(nextUpShare = 0.8, inProgressShare = 0.8))
        }
        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(base.copy(dueDateWindowStartDays = 10, dueDateWindowEndDays = 10))
        }
        assertFailsWith<IllegalArgumentException> {
            SyntheticBackupGenerator.generate(base.copy(completedShare = 1.5))
        }
    }

    @Test
    fun `archive budget overflow is reported and not bypassed`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                SyntheticBackupValidator.ensureWithinArchiveBudget(
                    payloadBytes = ByteArray((ArchiveBudget.MAX_TOTAL_BYTES + 1).toInt().coerceAtLeast(1)),
                    manifestBytes = ByteArray(0),
                )
            }
        assertTrue(ex.message!!.contains("exceeds backup archive budget"), ex.message)
    }

    @Test
    fun `generated archive passes codec roundtrip and backup domain validation`() =
        runTest {
            val params = SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 123L)
            val generated = SyntheticBackupGenerator.generate(params)
            val payload = generated.payload
            SyntheticBackupValidator.validate(payload)

            val payloadBytes = StableJson.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
            val manifest =
                BackupDomain.buildManifest(
                    appVersion = "test",
                    nowEpochMillis = 0L,
                    userId = UserId.anonymous,
                    payloadBytes = payloadBytes,
                    counts = BackupDomain.countsOf(payload),
                )
            val manifestBytes = StableJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()

            val fs = JvmFileSystem()
            val codec = JvmBackupCodec()
            val tmp = Files.createTempFile("synthetic-backup", ".zip")
            try {
                codec.export(manifestBytes, payloadBytes, emptyList(), tmp.toString(), fs).getOrThrow()
                val read = codec.import(tmp.toString(), fs).getOrThrow()
                val decodedManifest =
                    StableJson.decodeFromString(
                        BackupManifest.serializer(),
                        read.manifestBytes.decodeToString(),
                    )
                val decodedPayload =
                    StableJson.decodeFromString(
                        BackupPayload.serializer(),
                        read.payloadBytes.decodeToString(),
                    )
                BackupDomain.validateManifest(decodedManifest, read.payloadBytes).getOrThrow()
                SyntheticBackupValidator.validate(decodedPayload)
                assertEquals(payload.tasks.size, decodedPayload.tasks.size)
                assertEquals(payload.taskDependencies.size, decodedPayload.taskDependencies.size)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

    private companion object {
        /** The generator's fixed base epoch, `2025-01-01T00:00:00Z`, as a date string. */
        const val BASE_EPOCH_DATE = "2025-01-01"
    }
}
