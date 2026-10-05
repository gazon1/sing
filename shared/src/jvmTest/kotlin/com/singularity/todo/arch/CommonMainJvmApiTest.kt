package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No JVM-only API referenced from `commonMain`.
 *
 * ## The defect class
 *
 * `commonMain` is compiled for every target the module declares. Referencing
 * `java.*`, `javax.*` or `android.*` from it produces code that compiles and passes
 * its tests on the only targets that are ever built today — JVM and Android — and
 * fails the moment a third target exists. The failure is not a compile error the
 * author sees; it is a compile error a future contributor finds, on a platform the
 * author never had.
 *
 * This gate is the only thing standing between a merged change and that error. The
 * debt it recorded — 22 references across 15 files — is gone; the baseline below is
 * empty and the gate now blocks re-growth.
 *
 * ## Why it scans references, not imports
 *
 * The first version of this gate matched `^import (java|javax|android)\.` and nothing
 * else. Five references in `commonMain` were consequently invisible to it, all written
 * in the same week the gate landed:
 *
 * - `core/auth/SessionStore.kt` — `catch { e -> if (e is java.io.IOException) … }`
 * - `feature/ai/tools/AdrTools.kt` — `val date = java.time.LocalDate.now().toString()`
 * - `test/fakes/FakeAppDatabase.kt` — `java.time.Instant.ofEpochMilli(…)`
 * - `core/observability/RoomUsageRecorder.kt` — `…_${java.util.UUID.randomUUID()}` inside
 *   a string template, which the literal-blanking pass ate along with the text around it
 *
 * The first three are the same defect as a flagged import. Fully qualifying a
 * reference is the *natural* way to avoid an import line that a linter might object
 * to, so the escape hatch was the first thing anyone would reach for — the gate was
 * refusing the visible form and permitting the invisible one. An `import` anchor is a
 * proxy for "this code depends on a JVM type", and the proxy is what failed.
 *
 * The fourth is the reason this gate is not simply "strip the comments and grep":
 * a `${ … }` hole in a string is code, and a lexer that treats the whole literal as
 * text cannot tell the difference.
 *
 * A fifth shape needed the lookbehind rather than a new rule:
 * `klass.java.enumConstants` in `core/settings/PreferenceWrappers.kt`. It reads like a
 * package and is not one — it is `KClass.java`, a JVM-only extension.
 *
 * ## Why comments and string literals are excluded
 *
 * `core/files/FileChecksum.kt` documents *why* it uses okio by naming the
 * `java.security.MessageDigest` it replaced. Matching raw text would flag that as a
 * violation, and a gate that fires on its own documentation gets switched off. The
 * scan therefore blanks comments and string literals before looking for references,
 * keeping only code.
 *
 * ## Why the file field is now checked
 *
 * The baseline entries carry a `file`, and for its whole life nothing verified it —
 * matching was on the import text alone. So an entry pointing at `LogbookSection.kt`
 * stayed green after that file stopped holding the import, because a *different* file
 * still imported it. The record was wrong and no assertion could say so. A baseline
 * whose stated location is decorative is a comment with a failure message attached.
 *
 * ## Known limitation
 *
 * A raw string ending in a quote — `"""…""""` — closes one character early, which can
 * desynchronise the literal blanking for the remainder of that file. It produces a
 * false result, never a crash, and the fix is a closing-quote placement; the alternative
 * was a regex that is wrong in the common case instead of the rare one.
 */
@Tag("fast")
class CommonMainJvmApiTest {

    private val commonMain: File by lazy {
        val root = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        File(root)
    }

    /**
     * One recorded exception: a file, the exact offending line, and why it is allowed.
     *
     * [line] is matched against the *blanked* code line, so a comment explaining the
     * violation cannot itself be the record.
     */
    private data class KnownFinding(val file: String, val line: String, val why: String)

