package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No JVM-only API in `commonMain`.
 *
 * ## The defect class
 *
 * `commonMain` is compiled for every target the module declares. Importing
 * `java.*`, `javax.*` or `android.*` from it produces code that compiles and passes
 * its tests on the only targets that are ever built today — JVM and Android — and
 * fails the moment a third target exists. The failure is not a compile error the
 * author sees; it is a compile error a future contributor finds, on a platform the
 * author never had.
 *
 * This is not hypothetical here. The tree carried twelve such imports when this gate
 * was written, all of them green:
 *
 * - `core/files/FileChecksum.kt` — `java.security.MessageDigest`, on the attachment
 *   upload path and the backup integrity path. **Fixed** (okio).
 * - `core/sync/ConflictResolver.kt` — the same, and it dies with the checksum removal
 *   in the sync work anyway.
 * - `core/ids/IdGenerator.kt` — `java.util.concurrent.atomic.AtomicInteger`.
 * - `core/ui/DraftMviViewModel.kt` — `java.util.concurrent.ConcurrentHashMap`.
 * - `core/attachments/AttachmentId.kt` — `java.util.UUID`.
 * - `core/log/LogBundleExporter.kt`, `feature/timetracking/…/TimeTrackingSection.kt`,
 *   `feature/tasks/…/LogbookSection.kt` — `java.text.SimpleDateFormat`, `java.util.Date`,
 *   `java.util.Locale`.
 * - `feature/ai/KoogAgentService.kt` — `java.net.HttpURLConnection`, `java.net.URI`.
 * - `feature/ai/tools/AdrTools.kt` — `java.lang.System.getProperty`.
 * - `WhatsNewPrefs.kt`, `DataStoreDraftStore.kt`, `PreferenceWrappers.kt`,
 *   `CalendarSyncSettingsRepositoryImpl.kt` — `java.io.IOException`.
 *
 * ## Why a baseline rather than a clean sweep
 *
 * Twelve call sites is a project-sized change to do at once, in work unrelated to
 * whatever feature is in flight, and getting it half-right is worse than not starting:
 * a partial fix leaves a gate that passes for reasons nobody can reconstruct.
 *
 * So the gate blocks *growth* and records the debt. Every entry below is a specific
 * file with a reason, which is a much more useful starting list than a grep. When a
 * site is fixed, delete its line — the `every_baseline_entry_still_exists` assertion
 * in this class refuses to let a line outlive the problem it describes, so the
 * baseline cannot become a graveyard of fixed bugs.
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

    private data class KnownFinding(val file: String, val importLine: String, val why: String)

    private val baseline = listOf(
        KnownFinding(
            "core/sync/ConflictResolver.kt",
            "import java.security.MessageDigest",
            "row checksum for the row-level conflict gate. Removed together with " +
                "ConflictResolver in the per-field-LWW sync work; until then it is dead weight.",
        ),
        KnownFinding(
            "core/ids/IdGenerator.kt",
            "import java.util.concurrent.atomic.AtomicInteger",
            "monotonic ULID source. Needs an expect/actual atomic or a mutex-free counter.",
        ),
        KnownFinding(
            "core/ui/DraftMviViewModel.kt",
            "import java.util.concurrent.ConcurrentHashMap",
            "in-memory draft store keyed by draft id.",
        ),
        KnownFinding(
            "core/attachments/AttachmentId.kt",
            "import java.util.UUID",
            "attachment id generation; the project already has a ULID generator to use instead.",
        ),
        KnownFinding(
            "core/log/LogBundleExporter.kt",
            "import java.text.SimpleDateFormat",
            "log file names; kotlinx-datetime has a multiplatform formatter.",
        ),
        KnownFinding(
            "feature/timetracking/presentation/components/TimeTrackingSection.kt",
            "import java.text.SimpleDateFormat",
            "duration display; same replacement as the log exporter.",
        ),
        KnownFinding(
            "feature/ai/KoogAgentService.kt",
            "import java.net.HttpURLConnection",
            "local HTTP listener for the agent bridge. A Ktor server or a platform " +
                "expect/actual is the honest fix; okhttp has no server.",
        ),
        KnownFinding(
            "feature/ai/tools/AdrTools.kt",
            "import java.lang.System.getProperty",
            "reads the ADR directory from the environment.",
        ),
        KnownFinding(
            "feature/whatsnew/presentation/WhatsNewPrefs.kt",
            "import java.io.IOException",
            "catching a DataStore read failure. IOException is not available on all targets.",
        ),
        KnownFinding(
            "core/draft/DataStoreDraftStore.kt",
            "import java.io.IOException",
            "same shape as the whats-new prefs above.",
        ),
        KnownFinding(
            "core/settings/PreferenceWrappers.kt",
            "import java.io.IOException",
            "same shape.",
        ),
        KnownFinding(
            "feature/calendar_sync/data/CalendarSyncSettingsRepositoryImpl.kt",
            "import java.io.IOException",
            "same shape.",
        ),
        KnownFinding(
            "core/log/LogBundleExporter.kt",
            "import java.util.Date",
            "log file timestamps; kotlinx-datetime Instant covers it.",
        ),
        KnownFinding(
            "core/log/LogBundleExporter.kt",
            "import java.util.Locale",
            "fixed-locale filename formatting; a literal or a kotlinx-datetime format is enough.",
        ),
        KnownFinding(
            "feature/ai/KoogAgentService.kt",
            "import java.net.URI",
            "parses the bridge request URL; Ktor or a hand-rolled parse avoids the JVM type.",
        ),
        KnownFinding(
            "feature/timetracking/presentation/components/TimeTrackingSection.kt",
            "import java.util.Date",
            "duration display; same replacement as the log exporter.",
        ),
        KnownFinding(
            "feature/timetracking/presentation/components/TimeTrackingSection.kt",
            "import java.util.Locale",
            "duration display; same replacement.",
        ),
        KnownFinding(
            "feature/tasks/presentation/components/detail/LogbookSection.kt",
            "import java.util.Locale",
            "day-name formatting; kotlinx-datetime formats day names without a Locale.",
        ),
    )

    private fun findings(): List<String> {
        val sources = commonMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        return sources.flatMap { file ->
            val relative = file.relativeTo(commonMain).path.replace(File.separatorChar, '/')
            file.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                JVM_IMPORT.find(trimmed)?.let { "$relative: $trimmed" }
            }
        }
    }

    @Test
    fun commonMain_declares_no_new_jvm_only_imports() {
        val allowed = baseline.map { it.importLine }.toSet()
        val offenders = findings().filterNot { line -> line.substringAfter(": ") in allowed }

        if (offenders.isEmpty()) return
        fail(
            "JVM-only import(s) in commonMain — these will not compile on Kotlin/Native, " +
                "JS or Wasm:\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\n\nUse a multiplatform library (okio, kotlinx-datetime, kotlinx-io), " +
                "add an expect/actual, or add the file to BASELINE here with the reason " +
                "it is acceptable — and a follow-up.",
        )
    }

    @Test
    fun every_baseline_entry_still_exists() {
        val present = findings().map { it.substringAfter(": ") }.toSet()
        val stale = baseline.filterNot { it.importLine in present }

        assertTrue(
            stale.isEmpty(),
            "baseline entries whose import is gone — delete the line:\n" +
                stale.joinToString("\n") { "  - ${it.file}: ${it.importLine}" } +
                "\n\nA baseline that outlives its problem is a hole with paperwork.",
        )
    }

    @Test
    fun every_baseline_entry_states_why() {
        assertTrue(
            baseline.all { it.why.isNotBlank() && it.file.isNotBlank() },
            "a baseline entry without a file and a reason is not a decision",
        )
    }

    private companion object {
        /**
         * Matches a JVM-only import at the head of a line.
         *
         * The `^import` anchor after trimming is what keeps commented-out imports and
         * KDoc lines out: they trim to `// import java…` and `* import java…`, which do
         * not start with `import`. Same reasoning as the other scanners in this package.
         */
        val JVM_IMPORT = Regex("""^import\s+(java|javax|android)\.[\w.]+""")
    }
}
