// A command-line entry point blocks until its one command finishes: there is no
// longer-lived scope to launch the work into.
@file:Suppress("NoRunBlocking")

package com.singularity.todo.importer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.singularity.todo.core.backup.ArchiveBudget
import com.singularity.todo.core.backup.BackupDomain
import com.singularity.todo.core.backup.BackupFormat
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.version.appVersion
import kotlinx.coroutines.runBlocking
import java.io.File

class ImporterCommand : CliktCommand(name = "singularity-import") {
    init {
        subcommands(ConvertCommand(), VerifyCommand(), GenerateCommand())
    }

    override fun run() = Unit
}

class ConvertCommand : CliktCommand(name = "convert") {
    val input by option("--input", help = "Legacy SingularityApp backup JSON").required()
    val output by option("--output", help = "Output native backup ZIP path").required()
    val markCompleted by option("--mark-completed", help = "Mark checked tasks as completed").flag(default = false)

    override fun run() {
        System.err.print("\r[1/4] Parsing JSON...")
        System.err.flush()
        val text = File(input).readText()
        val legacy = Mapper.json.decodeFromString(LegacyBackup.serializer(), text)
        System.err.print("\r[2/4] Mapping...          ")
        System.err.flush()
        val result =
            Mapper.map(legacy, markCompleted) { stage, cur, total ->
                System.err.print("\r[$stage] $cur/$total   ")
                System.err.flush()
            }
        System.err.print("\r[3/4] Writing archive... ")
        System.err.flush()
        val outDir = File(output).parentFile ?: File(".")
        outDir.mkdirs()

        val payloadBytes = StableJson.encodeToString(BackupPayload.serializer(), result.payload).encodeToByteArray()
        // The versions travel from the app that will read the archive, not from a
        // literal here: one schema constant, one app name, one place to bump.
        val manifest =
            BackupDomain.buildManifest(
                appVersion = appVersion().name,
                nowEpochMillis = System.currentTimeMillis(),
                userId = UserId.anonymous,
                payloadBytes = payloadBytes,
                counts = BackupDomain.countsOf(result.payload),
            )
        val manifestBytes = StableJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()

        val fs = JvmFileSystem()
        val codec = JvmBackupCodec()
        runBlocking { codec.export(manifestBytes, payloadBytes, emptyList(), output, fs).getOrThrow() }
        System.err.println("\r[4/4] Done.                    ")

        println(
            "Converted: tasks=${result.payload.tasks.size} projects=${result.payload.projects.size} " +
                "tags=${result.payload.tags.size} notes=${result.payload.notes.size} " +
                "checklists=${result.payload.checklistItems.size} timeEntries=${result.payload.timeEntries.size} " +
                "taskReminders=${result.payload.taskReminders.size}",
        )
        println(
            "Manifest: appVersion=${manifest.appVersion} schemaVersion=${manifest.schemaVersion} formatVersion=${manifest.formatVersion}",
        )
        println("Completed candidates (legacy checked==1): ${result.skipped["completedCandidates"]}")
        println(
            "Reminders: source=${result.skipped["reminderSourceRecords"] ?: 0} " +
                "imported=${result.skipped["remindersImported"] ?: 0} " +
                "duplicatesMerged=${result.skipped["remindersDuplicatesMerged"] ?: 0} " +
                "missingBaseTime=${result.skipped["remindersMissingBaseTime"] ?: 0} " +
                "readStateUnsupported=${result.skipped["reminderReadStateUnsupported"] ?: 0}",
        )
        val notImportedKeys =
            setOf(
                "completedCandidates",
                "reminderSourceRecords",
                "remindersImported",
                "remindersDuplicatesMerged",
                "remindersMissingBaseTime",
                "reminderReadStateUnsupported",
            )
        println(
            "Not imported: " +
                result.skipped
                    .filterKeys { it !in notImportedKeys }
                    .filterValues { it > 0 }
                    .entries
                    .joinToString(", ") { "${it.key}=${it.value}" },
        )
    }
}

class VerifyCommand : CliktCommand(name = "verify") {
    val input by option("--input", help = "Native backup ZIP path").required()

    override fun run() {
        val fs = JvmFileSystem()
        val codec = JvmBackupCodec()
        val read = runBlocking { codec.import(input, fs).getOrThrow() }
        val manifest = StableJson.decodeFromString(BackupManifest.serializer(), read.manifestBytes.decodeToString())
        val payload = StableJson.decodeFromString(BackupPayload.serializer(), read.payloadBytes.decodeToString())
        println(
            "manifest appName=${manifest.appName} appVersion=${manifest.appVersion} " +
                "formatVersion=${manifest.formatVersion} schemaVersion=${manifest.schemaVersion} " +
                "(this build reads schema ${BackupFormat.MIN_SUPPORTED_SCHEMA_VERSION}..${BackupFormat.SCHEMA_VERSION})",
        )
        println("payload schemaVersion=${payload.schemaVersion}")
        println(
            "tasks=${payload.tasks.size} notes=${payload.notes.size} projects=${payload.projects.size} " +
                "tags=${payload.tags.size} checklists=${payload.checklistItems.size} " +
                "timeEntries=${payload.timeEntries.size} taskTags=${payload.taskTags.size} " +
                "taskReminders=${payload.taskReminders.size}",
        )
    }
}

