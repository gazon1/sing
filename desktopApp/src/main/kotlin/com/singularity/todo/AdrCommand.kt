package com.singularity.todo

import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.initLogging
import com.singularity.todo.feature.ai.tools.AdrStorage
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * CLI subcommand dispatcher for `singularity-todo adr <subcommand>`.
 *
 * Usage:
 *   singularity-todo adr new <slug> <title> [tag ...]
 *   singularity-todo adr list
 *   singularity-todo adr list-open
 *   singularity-todo adr read <slug>
 *   singularity-todo adr validate
 *
 * Exits with code 0 on success, 1 on error.
 */
object AdrCommand {

    @Suppress("NoRunBlocking") // CLI command: blocks until work is done, then exits
    fun main(args: Array<String>) = runBlocking {
        val subcommand = args.firstOrNull()
        when (subcommand) {
            "new" -> newAdr(args.drop(1))
            "list" -> listAdrs(args.drop(1))
            "list-open" -> listOpenDeferred()
            "read" -> readAdr(args.drop(1))
            "validate" -> validateAdrs()
            else -> {
                println("Usage: singularity-todo adr <new|list|list-open|read|validate>")
                println("  new <slug> <title> [tag ...]")
                println("  list")
                println("  list-open")
                println("  read <slug>")
                println("  validate")
                exitProcess(0)
            }
        }
    }

    private suspend fun newAdr(args: List<String>) {
        if (args.size < 2) {
            println("Usage: adr new <slug> <title> [tag ...]")
            exitProcess(1)
        }
        val slug = args[0]
        val title = args[1]
        val tags = args.drop(2)
        bootstrapKoin()
        val storage: AdrStorage = GlobalContext.get().get()
        try {
            val path = storage.writeAdr(slug, title, tags, "")
            println("Created: $path")
        } catch (e: Throwable) {
            println("ERROR: ${e.message}")
            exitProcess(1)
        } finally {
            stopKoin()
        }
    }

    private suspend fun listAdrs(args: List<String>) {
        bootstrapKoin()
        val storage: AdrStorage = GlobalContext.get().get()
        try {
            val entries = storage.listAdrs()
            if (entries.isEmpty()) {
                println("No ADRs found.")
            } else {
                entries.forEach { entry ->
                    println("${entry.slug}  ${entry.date}  ${entry.title}")
                }
            }
        } finally {
            stopKoin()
        }
    }

    private suspend fun listOpenDeferred() {
        bootstrapKoin()
        val storage: AdrStorage = GlobalContext.get().get()
        try {
            val entries = storage.listOpenDeferred()
            if (entries.isEmpty()) {
                println("No open deferred entries.")
            } else {
                entries.forEach { entry ->
                    println("${entry.slug}  ${entry.date}  ${entry.title}")
                }
            }
        } finally {
            stopKoin()
        }
    }

    private suspend fun readAdr(args: List<String>) {
        if (args.isEmpty()) {
            println("Usage: adr read <slug>")
            exitProcess(1)
        }
        val slug = args[0]
        bootstrapKoin()
        val storage: AdrStorage = GlobalContext.get().get()
        try {
            val adr = storage.readAdr(slug)
            if (adr == null) {
                println("ADR not found: $slug")
                exitProcess(1)
            } else {
                println("# ${adr.title}")
                println("date: ${adr.date}")
                println("status: ${getStatusFromFile(adr.path)}")
                println("tags: ${adr.tags}")
                println()
                println(adr.body)
            }
        } finally {
            stopKoin()
        }
    }

    private fun validateAdrs() {
        // Delegate to the Python gate script — the authoritative validator.
        // This mirrors what the CI gate does and keeps the logic in one place.
        val script = "scripts/check_adr_status.py".toPath().toFile()
        if (!script.exists()) {
            println("ERROR: scripts/check_adr_status.py not found")
            exitProcess(1)
        }
        val result = ProcessBuilder(
            "python3",
            script.absolutePath,
        )
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        result.inputStream.use { it.copyTo(System.out) }
        exitProcess(result.waitFor())
    }

    // ─── Bootstrap ───────────────────────────────────────────────────────────

    private fun bootstrapKoin() {
        initLogging(
            isDebug = System.getProperty("singularity.debug") == "true",
            version = "cli",
            logDirectory = "${System.getProperty("user.home")}/.singularity-todo/logs".toPath(),
        )
        startKoin {
            modules(listOf(platformModule(), coreLoggingModule()) + domainModule())
        }
    }

    private fun getStatusFromFile(filePath: String): String {
        val content = runCatching {
            filePath.toPath().toFile().readText()
        }.getOrElse { "unknown" }
        val fmMatch = Regex("""(?m)^status:\s*(\S+)""").find(content)
        return fmMatch?.groupValues?.get(1) ?: "no status"
    }
}

private fun exitProcess(code: Int): Nothing = kotlin.system.exitProcess(code)
