package com.singularity.todo.importer

import com.singularity.todo.core.backup.BackupDomain
import com.singularity.todo.core.backup.BackupFormat
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.ChecklistItemDto
import com.singularity.todo.core.backup.NoteDto
import com.singularity.todo.core.backup.ProjectDto
import com.singularity.todo.core.backup.TagDto
import com.singularity.todo.core.backup.TaskDependencyDto
import com.singularity.todo.core.backup.TaskDto
import com.singularity.todo.core.backup.TaskTagDto
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Named scale profiles. Actual support is gated by archive budget + import verification. */
enum class GenerateProfile(val taskCount: Int) {
    TASKS_1K(1_000),
    TASKS_10K(10_000),
    TASKS_50K(50_000),
    TASKS_100K(100_000),
    ;

    val cliName: String = name.removePrefix("TASKS_").lowercase()

    companion object {
        fun fromCliName(value: String): GenerateProfile? =
            entries.firstOrNull { it.cliName.equals(value, ignoreCase = true) }
    }
}

@Serializable
data class GenerateParams(
    val seed: Long,
    val profile: String? = null,
    val tasks: Int,
    val projects: Int,
    val taskHierarchyDepth: Int,
    val subtaskShare: Double,
    val tags: Int,
    val dependencyDensity: Double,
    val notes: Int,
    val checklistItems: Int,
)

@Serializable
data class GenerateManifest(
    val generatorVersion: Int = GENERATOR_VERSION,
    val seed: Long,
    val profile: String? = null,
    val params: GenerateParams,
    val actualCounts: ActualCounts,
    val schemaVersion: Int,
    val payloadChecksum: String,
)

@Serializable
data class ActualCounts(
    val tasks: Int,
    val projects: Int,
    val tags: Int,
    val notes: Int,
    val checklistItems: Int,
    val taskDependencies: Int,
    val taskTags: Int,
)

data class GeneratedDataset(val payload: BackupPayload, val manifest: GenerateManifest)

@Suppress("TooManyFunctions")
object SyntheticBackupGenerator {
    private const val BASE_EPOCH_MS = 1_735_689_600_000L // 2025-01-01T00:00:00Z
    private const val DAY_MS = 86_400_000L
    private val PRIORITIES = listOf("none", "low", "medium", "high", "urgent")
    private val WORKFLOW_STATES = listOf("open", "next_up", "in_progress")

    fun defaultParams(profile: GenerateProfile, seed: Long): GenerateParams {
        val tasks = profile.taskCount
        val projects = when (profile) {
            GenerateProfile.TASKS_1K -> 20
            GenerateProfile.TASKS_10K -> 100
            GenerateProfile.TASKS_50K -> 250
            GenerateProfile.TASKS_100K -> 400
        }
        val depth = when (profile) {
            GenerateProfile.TASKS_1K -> 3
            GenerateProfile.TASKS_10K -> 4
            GenerateProfile.TASKS_50K -> 5
            GenerateProfile.TASKS_100K -> 5
        }
        val notes = max(1, tasks / 10)
        val checklistItems = tasks * 2
        return GenerateParams(
            seed = seed,
            profile = profile.cliName,
            tasks = tasks,
            projects = projects,
            taskHierarchyDepth = depth,
            subtaskShare = 0.25,
            tags = when (profile) {
                GenerateProfile.TASKS_1K -> 30
                GenerateProfile.TASKS_10K -> 100
                GenerateProfile.TASKS_50K -> 200
                GenerateProfile.TASKS_100K -> 300
            },
            dependencyDensity = 0.15,
            notes = notes,
            checklistItems = checklistItems,
        )
    }

    fun generate(params: GenerateParams): GeneratedDataset {
        validate(params)
        val random = Random(params.seed)
        val projects = generateProjects(params, random)
        val tags = generateTags(params)
        val tasks = generateTasks(params, projects, random)
        val taskTags = generateTaskTags(tasks, tags, random)
        val dependencies = generateDependencies(tasks, params, random)
        val notes = generateNotes(params, tasks, projects, random)
        val checklistItems = generateChecklistItems(params, tasks, random)

        val payload = BackupPayload(
            schemaVersion = BackupFormat.SCHEMA_VERSION,
            tasks = tasks,
            notes = notes,
            projects = projects,
            tags = tags,
            taskTags = taskTags,
            taskDependencies = dependencies,
            checklistItems = checklistItems,
        )

        val payloadBytes = com.singularity.todo.core.serialization.StableJson
            .encodeToString(BackupPayload.serializer(), payload)
            .encodeToByteArray()
        val checksum = BackupDomain.sha256Hex(payloadBytes)

        val manifest = GenerateManifest(
            seed = params.seed,
            profile = params.profile,
            params = params,
            actualCounts = ActualCounts(
                tasks = payload.tasks.size,
                projects = payload.projects.size,
                tags = payload.tags.size,
                notes = payload.notes.size,
                checklistItems = payload.checklistItems.size,
                taskDependencies = payload.taskDependencies.size,
                taskTags = payload.taskTags.size,
            ),
            schemaVersion = payload.schemaVersion,
            payloadChecksum = checksum,
        )
        return GeneratedDataset(payload, manifest)
    }