class GenerateCommand : CliktCommand(name = "generate") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Generate a deterministic synthetic native backup dataset for performance testing."

    val output by option("--output", help = "Output native backup ZIP path").required()
    val seed by option("--seed", help = "Deterministic generation seed").long().default(1L)
    val profileName by option(
        "--profile",
        help = "Named profile: 1k, 10k, 50k, 100k (sets defaults; explicit params override)",
    )
    val tasks by option("--tasks", help = "Number of tasks").int()
    val projects by option("--projects", help = "Number of projects").int()
    val taskHierarchyDepth by option("--task-hierarchy-depth", help = "Max task/project hierarchy depth").int()
    val subtaskShare by option("--subtask-share", help = "Share of tasks that are subtasks (0.0..1.0)").double()
    val tags by option("--tags", help = "Number of tags").int()
    val dependencyDensity by option(
        "--dependency-density",
        help = "Approx dependency edges relative to tasks (0.0..1.0), DAG by construction",
    ).double()
    val notes by option("--notes", help = "Number of notes").int()
    val checklistItems by option("--checklist-items", help = "Number of checklist items").int()
    val dueDateShare by option(
        "--due-date-share",
        help = "Share of tasks with a due date (0.0..1.0)",
    ).double()
    val dueDateWindowStartDays by option(
        "--due-date-window-start-days",
        help =
            "First day of the due-date window, inclusive, in days from the fixed base epoch " +
                "(negative = overdue)",
    ).int()
    val dueDateWindowEndDays by option(
        "--due-date-window-end-days",
        help = "End of the due-date window, exclusive, in days from the fixed base epoch",
    ).int()
    val completedShare by option("--completed-share", help = "Share of completed tasks (0.0..1.0)").double()
    val nextUpShare by option(
        "--next-up-share",
        help = "Share of not-completed tasks claimed as next_up (0.0..1.0)",
    ).double()
    val inProgressShare by option(
        "--in-progress-share",
        help = "Share of not-completed tasks claimed as in_progress (0.0..1.0)",
    ).double()
    val priorityWeights by option(
        "--priority-weights",
        help =
            "Relative weight per priority, comma-separated in the order none,low,medium,high,urgent " +
                "(default 1,1,1,1,1). E.g. 0,0,0,1,9 for mostly urgent tasks",
    )

    override fun run() {
        val params = resolveParams()
        System.err.print("\r[1/3] Generating the dataset and writing the archive...")
        System.err.flush()
        File(output).parentFile?.mkdirs()
        // The archive is written by the same helper the desktop measurement
        // uses, so both platforms restore the same file of the same dataset.
        val archive = runBlocking { SyntheticBackupArchive.write(params, output) }
        val generated = archive.dataset
        val sidecarPath = writeSidecar(generated, archive.manifest)
        verifyArchive()

        printSummary(
            generated.payload,
            archive.manifest,
            sidecarPath,
            generated,
            archive.payloadBytes,
            archive.manifestBytes,
        )
    }

    private fun resolveParams(): GenerateParams {
        val profile =
            profileName?.let { requested ->
                GenerateProfile.fromCliName(requested) ?: error(unsupportedProfileMessage(requested))
            }
        val base = profile?.let { SyntheticBackupGenerator.defaultParams(it, seed) } ?: customBaseParams()
        return overlayExplicitParams(base).copy(profile = profile?.cliName ?: base.profile)
    }

    /**
     * Overlays the flags the caller actually passed onto [base].
     *
     * The overlay is a separate function from the profile lookup so neither carries
     * the whole set of nullable options in one branch — the profile is resolved once,
     * and every other parameter is one "flag or default" pair.
     */
    private fun overlayExplicitParams(base: GenerateParams): GenerateParams =
        base.copy(
            seed = seed,
            tasks = tasks ?: base.tasks,
            projects = projects ?: base.projects,
            taskHierarchyDepth = taskHierarchyDepth ?: base.taskHierarchyDepth,
            subtaskShare = subtaskShare ?: base.subtaskShare,
            tags = tags ?: base.tags,
            dependencyDensity = dependencyDensity ?: base.dependencyDensity,
            notes = notes ?: base.notes,
            checklistItems = checklistItems ?: base.checklistItems,
            dueDateShare = dueDateShare ?: base.dueDateShare,
            dueDateWindowStartDays = dueDateWindowStartDays ?: base.dueDateWindowStartDays,
            dueDateWindowEndDays = dueDateWindowEndDays ?: base.dueDateWindowEndDays,
            completedShare = completedShare ?: base.completedShare,
            nextUpShare = nextUpShare ?: base.nextUpShare,
            inProgressShare = inProgressShare ?: base.inProgressShare,
            priorityWeights = priorityWeights?.let(::parsePriorityWeights) ?: base.priorityWeights,
        )

    /** The weights arrive as free text, so a typo has to fail with the flag named. */
    private fun parsePriorityWeights(raw: String): List<Double> {
        val parts = raw.split(',').map { it.trim() }
        val weights =
            parts.map { part ->
                part.toDoubleOrNull()
                    ?: error("--priority-weights expects comma-separated numbers, got '$part' in '$raw'")
            }
        require(weights.size == PRIORITY_COUNT) {
            "--priority-weights expects $PRIORITY_COUNT values (none,low,medium,high,urgent), " +
                "got ${weights.size} in '$raw'"
        }
        return weights
    }

    private fun unsupportedProfileMessage(requested: String): String {
        val supported = GenerateProfile.entries.joinToString(", ") { it.cliName }
        return "Unknown profile '$requested'. Supported: $supported"
    }

    private fun customBaseParams(): GenerateParams =
        GenerateParams(
            seed = seed,
            profile = null,
            tasks = tasks ?: error("--tasks is required when --profile is not set"),
            projects = projects ?: 0,
            taskHierarchyDepth = taskHierarchyDepth ?: 1,
            subtaskShare = subtaskShare ?: 0.0,
            tags = tags ?: 0,
            dependencyDensity = dependencyDensity ?: 0.0,
            notes = notes ?: 0,
            checklistItems = checklistItems ?: 0,
            dueDateShare = dueDateShare ?: 0.75,
            dueDateWindowStartDays = dueDateWindowStartDays ?: -30,
            dueDateWindowEndDays = dueDateWindowEndDays ?: 120,
            completedShare = completedShare ?: 0.20,
            nextUpShare = nextUpShare ?: 0.19,
            inProgressShare = inProgressShare ?: 0.13,
        )

    private fun writeSidecar(
        generated: GeneratedDataset,
        manifest: BackupManifest,
    ): String {
        System.err.print("\r[2/3] Writing sidecar...")
        System.err.flush()
        val payload = generated.payload
        val sidecar =
            generated.manifest.copy(
                actualCounts =
                    generated.manifest.actualCounts.copy(
                        tasks = payload.tasks.size,
                        projects = payload.projects.size,
                        tags = payload.tags.size,
                        notes = payload.notes.size,
                        checklistItems = payload.checklistItems.size,
                        taskDependencies = payload.taskDependencies.size,
                        taskTags = payload.taskTags.size,
                    ),
                schemaVersion = manifest.schemaVersion,
                payloadChecksum = manifest.payloadChecksum,
            )
        val sidecarPath = sidecarPathFor(output)
        File(sidecarPath).writeText(StableJson.encodeToString(GenerateManifest.serializer(), sidecar))
        return sidecarPath
    }

    private fun verifyArchive() {
        System.err.print("\r[3/3] Verifying...      ")
        System.err.flush()
        val read = runBlocking { JvmBackupCodec().import(output, JvmFileSystem()).getOrThrow() }
        val decodedPayload = StableJson.decodeFromString(BackupPayload.serializer(), read.payloadBytes.decodeToString())
        BackupDomain
            .validateManifest(
                StableJson.decodeFromString(BackupManifest.serializer(), read.manifestBytes.decodeToString()),
                read.payloadBytes,
            ).getOrThrow()
        SyntheticBackupValidator.validate(decodedPayload)
    }

    private fun printSummary(
        payload: BackupPayload,
        manifest: BackupManifest,
        sidecarPath: String,
        generated: GeneratedDataset,
        payloadBytes: ByteArray,
        manifestBytes: ByteArray,
    ) {
        System.err.println("\r[3/3] Done.                    ")
        println("Generated: ${File(output).path}")
        println("Sidecar: $sidecarPath")
        println("Profile: ${generated.manifest.profile ?: "custom"} seed=${generated.manifest.seed}")
        println(
            "Counts: tasks=${payload.tasks.size} projects=${payload.projects.size} tags=${payload.tags.size} " +
                "notes=${payload.notes.size} checklistItems=${payload.checklistItems.size} " +
                "taskDependencies=${payload.taskDependencies.size} taskTags=${payload.taskTags.size}",
        )
        println(
            "Manifest: schemaVersion=${manifest.schemaVersion} formatVersion=${manifest.formatVersion} payloadChecksum=${manifest.payloadChecksum}",
        )
        println(
            "Archive: entries=2 decompressedBytes=${payloadBytes.size.toLong() + manifestBytes.size.toLong()} " +
                "(budget entries=${ArchiveBudget.MAX_ENTRIES} bytes=${ArchiveBudget.MAX_TOTAL_BYTES})",
        )
    }

    private fun sidecarPathFor(archivePath: String): String =
        if (archivePath.endsWith(".zip")) {
            archivePath.removeSuffix(".zip") + ".manifest.json"
        } else {
            "$archivePath.manifest.json"
        }

    private companion object {
        /** Priorities the format knows: none, low, medium, high, urgent. */
        const val PRIORITY_COUNT = 5
    }
}
