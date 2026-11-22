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
        val a = SyntheticBackupGenerator.generate(SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 1L))
        val b = SyntheticBackupGenerator.generate(SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = 2L))
        assertTrue(a.payload != b.payload, "different seeds should change generated data")
    }

    @Test
    fun `counts and relationships match requested params`() {
        val params = GenerateParams(
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
        val payload = SyntheticBackupGenerator.generate(
            SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_10K, seed = 99L).copy(dependencyDensity = 0.8),
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

    @Test
    fun `archive budget overflow is reported and not bypassed`() {
        val ex = assertFailsWith<IllegalArgumentException> {
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
            val manifest = BackupDomain.buildManifest(
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
                val decodedManifest = StableJson.decodeFromString(BackupManifest.serializer(), read.manifestBytes.decodeToString())
                val decodedPayload = StableJson.decodeFromString(BackupPayload.serializer(), read.payloadBytes.decodeToString())
                BackupDomain.validateManifest(decodedManifest, read.payloadBytes).getOrThrow()
                SyntheticBackupValidator.validate(decodedPayload)
                assertEquals(payload.tasks.size, decodedPayload.tasks.size)
                assertEquals(payload.taskDependencies.size, decodedPayload.taskDependencies.size)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
}
