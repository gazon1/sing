package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every platform-bound seam is classified, and every classification is still true.
 *
 * ## The defect this exists to prevent
 *
 * A "seam" is a type bound differently in `PlatformModule.android.kt` and
 * `PlatformModule.jvm.kt`. There are 25. Ten of the JVM implementations are inert stubs,
 * and the shape of the failure is always the same:
 *
 * the binding resolves, the caller calls it, a test asserts the call against a fake —
 * and nothing happens. A stub that is bound is indistinguishable from a working one.
 *
 * Two of those ten are live data bugs on Desktop, right now:
 * - `JvmReminderScheduler` — a reminder is written to Room and no alarm is ever armed.
 * - `NoopSyncWorkScheduler` — every sync push evaporates on sign-in.
 *
 * `scripts/find-unwired-surfaces.py` cannot see this class of defect. `orphan-binding`
 * hard-filters to `CoreDiModule.kt` (`find-unwired-surfaces.py:601-602`) and reports only
 * bindings that *nothing injects*. This is a binding that is injected and called and inert.
 *
 * `PlatformModuleMirrorTest` cannot see it either: it checks presence, not quality, and its
 * failure message explicitly blesses a desktop no-op as "an Android-only feature with a
 * no-op desktop implementation, which is legitimate".
 *
 * So this test reads the registry — `platform-seams.tsv` on the test classpath — and asks
 * four questions of it. The fourth is the one that matters:
 *
 * **A new typed binding in a platform module with no registry row fails the gate.**
 *
 * Seam 26 cannot be added silently.
 */
@Tag("fast")
class PlatformSeamGuardTest {

    /** A row of `platform-seams.tsv`. */
    private data class Seam(
        val port: String,
        val kind: String,
        val androidImpl: String,
        val jvmImpl: String,
        val verdict: String,
        val wiring: String,
        val reason: String,
    ) {
        val isInfra: Boolean get() = kind == "infra"
    }

    private fun sourceRoot(property: String): File = File(
        System.getProperty(property)
            ?: error(
                "$property system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            ),
    )

    private val jvmPlatformModule =
        File(sourceRoot("jvmMain.root"), "com/singularity/todo/core/di/PlatformModule.jvm.kt")
    private val androidPlatformModule =
        File(sourceRoot("androidMain.root"), "com/singularity/todo/core/di/PlatformModule.android.kt")

    private fun read(file: File): String {
        assertTrue(file.isFile, "missing file: ${file.path}")
        return file.readText()
    }

    /**
     * The registry, from the test classpath rather than a repo-relative path.
     *
     * `SyncPeriodicTriggerWiringTest` reads the platform modules off the system
     * properties the build hands it; a file living outside the module would need a
     * relative walk, and a gate that silently reads nothing is worse than no gate.
     */
    private fun registry(): List<Seam> {
        val text = checkNotNull(javaClass.getResourceAsStream("/platform-seams.tsv")) {
            "platform-seams.tsv is not on the test classpath — " +
                "expected at shared/src/jvmTest/resources/platform-seams.tsv"
        }.bufferedReader().use { it.readText() }

        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val f = line.split('|').map { it.trim() }
                assertEquals(
                    7,
                    f.size,
                    "registry row must have 7 columns: " +
                        "port|kind|androidImpl|jvmImpl|verdict|wiring|reason — got: $line",
                )
                Seam(f[0], f[1], f[2], f[3], f[4], f[5], f[6])
            }
            .toList()
    }

    /**
     * Strips comments and string literals.
     *
     * A commented-out binding is not a binding. Counting it would report drift that
     * does not exist; worse, a binding that only *looks* commented-out is exactly the
     * ambiguity this gate must not carry.
     */
    private val quoteChar = '"'
    private val backslashChar = '\\'

    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLine -> {
                    if (c == '\n') {
                        inLine = false
                        out.append(c)
                    }
                }

                inBlock -> {
                    if (c == '*' && next == '/') {
                        inBlock = false
                        i++
                    }
                }

                inString -> {
                    if (c == backslashChar) {
                        i++
                    } else if (c == quoteChar) {
                        inString = false
                    }
                }

                c == '/' && next == '/' -> {
                    inLine = true
                    i++
                }

                c == '/' && next == '*' -> {
                    inBlock = true
                    i++
                }

                c == quoteChar -> inString = true

                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * The simple name of every type passed to a typed Koin binding call.
     *
     * Untyped `single { get<AppDatabase>().taskDao() }` DAO accessors are deliberately
     * invisible: they are constructor-injected, they are not seams, and a DAO list that
     * grows would otherwise be a false alarm on every addition.
     *
     * The `<` walk is depth-aware so `single<DataStore<Preferences>>` yields `DataStore`
     * rather than a truncated `DataStore<Preferences`.
     */
    private fun boundTypeNames(source: String): List<String> {
        val code = stripCommentsAndStrings(source)
        val opener = Regex("""\b(?:single|factory|viewModelOf|factoryOf|singleOf)\s*<""")
        return opener.findAll(code)
            .mapNotNull { m ->
                val close = closingAngle(code, m.range.last) ?: return@mapNotNull null
                code.substring(m.range.last + 1, close)
                    .substringBefore('<')
                    .substringAfterLast('.')
                    .trim()
            }
            .toList()
    }