    fun validate(params: GenerateParams) {
        require(params.tasks > 0) { "tasks must be > 0" }
        require(params.projects >= 0) { "projects must be >= 0" }
        require(params.taskHierarchyDepth >= 1) { "taskHierarchyDepth must be >= 1" }
        require(params.subtaskShare in 0.0..1.0) { "subtaskShare must be in 0.0..1.0" }
        require(params.tags >= 0) { "tags must be >= 0" }
        require(params.dependencyDensity in 0.0..1.0) { "dependencyDensity must be in 0.0..1.0" }
        require(params.notes >= 0) { "notes must be >= 0" }
        require(params.checklistItems >= 0) { "checklistItems must be >= 0" }
        val maxSubtasks = if (params.taskHierarchyDepth <= 1) 0 else params.tasks - 1
        val requestedSubtasks = (params.tasks * params.subtaskShare).roundToInt()
        require(requestedSubtasks <= maxSubtasks) {
            "subtaskShare requires $requestedSubtasks subtasks but taskHierarchyDepth=${params.taskHierarchyDepth} allows at most $maxSubtasks"
        }
    }

    private fun generateProjects(params: GenerateParams, random: Random): List<ProjectDto> {
        return List(params.projects) { index ->
            val id = deterministicId("project", index, params.seed)
            val parentId = if (index > 0 && params.taskHierarchyDepth > 1 && random.nextDouble() < 0.3) {
                // parent must be earlier index to keep hierarchy acyclic and bounded
                val maxParent = index - 1
                val candidate = random.nextInt(maxParent + 1)
                deterministicId("project", candidate, params.seed)
            } else {
                null
            }
            val createdAt = BASE_EPOCH_MS + index * 1_000L
            ProjectDto(
                id = id,
                name = "Project ${index + 1}",
                color = deterministicColor(index, params.seed),
                description = "Synthetic project ${index + 1}",
                createdAt = createdAt,
                updatedAt = createdAt + 500L,
                parentId = parentId,
                sortOrder = index,
            )
        }.let { projects -> sanitizeProjectHierarchy(projects, params.taskHierarchyDepth) }
    }

    private fun sanitizeProjectHierarchy(projects: List<ProjectDto>, maxDepth: Int): List<ProjectDto> {
        if (projects.isEmpty()) return projects
        val byId = projects.associateBy { it.id }.toMutableMap()
        val depthMemo = mutableMapOf<String, Int>()
        fun depth(id: String): Int = depthMemo.getOrPut(id) {
            val parent = byId[id]?.parentId ?: return@getOrPut 1
            val parentDepth = if (parent in byId) depth(parent) else 0
            parentDepth + 1
        }
        // break depth violations by clearing parent; also break any accidental cycles
        val visiting = mutableSetOf<String>()
        val safe = mutableSetOf<String>()
        fun ensureAcyclic(id: String) {
            if (id in safe) return
            if (!visiting.add(id)) {
                byId[id] = byId.getValue(id).copy(parentId = null)
                safe.add(id)
                return
            }
            val parent = byId[id]?.parentId
            if (parent != null && parent in byId) ensureAcyclic(parent)
            visiting.remove(id)
            safe.add(id)
        }
        projects.forEach { ensureAcyclic(it.id) }
        // enforce depth
        val adjusted = projects.map { p ->
            var cur = p
            while (cur.parentId != null && depth(cur.id) > maxDepth) {
                cur = cur.copy(parentId = null)
                byId[cur.id] = cur
                depthMemo.clear()
            }
            cur
        }
        return adjusted
    }

    private fun generateTags(params: GenerateParams): List<TagDto> =
        List(params.tags) { index ->
            val createdAt = BASE_EPOCH_MS + index * 500L
            TagDto(
                id = deterministicId("tag", index, params.seed),
                name = "tag-${index + 1}",
                color = deterministicColor(index + 10_000, params.seed),
                createdAt = createdAt,
                updatedAt = createdAt + 100L,
                sortOrder = index,
            )
        }

