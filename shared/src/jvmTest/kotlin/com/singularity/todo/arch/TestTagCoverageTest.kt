package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every test class carries a `@Tag`, so `-Ptest.tags=…` can never skip it silently.
 *
 * ## The defect this catches
 *
 * `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` translate `-Ptest.tags`
 * into JUnit's `includeTags(...)`, and CI runs `-Ptest.tags=fast,slow`. JUnit matches
 * tags **per class**: a class with no `@Tag` is simply not selected. The failure is
 * silent — the task still prints `BUILD SUCCESSFUL`.
 *
 * That is not hypothetical. With 16 of 218 test classes tagged, CI executed 16 classes
 * in `shared` and **zero** in `desktopApp`, so no navigation test
 * (`NavigationPolicyTest`, `ScreenFamilyTest`, `Nav3StateReselectTest`,
 * `NavSavedStateConfigTest`) and no desktop flow test had ever run in CI while the
 * pipeline stayed green.
 *
 * ## Why a file-level check is not enough
 *
 * Tags are matched per class, so a file holding two test classes where only one is
 * tagged still loses the other. This test therefore requires the annotation on every
 * **class that declares a member JUnit executes** — not once per file. That set is
 * [RunnableTestMember]'s, and it is deliberately wider than `@Test`: matching
 * `@Test` alone reported `RecurrenceRuleMapperTest` and `RruleGeneratorTest` as
 * clean while CI, which runs `-Ptest.tags=fast,slow`, skipped both. Helper classes
 * that live in a `*Test.kt` file (fakes, harnesses such as `RunVmTest` /
 * `IsolatedComposeTest`) declare no test member and are correctly left untagged.
 *
 * ## Adding a test
 *
 * Tag it in the same change: `@Tag("fast")` for pure logic and source scans, `@Tag("slow")`
 * for real I/O — Room/SQLite, the filesystem, zip/backup codecs, Compose UI (including
 * desktop's `runDesktopAppTest`, which mounts the whole `App()`), Robolectric, or real
 * `delay`. The convention is ADR `2026-09-25-test-suite-tag-defaults`.
 */
@Tag("fast")
class TestTagCoverageTest {

    /**
     * The repository root. `commonMain.root` points at
     * `shared/src/commonMain/kotlin`; three levels up is `shared/`, one more is the
     * root that also holds `desktopApp/` and `mcp-server/`.
     */
    private val repoRoot: File = File(
        System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
    ).parentFile.parentFile.parentFile.parentFile

    /** Source sets whose Gradle task translates `-Ptest.tags` into a JUnit tag filter. */
    private val testSourceDirs = listOf(
        "shared/src/commonTest",
        "shared/src/jvmTest",
        // Added 2026-10-07. `testAndroidHostTest` applies the same `includeTags` filter
        // as the other two, and `AndroidSyncDiGraphResolutionTest` shipped without a
        // `@Tag` — so it was excluded from `-Ptest.tags=fast,slow` and from every CI
        // run that uses it, while `:shared:testAndroidHostTest` stayed green over 171
        // classes it had not actually exercised. A source set that applies the filter
        // belongs in this list whether or not it holds tests yet: the gap and the rule
        // arrived on the same day, and only one of them was recorded.
        "shared/src/androidHostTest",
        "desktopApp/src/jvmTest",
        "mcp-server/src/test",
    )

    @Test
    fun every_test_class_declares_a_tag() {
        val untagged = mutableListOf<String>()

        testSourceDirs.forEach { dir ->
            val root = File(repoRoot, dir)
            assertTrue(root.isDirectory, "missing test source dir: ${root.path}")
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Test.kt") }
                .forEach { file ->
                    untagged += untaggedClassesIn(file)
                }
        }

        assertTrue(
            untagged.isEmpty(),
            "test classes without @Tag — CI runs -Ptest.tags=fast,slow, so an untagged " +
                "class is silently excluded from every run:\n" +
                untagged.joinToString("\n") { "  $it" },
        )
    }

    /**
     * Class declarations in [file] that declare test members but carry no `@Tag`.
     *
     * A deliberately small scanner: it looks for a class header, matches braces to find
     * its body, and reports the class when the body has a test member but the annotation
     * block above the header has no `@Tag`. Adding a real parser here would cost more
     * than the check is worth.
     *
     * "Has a test member" is [RunnableTestMember]'s job, not this test's — the same
     * question `infra/kiwi/sync.py` asks, which is why it lives in one object that
     * both are tested against (`config/test-fixtures/runnable-test-members.txt`).
     */
    private fun untaggedClassesIn(file: File): List<String> {
        val lines = file.readText().split("\n")
        val relative = file.relativeTo(repoRoot).path
        val offenders = mutableListOf<String>()

        // An aliased `import … Tag as X` means this file writes its tags as `@X(`.
        // Line by line, not over a joined string: the pattern is anchored at both ends
        // on purpose (so `@Tag` inside prose is not mistaken for an import), and a
        // joined string has one start and one end, not one per line.
        val tagAnnotation = lines.asSequence()
            .mapNotNull { TAG_ALIAS_IMPORT.find(it) }
            .firstOrNull()
            ?.let { Regex("""@${it.groupValues[1]}\(""") }
            ?: TAG_ANNOTATION

        lines.forEachIndexed { index, line ->
            val name = RunnableTestMember.classHeaderAt(lines, index) ?: return@forEachIndexed
            val annotationBlock = annotationBlockAbove(lines, index)
            if (annotationBlock.any { tagAnnotation.containsMatchIn(it) }) return@forEachIndexed

            if (RunnableTestMember.classHasTestMember(lines, index)) {
                offenders += "$relative:${index + 1} $name"
            }
        }
        return offenders
    }

    /** The contiguous `@…` lines immediately above [classLineIndex]. */
    private fun annotationBlockAbove(lines: List<String>, classLineIndex: Int): List<String> {
        val block = mutableListOf<String>()
        var i = classLineIndex - 1
        while (i >= 0 && ANNOTATION.matchEntire(lines[i].trim()) != null) {
            block.add(lines[i])
            i--
        }
        return block
    }

    private companion object {
        private val ANNOTATION = Regex("@\\w+.*")

        /**
         * Both the imported `@Tag("…")` and the fully-qualified
         * `@org.junit.jupiter.api.Tag("…")` form. Three test files already import the
         * domain entity `com.singularity.todo.feature.tags.Tag`, so the JUnit annotation
         * has to be written out in full there.
         */
        private val TAG_ANNOTATION = Regex("""@(?:org\.junit\.jupiter\.api\.)?Tag\(""")

        /**
         * `import org.junit.jupiter.api.Tag as JUnitTag` — the JUnit 5 migration on
         * `main` (014d1ca0) needed the alias wherever a file already imports
         * `kotlin.test.Test`, because both libraries export `Test`. Such a class carries
         * `@JUnitTag("slow")` and is tagged; matching the annotation by its written name
         * reported it as untagged, which is the one answer this test must never give
         * about a class that has a tag.
         *
         * The alias is therefore resolved from the import rather than accepted blindly.
         * `@Anything(` is not a tag, and widening the regex to "any annotation ending in
         * Tag" would let `@Suppress`-style lookalikes pass.
         */
        private val TAG_ALIAS_IMPORT = Regex(
            """^\s*import\s+org\.junit\.jupiter\.api\.Tag\s+as\s+(\w+)\s*$""",
        )
    }
}
