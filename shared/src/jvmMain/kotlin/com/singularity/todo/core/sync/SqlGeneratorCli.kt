package com.singularity.todo.core.sync

/**
 * CLI entry point for running [SqlGenerator] from a plain `java -cp …` command.
 *
 * Usage:
 * ```
 * java -cp <classes-dir> com.singularity.todo.core.sync.SqlGeneratorCli <output-dir>
 * ```
 *
 * Called by the `generateSyncSql` Gradle task in `shared/build.gradle.kts`.
 */
fun main(args: Array<String>) {
    val outputDir = args.singleOrNull()
        ?: error("Usage: SqlGeneratorCli <output-dir>")
    SqlGenerator.writeAll(outputDir)
    println("Generated SQL artifacts to: $outputDir")
}
