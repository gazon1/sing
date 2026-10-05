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
 * **class that declares `@Test` members** — not once per file. Helper classes that live
 * in a `*Test.kt` file (fakes, harnesses such as `RunVmTest` / `IsolatedComposeTest`)
 * declare no `@Test` and are correctly left untagged.
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
     * Class declarations in [file] that declare `@Test` members but carry no `@Tag`.
     *
     * A deliberately small scanner: it looks for a class header, matches braces to find
     * its body, and reports the class when the body has a `@Test` but the annotation
     * block above the header has no `@Tag`. Adding a real parser here would cost more
     * than the check is worth.
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
            // `find`, not `matchEntire`: a declaration line continues with " {" or " :",
            // and an anchored full match would silently skip every class in the repo.
            val header = CLASS_HEADER.find(line.trim()) ?: return@forEachIndexed
            if (header.range.first != 0) return@forEachIndexed
            val annotationBlock = annotationBlockAbove(lines, index)
            if (annotationBlock.any { tagAnnotation.containsMatchIn(it) }) return@forEachIndexed

            val body = classBody(lines, index)
            if (body.any { TEST_MEMBER.containsMatchIn(it) }) {
                offenders += "$relative:${index + 1} ${header.groupValues[1]}"
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

    /** Lines from [classLineIndex] to the line closing the class body (empty if none). */
    private fun classBody(lines: List<String>, classLineIndex: Int): List<String> {
        val declaration = lines[classLineIndex]
        // A bodyless declaration — `data object Alpha : TestDialog`, a sealed-interface
        // member — has no '{' here. Without this guard the brace counter would run to the
        // end of the file and blame the *next* real class for this file's @Test methods.
        if (!declaration.contains('{')) return emptyList()
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in classLineIndex until lines.size) {
            val line = lines[i]
            body.add(line)
            line.forEach { ch ->
                when (ch) {
                    '{' -> {
                        depth++
                        opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth == 0) break
        }
        return body
    }

    private companion object {
        /**
         * Anchored at the start of a (trimmed) line and **not** anchored at the end: a
         * declaration continues with " {" or " :", so a full match would skip every class
         * and this test would pass vacuously.
         */
        private val CLASS_HEADER = Regex(
            "^(?:public |internal |private |protected |abstract |open |final |sealed |data |value )*" +
                "(?:class|object)\\s+(\\w+)",
        )

        private val ANNOTATION = Regex("@\\w+.*")

        /**
         * Every JUnit annotation that makes a member an actual test, not just `@Test`.
         *
         * Matching `@Test` alone left a real hole: `RecurrenceRuleMapperTest` and
         * `RruleGeneratorTest` declare `@ParameterizedTest` members, so this gate
         * never saw a test in them and reported both as clean while CI — which runs
         * `-Ptest.tags=fast,slow` — silently skipped both classes. The check was
         * "this class has no tests" wearing the costume of "this class has no tag",
         * which is worse than not having the check: it was answering about
         * something else while appearing to answer about this.
         *
         * The set is the JUnit 5 test annotations, plus the `kotlin.test` spelling
         * this project also uses. `@TestFactory` and `@TestTemplate` are included
         * because they produce test runs exactly as `@Test` does. A trailing `\b`
         * is required: without it `@Test` matches the `@TestFactory` prefix, and
         * conversely a bare `@Test` match would also catch `@TestFoo`.
         */
        private val TEST_MEMBER = Regex(
            """^\s*@(?:kotlin\.test\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b""",
        )

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
