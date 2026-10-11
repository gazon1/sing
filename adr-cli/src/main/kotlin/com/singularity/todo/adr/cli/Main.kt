package com.singularity.todo.adr.cli

import com.singularity.todo.core.platform.JvmHostEnvironment
import com.singularity.todo.core.platform.systemTimeZone
import com.singularity.todo.feature.ai.tools.AdrStorage
import kotlin.time.Clock

/**
 * The whole dependency graph of this CLI, in one place, with no container.
 *
 * Three values, constructed directly. The desktop app reached this through Koin and a
 * database in order to read markdown files; `AdrStorage` takes its dependencies as
 * constructor parameters exactly so that this is possible (#522).
 *
 * `Clock.System` here is the one place in the codebase allowed to name it — it is the
 * composition root, and the rule exists so that everything *below* a root is testable.
 */
fun adrStorage(): AdrStorage = AdrStorage(
    host = JvmHostEnvironment(),
    clock = Clock.System,
    timeZone = systemTimeZone,
)

fun main(args: Array<String>) {
    val exitCode = AdrCli(adrStorage(), System.out, System.err).run(args.toList())
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}