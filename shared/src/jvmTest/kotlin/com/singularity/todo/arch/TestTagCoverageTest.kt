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
 * into JUnit's `includeTags(...)`, and CI selects its population explicitly —
 * `fast` on a pull request, `fast,slow` on the nightly. JUnit matches
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
 * clean while CI, which selects tagged classes only, skipped both. Helper classes
 * that live in a `*Test.kt` file (fakes, harnesses such as `RunVmTest` /
 * `IsolatedComposeTest`) declare no test member and are correctly left untagged.
 *
 * ## Adding a test
 *
 * Tag it in the same change: `@Tag("fast")` for pure logic and source scans, `@Tag("slow")`
 * for real I/O — Room/SQLite, the filesystem, zip/backup codecs, Compose UI (including
 * desktop's `runDesktopAppTest`, which mounts the whole `App()`), Robolectric, or real
 * `delay`. The convention is ADR `2026-09-25-test-suite-tag-defaults`.
 *
 * ## Annotation is not execution — the three questions, and who answers each
 *
 * A `@Tag` only means the class is *selectable*; three separate defects can
 * still keep it from running, and this class plus the run gates answer them in
 * order:
 *
 * 1. **Is the class tagged, with a value CI selects, on an engine that reads
 *    tags?** — this class. Presence alone was not enough twice over: a value
 *    outside the populations CI selects is as invisible as no tag at
 *    all, and a JUnit 4 class runs on the Vintage engine, where Jupiter's
 *    `@Tag` does not exist as a filter (`shared/build.gradle.kts` documents
 *    the `testAndroidHostTest` exemption those two checks protect).
 * 2. **Did the run discover anything?** — `failOnNoDiscoveredTests` in each
 *    module's build file. Catches a filter that selects zero classes, not one
 *    class dropped from a green batch.
 * 3. **Did each declared `fast` class produce a JUnit suite?** —
 *    `scripts/check-test-runs.py`, after the run, against the XML. This is the
 *    execution cross-reference: it fails when a source-declared `@Tag("fast")`
 *    class has no `TEST-*.xml`, whatever the reason (wrong engine, wrong value,
 *    a condition that excluded it).
 *
 * Source-level checks answer in seconds on a local run and name the file;
 * the execution check answers for the run that actually happened. Neither
 * replaces the other: check-test-runs only sees `fast` classes (checking
 * `slow` would fail every default local run, which excludes it by design), so
 * the value check here is what holds the `slow` half.
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
        // `shared/src/androidHostTest` was here from 2026-10-07 to 2026-10-08, on the
        // rationale that it "applies the same `includeTags` filter as the other two".
        // That stopped being true the same day: `testAndroidHostTest` was exempted from
        // the filter, because a Robolectric class is JUnit4 and JUnit4 has no `@Tag`, so
        // the filter could only ever exclude it. Requiring a tag in a source set where
        // tags cannot work is not a check — it is a rule that can only be satisfied by a
        // test that does not exist. `AndroidNotifierTest` is the proof: untagged, JUnit4,
        // runs, and is held in place by a floor rather than by a tag.
        //
        // Put this back if the exemption is ever removed — at which point the filter is
        // back, and the requirement is back, and they have to agree.
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

    @Test
    fun every_tag_value_is_selected_by_ci() {
        val offenders = mutableListOf<String>()

        testSourceDirs.forEach { dir ->
            File(repoRoot, dir).walkTopDown()
                .filter { it.isFile && it.name.endsWith("Test.kt") }
                .forEach { file -> offenders += unselectableTagValuesIn(file) }
        }

        assertTrue(
            offenders.isEmpty(),
            "test classes whose @Tag value CI's -Ptest.tags=fast,slow never selects — " +
                "the annotation exists, so every source-level check passes, and the class " +
                "still does not run once in CI. fast and slow are the whole vocabulary; " +
                "a typo ('fasst') or a third value ('medium') is indistinguishable from " +
                "no tag at all:\n" +
                offenders.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun tag_filtered_source_sets_run_on_an_engine_that_honours_tags() {
        val offenders = mutableListOf<String>()

        testSourceDirs.forEach { dir ->
            File(repoRoot, dir).walkTopDown()
                .filter { it.isFile && it.name.endsWith("Test.kt") }
                .forEach { file -> offenders += junit4OffendersIn(file) }
        }

        assertTrue(
            offenders.isEmpty(),
            "files in a tag-filtered source set that use the JUnit 4 API — those classes " +
                "run on the Vintage engine, and Vintage does not map Jupiter's @Tag onto " +
                "platform tags, so includeTags excludes them exactly like an untagged " +
                "class. This is the defect the testAndroidHostTest exemption documents: " +
                "the only JUnit 4 source set allowed is the one exempted from the filter. " +
                "Port the class to Jupiter (kotlin.test/@Tag work as-is) or move it to " +
                "androidHostTest with its own floor:\n" +
                offenders.joinToString("\n") { "  $it" },
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
        // Two views of the same file. The raw lines carry the tag — its value
        // lives inside a string literal, which the code view blanks. The code
        // view carries the class and its body, which the raw lines get wrong
        // whenever a literal or a comment holds a brace.
        val code = RunnableTestMember.codeLines(lines)
        val relative = file.relativeTo(repoRoot).path
        val tagAnnotation = tagAnnotationRegex(lines)
        val offenders = mutableListOf<String>()

        lines.forEachIndexed { index, line ->
            val name = RunnableTestMember.classHeaderAt(code, index) ?: return@forEachIndexed
            val annotationBlock = annotationBlockAbove(lines, index)
            if (annotationBlock.any { tagAnnotation.containsMatchIn(it) }) return@forEachIndexed

            if (RunnableTestMember.classHasTestMember(code, index)) {
                offenders += "$relative:${index + 1} $name"
            }
        }
        return offenders
    }

    /**
     * Tagged classes in [file] whose tag values do not hold together.
     *
     * The hole this closes: `every_test_class_declares_a_tag` matches the annotation,
     * not its argument, and JUnit's `includeTags("fast","slow")` matches the argument.
     * `@Tag("fasst")` satisfies the first and the second never selects — the class is
     * absent from every CI run while the gate that exists to prevent exactly that
     * stays green. A non-literal argument (`@Tag(FAST)`) is reported too: the value
     * cannot be verified statically, and every tag in this repo is a literal.
     *
     * A class may carry more than one tag: the primary one (`fast` or `slow`) and a
     * sub-tag naming why it is slow. Two rules hold for that: the primary tag must be
     * there — a class that carried only `slow:konsist` would be in no CI run at all —
     * and every sub-tag must come from [SLOW_SUBTAGS], so that a typo cannot quietly
     * take a class out of the run it was meant for.
     */
    private fun unselectableTagValuesIn(file: File): List<String> {
        val lines = file.readText().split("\n")
        val code = RunnableTestMember.codeLines(lines)
        val relative = file.relativeTo(repoRoot).path
        val tagAnnotation = tagAnnotationRegex(lines)
        val tagValue = tagValueRegex(lines)
        val offenders = mutableListOf<String>()

        lines.forEachIndexed { index, line ->
            val name = RunnableTestMember.classHeaderAt(code, index) ?: return@forEachIndexed
            val annotationBlock = annotationBlockAbove(lines, index)
            val tagLines = annotationBlock.filter { tagAnnotation.containsMatchIn(it) }
            if (tagLines.isEmpty()) return@forEachIndexed

            val values = tagLines.map { tagValue.find(it)?.groupValues?.get(1) }
            if (values.any { it == null }) {
                offenders += "$relative:${index + 1} $name — @Tag argument is not a string literal"
                return@forEachIndexed
            }
            val present = values.filterNotNull()
            val primary = present.filter { it in CI_SELECTABLE_TAGS }
            val subTags = present.filter { it !in CI_SELECTABLE_TAGS }

            when {
                primary.isEmpty() ->
                    offenders += "$relative:${index + 1} $name — no @Tag(\"fast\") or @Tag(\"slow\"): " +
                        "CI selects on those, so the class would be in no run"

                subTags.any { it !in SLOW_SUBTAGS } ->
                    offenders += "$relative:${index + 1} $name — " +
                        subTags.filter { it !in SLOW_SUBTAGS }
                            .joinToString { "@Tag(\"$it\")" } +
                        " is not a known sub-tag"

                subTags.isNotEmpty() && primary.size > 1 ->
                    offenders += "$relative:${index + 1} $name — more than one primary tag: " +
                        primary.joinToString { "@Tag(\"$it\")" }
            }
        }
        return offenders
    }

    /**
     * True when [file] uses the JUnit 4 API while living in a tag-filtered source set.
     *
     * Reported at file granularity on purpose: the engine is chosen per source set and
     * per file's imports, not per class, and a file that mixes JUnit 4 imports with a
     * Jupiter class is already broken in a way this message should surface. The
     * `hasRunnableTest` gate keeps support files (assertion helpers that import
     * `org.junit.Assert`) out of the finding — only a file that declares a runnable
     * test is claiming an engine.
     */
    private fun junit4OffendersIn(file: File): List<String> {
        val text = file.readText()
        if (!RunnableTestMember.hasRunnableTest(text)) return emptyList()
        val lines = text.split("\n")
        val relative = file.relativeTo(repoRoot).path
        val markers = lines.filter {
            JUNIT4_IMPORT.containsMatchIn(it) || JUNIT4_RUN_WITH.containsMatchIn(it)
        }
        if (markers.isEmpty()) return emptyList()
        return listOf("$relative — ${markers.joinToString("; ") { it.trim() }}")
    }

    /**
     * The `@Tag(`-shaped regex this file's tests should match with: aliased when the
     * file imports the annotation under a name (`import … Tag as JUnitTag`), the
     * plain or fully-qualified spelling otherwise.
     */
    private fun tagAnnotationRegex(lines: List<String>): Regex =
        aliasTagName(lines)?.let { Regex("""@${Regex.escape(it)}\(""") } ?: TAG_ANNOTATION

    /**
     * Same resolution as [tagAnnotationRegex], with a capture group for the value.
     * Kept as a second regex rather than one parameterised capture: the detection
     * sites want "is a tag here", not "what is its value", and a capture group they
     * ignore is a standing invitation to parse with the wrong one.
     */
    private fun tagValueRegex(lines: List<String>): Regex =
        aliasTagName(lines)?.let { Regex("""@${Regex.escape(it)}\("([^"]*)"\)""") }
            ?: TAG_VALUE_ANNOTATION

    /**
     * An aliased `import … Tag as X` means this file writes its tags as `@X(`.
     * Line by line, not over a joined string: the pattern is anchored at both ends
     * on purpose (so `@Tag` inside prose is not mistaken for an import), and a
     * joined string has one start and one end, not one per line.
     */
    private fun aliasTagName(lines: List<String>): String? = lines.asSequence()
        .mapNotNull { TAG_ALIAS_IMPORT.find(it) }
        .firstOrNull()
        ?.groupValues?.get(1)

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
         * The only two class-level tag values CI's `-Ptest.tags=fast,slow` selects.
         * `all` is a selection sentinel (`-Ptest.tags=all` disables filtering), not a
         * class tag; adding a third value is a change to ci.yml first and here second.
         */
        private val CI_SELECTABLE_TAGS = setOf("fast", "slow")

        /**
         * The sub-tags a slow class may carry, naming *why* it is slow.
         *
         * A sub-tag never replaces the primary tag: CI selects on `fast`/`slow`, so a
         * class that carried only `slow:konsist` would be absent from every CI run
         * while this gate stayed green. The sub-tag is a second annotation, next to
         * the primary one, and it names the category so that
         * `-Ptest.tags=slow:konsist` can run one part of the slow half.
         *
         * The list is closed on purpose. A free-text sub-tag is a typo waiting to
         * happen, and a typo in a tag is invisible: the class is absent from every
         * run the typo was meant for, and every gate that checks the tag still
         * passes.
         */
        private val SLOW_SUBTAGS = setOf(
            "slow:konsist",
            "slow:migration",
            "slow:compose",
            "slow:data",
            "slow:platform",
        )

        /**
         * Both the imported `@Tag("…")` and the fully-qualified
         * `@org.junit.jupiter.api.Tag("…")` form. Three test files already import the
         * domain entity `com.singularity.todo.feature.tags.Tag`, so the JUnit annotation
         * has to be written out in full there.
         */
        private val TAG_ANNOTATION = Regex("""@(?:org\.junit\.jupiter\.api\.)?Tag\(""")

        /** [TAG_ANNOTATION] with a capture group around the string-literal value. */
        private val TAG_VALUE_ANNOTATION =
            Regex("""@(?:org\.junit\.jupiter\.api\.)?Tag\("([^"]*)"\)""")

        /**
         * JUnit 4 API usage: any `org.junit.*` import that is not Jupiter or the
         * Platform. Vintage — the engine that runs these — has no tag support, so a
         * `@Tag` on such a class is decoration: `includeTags` never selects it.
         */
        private val JUNIT4_IMPORT =
            Regex("""^\s*import\s+org\.junit\.(?!jupiter\.|platform\.)\S+""")

        /** `@RunWith(...)` — the class is explicitly placed on a JUnit 4 runner. */
        private val JUNIT4_RUN_WITH = Regex("""^\s*@(?:org\.junit\.runner\.)?RunWith\(""")

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
