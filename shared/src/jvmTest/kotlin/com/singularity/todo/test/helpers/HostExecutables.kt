package com.singularity.todo.test.helpers

import java.io.File
import java.util.concurrent.TimeUnit

fun binaryIsOnPath(name: String): Boolean =
    pathEntries().any { directory ->
        File(directory, name).let { it.isFile && it.canExecute() }
    }

fun commandSucceeds(vararg argv: String): Boolean =
    runCatching {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        try {
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor()
                false
            } else {
                process.exitValue() == 0
            }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor() }
            }
        }
    }.getOrDefault(false)

private fun pathEntries(): List<String> =
    pathVariable()
        ?.split(File.pathSeparator)
        .orEmpty()

private fun pathVariable(): String? =
    System
        .getenv()
        .entries
        .firstOrNull { it.key.equals("PATH", ignoreCase = true) }
        ?.value
