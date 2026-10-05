package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every source set a module declares must be one detekt is told to scan.
 *
 * ## The defect this catches
 *
 * `androidApp/build.gradle.kts` sets `detekt.source` to a literal list of
 * directories. `src/debug` was not on it, so `DebugSeedActivity.kt` was never
 * linted — and detekt reported nothing about that, because detekt scans what it
 * is told to scan and says nothing at all about what it was not told to scan.
 *
 * That is the dangerous shape. Every other gate in this repository reports a
 * finding; this one reported an absence, which no reader of a green report can
 * distinguish from "there is nothing wrong".
 *
 * `DebugSeedActivity.kt` had 15 findings waiting in it, four of which are now
 * baselined with written reasons and nine of which were real formatting defects.
 * Three `Clock.System` calls had never been seen by `NoDirectClockSystem`. The
 * whole file was a place where a defect could accumulate without any signal.
 *
 * ## The second defect
 *
 * The same list once named `src/androidAndroidTest/kotlin`, a directory that
 * does not exist. detekt ignored the entry without complaint, which is the
 * normal outcome of a typo in a configuration list — so the list looked
 * complete while covering a path that was never scanned. Hence the existence
 * check as well as the coverage check.
 *
 * @see openspec/changes/androidapp-debug-lint-policy — the policy decision
 */
@Tag("fast")
class DetektSourceSetsAreAllScannedTest {

    private val repoRoot: File = File(
        System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
    ).parentFile.parentFile.parentFile.parentFile

    /**
     * Module -> the source sets it actually has.
     *
     * Read from the filesystem rather than hard-coded, because a hard-coded list
     * would be a second list to forget to update — which is the same mistake in a
     * different file. A directory counts as a source set when it holds a
     * `kotlin` subdirectory with sources in it.
     */
    private val sourceSetsOnDisk: Map<String, Set<String>> by lazy {
        // `pro` is in this list from 2026-10-05. It is an Android library with one
        // source set, added to the open-core catalogue the same day it was created; leaving
        // it out would make it the only module in the repository whose source sets no test
        // checks, which is the exact shape of hole this class exists to close.
        listOf("shared", "androidApp", "desktopApp", "mcp-server", "pro").associateWith { module ->
            File(repoRoot, module)
                .resolve("src")
                .takeIf { it.isDirectory }
                ?.listFiles()
                .orEmpty()
                .filter { File(it, "kotlin").isDirectory }
                .map { "src/${it.name}/kotlin" }
                .filter { rel ->
                    File(repoRoot, "$module/$rel").walkTopDown()
                        .any { f -> f.isFile && f.extension == "kt" }
                }
                .toSortedSet()
        }
    }

    private fun detektSourceList(module: String): List<String>? {
        val buildFile = File(repoRoot, "$module/build.gradle.kts")
        if (!buildFile.isFile) return null
        val text = buildFile.readText()
        val block = Regex(
            """source\.setFrom\(([^)]*)\)""",
        ).find(text)?.groupValues?.get(1) ?: return null
        return Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun every_source_set_a_module_declares_is_scanned_by_detekt() {
        val unscanned = mutableListOf<String>()

        sourceSetsOnDisk.forEach { (module, sets) ->
            val declared = detektSourceList(module) ?: return@forEach
            sets.filterNot { it in declared }.forEach { set ->
                unscanned += "$module: $set exists and detekt is not told to scan it"
            }
        }

        assertEquals(
            unscanned,
            emptyList(),
            "these source sets contain Kotlin but are absent from their module's " +
                "detekt.source. detekt reports nothing about a directory it was not " +
                "given, so a defect in one of these is invisible rather than absent:",
        )
    }

    @Test
    fun every_entry_in_detekt_source_exists() {
        val missing = mutableListOf<String>()

        sourceSetsOnDisk.keys.forEach { module ->
            detektSourceList(module)?.forEach { declared ->
                if (!File(repoRoot, "$module/$declared").isDirectory) {
                    missing += "$module: detekt.source names $declared, which does not exist"
                }
            }
        }

        assertEquals(
            missing,
            emptyList(),
            "detekt.source names directories that do not exist. detekt ignores an " +
                "unreadable path without complaining, so the list looks complete while " +
                "covering nothing — that is how `src/androidAndroidTest/kotlin` " +
                "survived in it:",
        )
    }

    @Test
    fun the_scan_is_not_vacuous() {
        """Every module this test claims to check must actually declare a list.

        A test that passes because it read nothing is the failure mode this whole
        class exists to prevent, applied to itself.
        """
        val modulesWithAList = sourceSetsOnDisk.keys.filter { detektSourceList(it) != null }
        assertTrue(
            modulesWithAList.size >= 3,
            "only found a detekt.source list in $modulesWithAList — the regex or the " +
                "assumption about where the list lives has drifted, and this test " +
                "would pass by checking nothing",
        )
        assertTrue(
            sourceSetsOnDisk.values.any { it.size > 1 },
            "no module reported more than one source set, so the coverage assertion " +
                "above has nothing to compare against",
        )
    }
}
