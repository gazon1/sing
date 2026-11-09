// A command-line entry point blocks until its one command finishes: there is no
// longer-lived scope to launch the work into.
@file:Suppress("NoRunBlocking")

package com.singularity.todo.importer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
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
        subcommands(ConvertCommand(), VerifyCommand())
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
                "checklists=${result.payload.checklistItems.size} timeEntries=${result.payload.timeEntries.size}",
        )
        println(
            "Manifest: appVersion=${manifest.appVersion} schemaVersion=${manifest.schemaVersion} formatVersion=${manifest.formatVersion}",
        )
        println("Completed candidates (legacy checked==1): ${result.skipped["completedCandidates"]}")
        println(
            "Not imported: " +
                result.skipped
                    .filterKeys { it != "completedCandidates" }
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
            "tasks=${payload.tasks.size} notes=${payload.notes.size} projects=${payload.projects.size} tags=${payload.tags.size} checklists=${payload.checklistItems.size} timeEntries=${payload.timeEntries.size} taskTags=${payload.taskTags.size}",
        )
    }
}