    /**
     * Accepted violations. Empty: every site recorded here has been closed, and the
     * reason each was allowed lived in a file that has since changed. A future
     * exception goes here with the reason it is acceptable *and* a follow-up.
     */
    private val baseline = listOf<KnownFinding>()

    private fun kotlinFiles(): List<File> = commonMain.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun relative(file: File): String =
        file.relativeTo(commonMain).path.replace(File.separatorChar, '/')

    /** Every code line in the tree that names a `java`/`javax`/`android` type. */
    private fun findings(): List<Pair<String, String>> = kotlinFiles().flatMap { file ->
        val path = relative(file)
        codeOnly(file.readText()).lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { JVM_REFERENCE.containsMatchIn(it) }
            .map { path to it }
    }

    @Test
    fun commonMain_declares_no_new_jvm_only_references() {
        val allowed = baseline.map { it.file to it.line }.toSet()
        val offenders = findings().filterNot { it in allowed }

        if (offenders.isEmpty()) return
        fail(
            "JVM-only reference in commonMain — these will not compile on Kotlin/Native, " +
                "JS or Wasm:\n" +
                offenders.joinToString("\n") { "  - ${it.first}: ${it.second}" } +
                "\n\nUse a multiplatform library (okio, kotlinx-datetime, kotlinx-io), " +
                "or add an expect/actual — and only then, a BASELINE entry with the reason " +
                "it is acceptable and a follow-up.",
        )
    }

    @Test
    fun every_baseline_entry_still_exists() {
        val present = findings().toSet()
        val stale = baseline.filterNot { (it.file to it.line) in present }

        assertTrue(
            stale.isEmpty(),
            "baseline entries whose site is gone — delete the line:\n" +
                stale.joinToString("\n") { "  - ${it.file}: ${it.line}" } +
                "\n\nA baseline that outlives its problem is a hole with paperwork.",
        )
    }

    /** The `file` a baseline entry claims must be the file that actually holds the site. */
    @Test
    fun every_baseline_entry_names_the_file_that_holds_the_site() {
        val byLine = findings().groupBy({ it.second }, { it.first })
        val misfiled = baseline.filter { entry -> entry.line !in byLine[entry.file].orEmpty() }

        assertTrue(
            misfiled.isEmpty(),
            "baseline entries naming a file that does not hold the reference. The text " +
                "exists somewhere, which is why a location-only mistake stayed green:\n" +
                misfiled.joinToString("\n") { entry ->
                    "  - claims ${entry.file}, actually in ${byLine[entry.line].orEmpty()}: ${entry.line}"
                },
        )
    }

    @Test
    fun every_baseline_entry_states_why() {
        assertTrue(
            baseline.all { it.why.isNotBlank() && it.file.isNotBlank() && it.line.isNotBlank() },
            "a baseline entry without a file, a site and a reason is not a decision",
        )
    }

    /**
     * The scanner still detects what it is here to detect.
     *
     * A regex-and-state-machine gate fails by *going quiet*: it stops matching, the
     * test goes green having checked nothing, and the debt regrows silently. This
     * project has already paid that bill twice, so each shape is pinned here —
     * the import, the fully qualified reference, the `KClass.java` extension that
     * reads like a package, and the three near-misses that must NOT be reported.
     */
    @Test
    fun `the scan detects references and ignores the prose around them`() {
        val source = """
            package com.singularity.todo.sample

            import java.util.UUID

            /**
             * Replaced java.security.MessageDigest with okio.
             * See // java.io.File for the old sketch.
             */
            fun load(path: String): String {
                val stamp = java.time.LocalDate.now()
                val label = "the old java.io.File name"
                // javax.inject.Inject is not needed here
                val backing = Utils.class.java.enumConstants
                return "${'$'}stamp ${'$'}label ${'$'}path ${'$'}backing"
            }

            fun id(): String = "run_${'$'}{java.util.UUID.randomUUID()}"
        """.trimIndent()

        val lines = codeOnly(source).lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { JVM_REFERENCE.containsMatchIn(it) }

        assertEquals(
            listOf(
                "import java.util.UUID",
                "val stamp = java.time.LocalDate.now()",
                "val backing = Utils.class.java.enumConstants",
                // The quotes and the literal prefix are blanked — they are text — while
                // the hole itself survives, because it is code. The assertion shows the
                // boundary rather than merely the presence.
                "fun id(): String =      ${'$'}{java.util.UUID.randomUUID()}",
            ),
            lines,
        )
    }