    private fun generateTasks(params: GenerateParams, projects: List<ProjectDto>, random: Random): List<TaskDto> {
        val subtaskCount = min(params.tasks - 1, (params.tasks * params.subtaskShare).roundToInt()).coerceAtLeast(0)
        val topLevelCount = params.tasks - subtaskCount
        val tasks = ArrayList<TaskDto>(params.tasks)

        for (i in 0 until topLevelCount) {
            tasks.add(
                buildTask(
                    index = i,
                    parentTaskId = null,
                    params = params,
                    projects = projects,
                    random = random,
                ),
            )
        }

        // Create subtasks by attaching to existing tasks, respecting max depth.
        val depthById = HashMap<String, Int>(params.tasks * 2)
        for (t in tasks) depthById[t.id] = 1
        var createdSubtasks = 0
        var attempt = 0
        val maxAttempts = subtaskCount * 20 + 100
        while (createdSubtasks < subtaskCount && attempt < maxAttempts) {
            attempt++
            val parentIndex = random.nextInt(tasks.size)
            val parent = tasks[parentIndex]
            val parentDepth = depthById[parent.id] ?: 1
            if (parentDepth >= params.taskHierarchyDepth) continue
            val index = topLevelCount + createdSubtasks
            val child = buildTask(
                index = index,
                parentTaskId = parent.id,
                params = params,
                projects = projects,
                random = random,
                forceProjectId = parent.projectId,
            )
            tasks.add(child)
            depthById[child.id] = parentDepth + 1
            createdSubtasks++
        }
        require(createdSubtasks == subtaskCount) {
            "Could not generate requested subtasks: wanted $subtaskCount, got $createdSubtasks " +
                "(taskHierarchyDepth=${params.taskHierarchyDepth}, tasks=${params.tasks})"
        }
        // stable order by index-encoded id already; keep as generated
        return tasks
    }

    private fun buildTask(
        index: Int,
        parentTaskId: String?,
        params: GenerateParams,
        projects: List<ProjectDto>,
        random: Random,
        forceProjectId: String? = null,
    ): TaskDto {
        val id = deterministicId("task", index, params.seed)
        val createdAt = BASE_EPOCH_MS + index * 60_000L
        val dueOffsetDays = random.nextInt(-30, 120)
        val hasDueDate = random.nextDouble() < 0.75
        val dueDate = if (hasDueDate) epochDayString(BASE_EPOCH_MS + dueOffsetDays * DAY_MS) else null
        val statusRoll = random.nextDouble()
        val completedAt = when {
            statusRoll < 0.20 -> createdAt + 3_600_000L
            else -> null
        }
        val workflowState = when {
            completedAt != null -> "open"
            statusRoll < 0.35 -> "next_up"
            statusRoll < 0.45 -> "in_progress"
            else -> "open"
        }
        val priority = PRIORITIES[random.nextInt(PRIORITIES.size)]
        val projectId = forceProjectId ?: projects.takeIf { it.isNotEmpty() }?.let {
            if (random.nextDouble() < 0.8) it[random.nextInt(it.size)].id else null
        }
        return TaskDto(
            id = id,
            title = "Task ${index + 1}",
            description = "Synthetic task ${index + 1} for perf dataset",
            priority = priority,
            kind = "task",
            projectId = projectId,
            parentTaskId = parentTaskId,
            sortOrder = index,
            dueDate = dueDate,
            dueTime = if (hasDueDate && random.nextDouble() < 0.4) LocalTime(random.nextInt(0, 24), listOf(0, 15, 30, 45)[random.nextInt(4)]) else null,
            completedAt = completedAt,
            workflowState = workflowState,
            createdAt = createdAt,
            updatedAt = createdAt + 1_000L,
        )
    }

    private fun generateTaskTags(tasks: List<TaskDto>, tags: List<TagDto>, random: Random): List<TaskTagDto> {
        if (tags.isEmpty() || tasks.isEmpty()) return emptyList()
        val out = ArrayList<TaskTagDto>(tasks.size)
        for (task in tasks) {
            val count = when (random.nextInt(4)) {
                0 -> 0
                1 -> 1
                2 -> 2
                else -> min(3, tags.size)
            }
            if (count == 0) continue
            val chosen = HashSet<Int>(count * 2)
            while (chosen.size < count) chosen.add(random.nextInt(tags.size))
            for (tagIndex in chosen.sorted()) {
                out.add(TaskTagDto(taskId = task.id, tagId = tags[tagIndex].id))
            }
        }
        return out
    }

