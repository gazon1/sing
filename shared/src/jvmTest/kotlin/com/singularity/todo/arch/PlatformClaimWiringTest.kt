package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A file that describes *itself* as platform-specific must actually live in that
 * platform's source set.
 *
 * ## The defect this exists to prevent
 *
 * A KDoc sentence that reads "JVM stub for [Port]" is a claim about where the file
 * lives, and it is checkable in one comparison: the source set. An earlier commit
 * claimed `android only` for a test whose subject was in `commonMain` — measured,
 * specific, and wrong. `NoopCalendarSyncRepositoryImpl` made the same claim: its own
 * KDoc says "JVM stub", and the file sits in `commonMain`, where it is compiled into
 * the Android binary too. On Android it is not bound at all
 * (`PlatformModule.android.kt` binds `CalendarSyncSettingsRepositoryImpl`), so the
 * class ships and never runs — which is exactly what "JVM stub" denies.
 *
 * Both are the same shape: a confident, specific, verifiable assertion that a reader
 * has no way to check without opening the build file.
 *
 * ## Why the claim has to be positional
 *
 * Scanning a file for the words "Android-only" finds 25 sentences, and 24 of them
 * are true statements about *something else*: `expect` ports legitimately document
 * which platform implements them ("Android: uses `NotificationManager`"), and history
 * notes legitimately record where a bug was ("Was absent on the desktop path while
 * the Android-only screen had it"). A rule that flagged those would be wrong 96% of
 * the time, and a gate wrong 96% of the time is noise.
 *
 * So the claim has to be the file's *own first sentence* — the line that says what
 * this file is. Measured over all three source sets, that position yields 60 files
 * describing themselves and exactly one whose source set contradicts it.
 *
 * ## What this does not check
 *
 * Whether the claim is *accurate* about runtime behaviour. "JVM stub of
 * [CalendarAppQueries]" in `jvmMain` passes because the platform matches; if that
 * stub ever got bound on Android, this gate would stay green. That needs the DI
 * mirror test, not a text comparison.
 */
@Tag("fast")
class PlatformClaimWiringTest {

    private companion object {
        /** Handed over by the jvmTest task; see the system properties in `shared/build.gradle.kts`. */
        private fun root(name: String): File = File(
            System.getProperty(name)
                ?: error("$name is not set — see the jvmTest task config in shared/build.gradle.kts"),
        )

        /** Source set → the platform word a file there may legitimately claim for itself. */
        private val SOURCE_SET_PLATFORM = mapOf(
            "commonMain" to null,
            "androidMain" to "android",
            "jvmMain" to "jvm",
        )

        /** "JVM", "Android" and "Desktop" are one platform: the desktop app *is* the JVM target. */
        private const val DESKTOP = "desktop"

        /**
         * Opens a sentence claiming the file's own platform.
         *
         * `[JvmReminderScheduler][com.singularity.todo...]`-style links are how a
         * file points at a *neighbour*, and those documents are about the neighbour,
         * so the pattern is anchored to the start of the line.
         */
        private val SELF_CLAIM = Regex(
            "^(jvm|android|desktop)\\s+(stub|implementation)\\b|^(jvm|android|desktop)-only\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * First prose line of the file's own header KDoc, or null when the file
         * opens without one.
         *
         * Three kinds of line are stepped over, in this order, and the order is the
         * whole trick:
         *
         * - `package` and `import`, which are code but carry no claim. They must be
         *   skipped *before* the "is this a comment?" test, or the very first line
         *   of every Kotlin file ends the walk and the gate reads nothing. Blank
         *   lines belong here too, and for the same reason: the blank between
         *   `package` and the doc opener would otherwise stop the walk one line in.
         * - comment lines that strip to nothing, above all the bare opener of a
         *   block comment. Returning that would answer every file with a KDoc before
         *   a single sentence was read — a gate that passes because it never looked.
         *
         * Anything else ends the walk: past the doc opener lies the first
         * declaration, which is the boundary that keeps "the file's own first
         * sentence" narrower than "the first sentence anywhere above the class".
         */
        private fun firstSentence(text: String): String? {
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("package ") || line.startsWith("import ")) return@forEach
                val isComment = line.startsWith("/**") || line.startsWith("*") || line.startsWith("//")
                if (!isComment) return null
                val prose = line.removePrefix("/**").removePrefix("//").removePrefix("*").trim()
                if (prose.isEmpty()) return@forEach
                return prose
            }
            return null
        }
    }

    @Test
    fun `a file that claims a platform lives in that platform's source set`() {
        val offenders = mutableListOf<String>()

        SOURCE_SET_PLATFORM.forEach { (sourceSet, expected) ->
            val dir = root("$sourceSet.root")
            assertTrue(dir.isDirectory, "missing source root: ${dir.path}")

            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
                val claim = SELF_CLAIM.find(firstSentence(file.readText()) ?: "") ?: return@forEach
                val claimed = claim.groupValues.drop(1).first { it.isNotEmpty() }.lowercase()
                val normalised = if (claimed == DESKTOP) "jvm" else claimed
                if (normalised != expected) {
                    offenders += "${file.relativeTo(dir).path} ($sourceSet) claims \"${claim.value}\""
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "A file documents its own platform, and the source set can check whether that " +
                "claim is true. These contradict it — either the file is in the wrong source " +
                "set, or the sentence is:\n" + offenders.joinToString("\n") { "  - $it" },
        )
    }
}