    private companion object {

        /**
         * Any qualified reference to a JVM-only type, import or not.
         *
         * The lookbehind rejects only a preceding *word* character, so `foo.java.Bar`
         * — a longer identifier that happens to end in `java` — does not match, while
         * `klass.java.enumConstants` does. That one reads like a package at a glance
         * and is not one: `KClass.java` is a JVM-only extension returning
         * `java.lang.Class`, so it belongs to this class of defect and is named here
         * rather than being waved through as a false positive.
         */
        val JVM_REFERENCE = Regex("""(?<![A-Za-z0-9_])(java|javax|android)\.[A-Za-z_]\w*""")

        /**
         * Everything that is not code: line comments, block comments, raw strings,
         * strings and char literals — plus the unterminated form of each, so an
         * unclosed literal cannot swallow the rest of a file and turn the scan into
         * a pass.
         *
         * A lexer expressed as one alternation rather than a `when` per state,
         * because `findAll` scans left to right and takes the earliest match: a line
         * comment marker inside a string loses to the quote that opened it, and a
         * quote inside a comment loses to the marker that started it. That ordering
         * is the whole trick, and it comes free here in a way it does not with a
         * state machine — the first version of this scanner was a 36-branch `when`
         * that detekt correctly called too complex to read.
         *
         * The alternatives are ordered longest-construct-first, so a triple quote is
         * tried before a single one and a closed block comment before an open one.
         */
        private val NON_CODE = Regex(
            listOf(
                "/\\*[\\s\\S]*?\\*/",
                "//[^\\n]*",
                RAW_OPEN + "[\\s\\S]*?" + RAW_CLOSE,
                "\"(?:\\\\.|[^\"\\\\\\n])*\"",
                "'(?:\\\\.|[^'\\\\\\n])*'",
                "/\\*[\\s\\S]*$",
                RAW_OPEN + "[\\s\\S]*$",
                "\"(?:\\\\.|[^\"\\\\\\n])*$",
            ).joinToString("|"),
        )

        private const val RAW_OPEN = "\"\"\""

        private const val RAW_CLOSE = "\"\"\""

        /**
         * A `${ … }` hole in a string template.
         *
         * Blanking a string literal blanks the code inside it too, and that code runs:
         * `id = "$prefix_${java.util.UUID.randomUUID()}"` was in
         * `core/observability/RoomUsageRecorder.kt` for the whole life of this gate,
         * invisible because the lexer treated the hole as literal text. No nesting is
         * handled — a template expression containing `}` inside a nested string is not
         * tracked — which under-detects in the safe direction only if the JVM reference
         * is *after* the nested brace, and that is a far smaller hole than the one this
         * replaces.
         */
        private val INTERPOLATION = Regex("""\$\{[^{}]*}""")

        /**
         * [source] with every comment and literal body replaced by spaces.
         *
         * Newlines survive, so a line of code is still a line of code afterwards and
         * the reported site can be pasted straight into an editor. Template holes are
         * left intact, because they are code.
         */
        private fun codeOnly(source: String): String {
            val blanked = StringBuilder(source)
            for (match in NON_CODE.findAll(source)) {
                val keep = INTERPOLATION.findAll(match.value).flatMap { it.range }.toSet()
                for (offset in match.range) {
                    val index = offset - match.range.first
                    if (source[offset] != '\n' && index !in keep) blanked[offset] = ' '
                }
            }
            return blanked.toString()
        }
    }
}
