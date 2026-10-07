package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `at(1)` daemon is deleted and stays deleted.
 *
 * ## What the rule is protecting
 *
 * Two ADRs say this in prose — `2026-10-06-notification-port-deleted-because-it-cancelled-
 * other-peoples-jobs` and `2026-10-07-desktop-reminders-systemd-user-timers`. Both are
 * emphatic, both explain why, and neither is enforced by anything.
 *
 * The failure mode is not hypothetical, it is what already happened once. The JVM half of
 * the deleted `NotificationPort` shelled out to `at(1)` and:
 *
 * - `cancelAll()` ran `atq`/`atrm` across **every** `at` job on the host, including jobs
 *   the user had queued outside this app — the app deleting other people's work;
 * - `scheduleAt()` fell back to firing **immediately** when `at` exited non-zero, so an
 *   18:00 reminder fired at once on any machine without `atd`.
 *
 * Both are the kind of defect that reads as reasonable in review: `atrm` looks like the
 * obvious way to cancel an `at` job, and "fire it now if the daemon is missing" looks like
 * helpful degradation. Nobody writes that code on purpose.
 *
 * `systemd --user` is the replacement, and it has the property `at` lacked: every unit
 * has a name this app chose, so cancelling is a targeted stop rather than an enumeration.
 *
 * ## Why this is a scan
 *
 * Nothing in the type system forbids shelling out to a binary. The violation is a line
 * that compiles perfectly and reads sensibly.
 *
 * ## What is matched, and what is deliberately not
 *
 * The scan looks for the `at` binaries as **command names** — the mechanism, not the word.
 * A bare `"at"` is far too common a token to ban (it appears in prose, in `cat`, in
 * `format`), and a rule that bans a word rather than a mechanism would either be
 * unusable or would fire on every KDoc in the repository, which is how a gate gets
 * switched off.
 *
 * So: `ProcessBuilder`/`Runtime.exec` handed an `at` family binary is a violation, and the
 * cancellation binaries `atq`/`atrm` are a violation wherever they appear as a literal.
 * `ReminderFireLogic.kt` mentioning `atq` in a KDoc is not a violation, because a comment
 * cannot execute anything.
 */
@Tag("fast")
class AtBackendConfinedTest {

    /**
     * Production sources across every source set of `:shared`.
     *
     * Derived by walking up from `commonMain.root`, which is `<module>/src/commonMain/kotlin`,
     * to `<module>/src`. Test sources are excluded: this rule is about what ships.
     */
    private fun productionSources(): List<File> {
        val root = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        val sourceRoot = File(root).parentFile?.parentFile
            ?: error("cannot locate the module source root above $root")
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("${File.separator}jvmTest${File.separator}") }
            .filterNot { it.path.contains("${File.separator}commonTest${File.separator}") }
            .filterNot { it.path.contains("${File.separator}androidUnitTest${File.separator}") }
            .toList()
    }

    @Test
    fun `no production source shells out to the at daemon`() {
        val offenders = productionSources()
            .mapNotNull { file -> findViolation(file.readText())?.let { file.name to it } }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "the at(1) backend is deleted because it cancelled every at job on the host " +
                "and fired reminders immediately when the daemon was absent. Use " +
                "systemd --user, whose units this app names and can therefore target. " +
                "See docs/decisions/2026-10-06-notification-port-deleted-because-it-cancelled-" +
                "other-peoples-jobs.md — offenders: ${offenders.joinToString { "${it.first} ${it.second}" }}",
        )
    }

    /**
     * The positive control.
     *
     * A scan that silently stops matching reports success while checking nothing, which is
     * the specific failure this project has already paid for twice. This feeds the rule the
     * three shapes it must catch and asserts each is recognised. If the patterns are
     * tightened into uselessness, this fails.
     */
    @Test
    fun `the rule still recognises every way the backend was reached`() {
        val samples = listOf(
            """val p = ProcessBuilder("at", "-m", "1800")""" to "ProcessBuilder with at",
            """val p = ProcessBuilder(listOf("atq"))""" to "ProcessBuilder with atq",
            """val p = ProcessBuilder("atrm", jobId)""" to "ProcessBuilder with atrm",
            """val p = ProcessBuilder(listOf("/usr/bin/atrm"))""" to "absolute path to atrm",
            """Runtime.getRuntime().exec(arrayOf("at", "job"))""" to "Runtime.exec with at",
        )

        for ((source, description) in samples) {
            assertTrue(
                findViolation(source) != null,
                "the rule no longer catches: $description. If at(1) cannot be detected, it " +
                    "can be revived, and reviving it re-opens two already-shipped bugs",
            )
        }
    }

    /**
     * The rule must not fire on the words.
     *
     * Without this the rule would be tightened later to "ban the string `at`", which would
     * match `cat`, `format`, `at` inside prose, and half this repository's KDoc — and a
     * gate that cries wolf gets deleted rather than fixed.
     */
    @Test
    fun `the rule ignores at that is not a command name`() {
        val benign = listOf(
            "val format = \"at %s\"",
            "fun concatenate(a: String, b: String) = a + b",
            "// it ran atq across every at job on the host",
            "val data = readText(\"data.txt\")",
        )

        for (source in benign) {
            assertTrue(
                findViolation(source) == null,
                "false positive on: $source — the rule must match the mechanism, not the word",
            )
        }
    }

    /**
     * Returns a description of the violation in [source], or null.
     *
     * Two patterns rather than one, because the two ways this code was written are
     * genuinely different text: a launcher gets the binary name as its first argument,
     * and cancellation used the two utility names directly.
     */
    private fun findViolation(source: String): String? {
        AT_LAUNCHER.find(source)?.let {
            return "ProcessBuilder/Runtime.exec launching the at daemon: ${it.value}"
        }
        AT_UTILITY.find(source)?.let {
            return "at job-list/cancel utility invoked: ${it.value}"
        }
        return null
    }

    private companion object {
        /**
         * A subprocess whose first argument is an `at` family binary.
         *
         * Tolerates `ProcessBuilder("at"` and `ProcessBuilder(listOf("atq"` alike, because
         * a rule that only recognises one spelling is a rule that survives being renamed.
         */
        val AT_LAUNCHER = Regex(
            "(ProcessBuilder\\s*\\(|Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\s*\\(\\s*)" +
                "(listOf\\s*\\(\\s*|arrayOf\\s*\\(\\s*)?\\(?\\s*\"/?[\\w./-]*/?(at|atq|atrm)\"",
            RegexOption.IGNORE_CASE,
        )

        /**
         * The job-list and cancel utilities, matched wherever they appear as a literal.
         *
         * `atq` and `atrm` have no legitimate use in this codebase — nothing lists or
         * removes jobs on the host — so they are banned outright, unlike bare `at`.
         */
        val AT_UTILITY = Regex("""["'](atq|atrm)["']""")
    }
}
