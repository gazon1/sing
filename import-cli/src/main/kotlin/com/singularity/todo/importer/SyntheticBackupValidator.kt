package com.singularity.todo.importer

import com.singularity.todo.core.backup.ArchiveBudget
import com.singularity.todo.core.backup.BackupPayload

object SyntheticBackupValidator {
    fun validate(payload: BackupPayload) {
        val taskIds = payload.tasks.mapTo(HashSet(payload.tasks.size * 2)) { it.id }
        require(taskIds.size == payload.tasks.size) { "duplicate task ids" }

        val projectIds = payload.projects.mapTo(HashSet(payload.projects.size * 2)) { it.id }
        require(projectIds.size == payload.projects.size) { "duplicate project ids" }

        val tagIds = payload.tags.mapTo(HashSet(payload.tags.size * 2)) { it.id }
        require(tagIds.size == payload.tags.size) { "duplicate tag ids" }

        val noteIds = payload.notes.mapTo(HashSet(payload.notes.size * 2)) { it.id }
        require(noteIds.size == payload.notes.size) { "duplicate note ids" }

        val checklistIds = payload.checklistItems.mapTo(HashSet(payload.checklistItems.size * 2)) { it.id }
        require(checklistIds.size == payload.checklistItems.size) { "duplicate checklist item ids" }

        payload.projects.forEach { project ->
            val parent = project.parentId ?: return@forEach
            require(parent in projectIds) { "project ${project.id} references missing parent $parent" }
            require(parent != project.id) { "project ${project.id} cannot be its own parent" }
        }
        ensureNoProjectCycles(payload)

        payload.tasks.forEach { task ->
            task.projectId?.let { require(it in projectIds) { "task ${task.id} references missing project $it" } }
            val parent = task.parentTaskId ?: return@forEach
            require(parent in taskIds) { "task ${task.id} references missing parentTaskId $parent" }
            require(parent != task.id) { "task ${task.id} cannot be its own parent" }
        }
        ensureNoTaskCycles(payload)

        payload.taskTags.forEach { ref ->
            require(ref.taskId in taskIds) { "taskTag references missing task ${ref.taskId}" }
            require(ref.tagId in tagIds) { "taskTag references missing tag ${ref.tagId}" }
        }

        payload.taskDependencies.forEach { dep ->
            require(dep.taskId in taskIds) { "dependency references missing task ${dep.taskId}" }
            require(
                dep.dependsOnTaskId in taskIds,
            ) { "dependency references missing dependsOnTaskId ${dep.dependsOnTaskId}" }
            require(dep.taskId != dep.dependsOnTaskId) { "dependency self-loop on ${dep.taskId}" }
        }
        ensureNoDependencyCycles(payload)

        payload.notes.forEach { note ->
            note.taskId?.let { require(it in taskIds) { "note ${note.id} references missing task $it" } }
            note.projectId?.let { require(it in projectIds) { "note ${note.id} references missing project $it" } }
        }

        payload.checklistItems.forEach { item ->
            require(item.taskId in taskIds) { "checklist item ${item.id} references missing task ${item.taskId}" }
        }
    }

    fun ensureWithinArchiveBudget(
        payloadBytes: ByteArray,
        manifestBytes: ByteArray,
        attachmentCount: Int = 0,
    ) {
        val entryCount = 2 + attachmentCount
        val totalBytes = payloadBytes.size.toLong() + manifestBytes.size.toLong()
        if (entryCount > ArchiveBudget.MAX_ENTRIES || totalBytes > ArchiveBudget.MAX_TOTAL_BYTES) {
            throw IllegalArgumentException(
                "Generated dataset exceeds backup archive budget: " +
                    "entries=$entryCount (max=${ArchiveBudget.MAX_ENTRIES}), " +
                    "decompressedBytes=$totalBytes (max=${ArchiveBudget.MAX_TOTAL_BYTES}). " +
                    "Reduce profile/params; limits are not bypassed.",
            )
        }
    }

    private fun ensureNoProjectCycles(payload: BackupPayload) {
        val parentById = payload.projects.associate { it.id to it.parentId }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()

        fun dfs(id: String) {
            if (!visiting.add(id)) error("project hierarchy cycle detected at $id")
            if (id in visited) {
                visiting.remove(id)
                return
            }
            parentById[id]?.let { parent -> if (parent in parentById) dfs(parent) }
            visiting.remove(id)
            visited.add(id)
        }
        payload.projects.forEach { dfs(it.id) }
    }

    private fun ensureNoTaskCycles(payload: BackupPayload) {
        val parentById = payload.tasks.associate { it.id to it.parentTaskId }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()

        fun dfs(id: String) {
            if (!visiting.add(id)) error("task hierarchy cycle detected at $id")
            if (id in visited) {
                visiting.remove(id)
                return
            }
            parentById[id]?.let { parent -> if (parent in parentById) dfs(parent) }
            visiting.remove(id)
            visited.add(id)
        }
        payload.tasks.forEach { dfs(it.id) }
    }

    private fun ensureNoDependencyCycles(payload: BackupPayload) {
        val taskIds = payload.tasks.mapTo(HashSet(payload.tasks.size * 2)) { it.id }
        val outgoing = HashMap<String, MutableList<String>>(payload.taskDependencies.size * 2)
        payload.taskDependencies.forEach { dep ->
            outgoing.getOrPut(dep.taskId) { mutableListOf() }.add(dep.dependsOnTaskId)
        }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()

        fun dfs(id: String) {
            if (!visiting.add(id)) error("task dependency cycle detected at $id")
            if (id in visited) {
                visiting.remove(id)
                return
            }
            outgoing[id]?.forEach { next -> if (next in taskIds) dfs(next) }
            visiting.remove(id)
            visited.add(id)
        }
        outgoing.keys.forEach { dfs(it) }
    }
}