    /** Index of the `>` that closes the `<` at [open], or null if the source runs out. */
    private fun closingAngle(code: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '<' -> depth++

                '>' -> {
                    depth--
                    if (depth == 0) {
                        return i
                    }
                }

                else -> Unit
            }
            i++
        }
        return null
    }

    @Test
    fun `every registered seam's implementations are still bound on both platforms`() {
        val jvm = read(jvmPlatformModule)
        val android = read(androidPlatformModule)
        val failures = mutableListOf<String>()

        registry().filterNot { it.isInfra }.forEach { seam ->
            // `-` means "this platform deliberately has no binding" — the declared
            // Android-only shape, which PomodoroScheduler is the current example of.
            // Requiring a binding there would contradict the registry's own vocabulary.
            if (seam.jvmImpl != "-" && seam.port !in boundTypeNames(jvm)) {
                failures += "${seam.port} is not bound in PlatformModule.jvm.kt"
            }
            if (seam.androidImpl != "-" && seam.port !in boundTypeNames(android)) {
                failures += "${seam.port} is not bound in PlatformModule.android.kt"
            }
            if (seam.androidImpl != "-" && seam.androidImpl !in android) {
                failures += "${seam.port}: androidImpl '${seam.androidImpl}' not found in PlatformModule.android.kt"
            }
            if (seam.jvmImpl != "-" && seam.jvmImpl !in jvm) {
                failures += "${seam.port}: jvmImpl '${seam.jvmImpl}' not found in PlatformModule.jvm.kt"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "platform seam drift:\n" + failures.joinToString("\n"),
        )
    }

    @Test
    fun `a seam marked real is not actually a stub`() {
        val stubPattern = Regex("Noop|NoOp|Stub")
        val offenders = registry()
            .filterNot { it.isInfra }
            .filter { it.verdict == "real" }
            .filter { stubPattern.containsMatchIn(it.jvmImpl) }
            .map { "${it.port} is marked real but bound to '${it.jvmImpl}'" }

        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    @Test
    fun `a seam marked stub or exempt carries a written reason`() {
        val offenders = registry()
            .filterNot { it.isInfra }
            .filter { it.verdict == "stub" || it.verdict == "exempt" }
            .filter { it.reason.isBlank() }
            .map { "${it.port} is '${it.verdict}' with no recorded reason — inert code must be a documented choice" }

        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    @Test
    fun `every typed binding in a platform module has a registry row`() {
        val registered = registry().map { it.port }.toSet()
        // DataStore binds as DataStore<Preferences>; the registry names it DataStore.
        val bound = boundTypeNames(read(jvmPlatformModule)) +
            boundTypeNames(read(androidPlatformModule))
        val extra = bound.distinct().filterNot { it in registered }

        assertTrue(
            extra.isEmpty(),
            "typed bindings with no registry row: ${extra.sorted()}. " +
                "Add a row to shared/src/jvmTest/resources/platform-seams.tsv and classify it — " +
                "an unclassified binding is how a silent no-op gets added.",
        )
    }

    /**
     * A seam declared `injected` must actually be referenced somewhere.
     *
     * This is the rule that `NotificationPort` would have failed. It was bound on both
     * platforms, satisfied every other rule here — real, present on both sides, not a
     * stub — and no production code ever injected it. Its JVM half shelled out to
     * `atq`/`atrm` and deleted **every** `at` job on the host, including jobs the user
     * had queued outside the app, and armed notifications through a path that fired them
     * immediately when the daemon was missing.
     *
     * `find-unwired-surfaces.py` cannot see this: `orphan-binding` hard-filters to
     * CoreDiModule.kt and reports only bindings nothing injects. So the registry
     * declares each seam's wiring and this test holds the declaration honest.
     */
    @Test
    fun `a seam declared injected is referenced outside its own bindings`() {
        val boundOnlyIn = setOf(
            "com/singularity/todo/core/di/PlatformModule.android.kt",
            "com/singularity/todo/core/di/PlatformModule.jvm.kt",
        )
        val commonRoot = sourceRoot("commonMain.root")
        val bodies = commonRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.toString().substring(commonRoot.path.length).removePrefix("/") in boundOnlyIn }
            .filterNot { it.name.contains("Test") }
            .map { it.readText() }
            .toList()

        val orphans = registry()
            .filterNot { it.isInfra }
            .filter { it.wiring == "injected" }
            .filterNot { seam -> bodies.any { seam.port in it } }
            .map {
                "${it.port} is declared injected but appears in no production file outside " +
                    "its two bindings — declare it `unwired` with a reason, or inject it"
            }

        assertTrue(orphans.isEmpty(), orphans.joinToString("\n"))
    }

    @Test
    fun `an unwired seam carries a written reason`() {
        val offenders = registry()
            .filterNot { it.isInfra }
            .filter { it.wiring == "unwired" }
            .filter { it.reason.isBlank() }
            .map { "${it.port} is unwired with no recorded reason" }
        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    @Test
    fun `the registry covers every seam both platforms share`() {
        val registered = registry().map { it.port }.toSet()
        val jvm = boundTypeNames(read(jvmPlatformModule))
        val android = boundTypeNames(read(androidPlatformModule))
        val shared = (jvm intersect android.toSet()).distinct().filterNot { it in registered }
        assertTrue(shared.isEmpty(), "shared bindings missing from the registry: ${shared.sorted()}")
    }
}