    private fun generateDependencies(tasks: List<TaskDto>, params: GenerateParams, random: Random): List<TaskDependencyDto> {
        if (tasks.size < 2 || params.dependencyDensity <= 0.0) return emptyList()
        val maxEdges = tasks.size * 3 // keep sane; density is relative
        val targetEdges = (tasks.size * params.dependencyDensity).roundToInt().coerceIn(0, maxEdges)
        if (targetEdges == 0) return emptyList()

        // DAG by construction: edge from later index to earlier index.
        val edges = LinkedHashSet<Pair<String, String>>(targetEdges * 2)
        val ids = tasks.map { it.id }
        var attempts = 0
        val maxAttempts = targetEdges * 30 + 100
        while (edges.size < targetEdges && attempts < maxAttempts) {
            attempts++
            val fromIdx = random.nextInt(1, ids.size)
            val toIdx = random.nextInt(0, fromIdx)
            edges.add(ids[fromIdx] to ids[toIdx])
        }
        return edges.map { (taskId, dependsOnId) ->
            TaskDependencyDto(taskId = taskId, dependsOnTaskId = dependsOnId, verb = "BLOCKS")
        }
    }

    private fun generateNotes(params: GenerateParams, tasks: List<TaskDto>, projects: List<ProjectDto>, random: Random): List<NoteDto> {
        if (params.notes <= 0) return emptyList()
        return List(params.notes) { index ->
            val createdAt = BASE_EPOCH_MS + index * 90_000L
            val attachToTask = tasks.isNotEmpty() && random.nextDouble() < 0.7
            val taskId = if (attachToTask) tasks[random.nextInt(tasks.size)].id else null
            val projectId = if (!attachToTask && projects.isNotEmpty() && random.nextDouble() < 0.6) {
                projects[random.nextInt(projects.size)].id
            } else {
                null
            }
            val body = "Synthetic note ${index + 1}\nSeed dataset note body."
            NoteDto(
                id = deterministicId("note", index, params.seed),
                title = "Note ${index + 1}",
                bodyMarkdown = body,
                bodyHtml = "<p>Synthetic note ${index + 1}</p><p>Seed dataset note body.</p>",
                taskId = taskId,
                projectId = projectId,
                wordCount = wordCount(body),
                charCount = body.length,
                createdAt = createdAt,
                updatedAt = createdAt + 500L,
            )
        }
    }

    private fun generateChecklistItems(params: GenerateParams, tasks: List<TaskDto>, random: Random): List<ChecklistItemDto> {
        if (params.checklistItems <= 0 || tasks.isEmpty()) return emptyList()
        return List(params.checklistItems) { index ->
            val task = tasks[index % tasks.size]
            val createdAt = BASE_EPOCH_MS + index * 30_000L
            ChecklistItemDto(
                id = deterministicId("check", index, params.seed),
                taskId = task.id,
                title = "Checklist item ${index + 1}",
                isCompleted = random.nextDouble() < 0.35,
                sortOrder = index / tasks.size,
                createdAt = createdAt,
                updatedAt = createdAt + 100L,
            )
        }
    }

    private fun deterministicId(kind: String, index: Int, seed: Long): String {
        val h1 = mix64(seed xor kind.hashCode().toLong() xor (index.toLong() * 0x9E3779B97F4A7C15uL.toLong()))
        val h2 = mix64(h1 xor 0xBF58476D1CE4E5B9uL.toLong() xor index.toLong())
        val bytes = ByteArray(16)
        for (i in 0 until 8) bytes[i] = ((h1 ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 8) bytes[8 + i] = ((h2 ushr (8 * i)) and 0xFF).toByte()
        bytes[6] = (bytes[6].toInt() and 0x0F or 0x40).toByte() // version 4
        bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte() // variant
        return bytes.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
            .let { hex -> "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}" }
    }

    private fun mix64(value: Long): Long {
        var x = value
        x = (x xor (x ushr 33)) * -0xae502812aa7333L
        x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
        return x xor (x ushr 33)
    }

    private fun deterministicColor(index: Int, seed: Long): Int {
        val r = Random(seed xor (index.toLong() * 31))
        val rgb = r.nextInt(0x1000000)
        return (0xFF000000.toInt()) or rgb
    }

    private fun epochDayString(epochMs: Long): String {
        // deterministic date formatting without java.time in common style; JVM is fine but keep simple
        val days = Math.floorDiv(epochMs, DAY_MS)
        return java.time.LocalDate.ofEpochDay(days).toString()
    }

    private fun wordCount(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }
}

internal const val GENERATOR_VERSION = 1
