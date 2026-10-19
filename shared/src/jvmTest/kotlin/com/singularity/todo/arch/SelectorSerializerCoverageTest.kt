package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * Every [com.singularity.todo.feature.agenda.domain.model.Selector] variant must have
 * an entry in the serializer registry.
 *
 * ## What this guards
 *
 * `Selector.TimeOfDay` was added to the sealed hierarchy and to `Selector.typeTag`,
 * and not to `SelectorSerializer`'s registry. The registry's own completeness check
 * did not catch it, because that check compares the registry against a hand-written
 * list of expected variants — and the hand-written list had been left at the old
 * count, so the two agreed with each other and neither agreed with the hierarchy.
 *
 * The failure it produced was silent and total: serializing any agenda section that
 * used a work-day block threw `NoSuchElementException: Key TimeOfDay is missing in
 * the map`, so saving the current agenda as a saved view never wrote anything, never
 * navigated, and never said why.
 *
 * ## Why a source scan and not a runtime check
 *
 * A runtime check over a hand-written list of variants is the same check that just
 * failed. The point here is to read the *hierarchy* and compare it with the
 * *registry*, so the two cannot drift apart again: adding a variant to `Selector.kt`
 * without registering it fails this test even if every hand-written list in the
 * codebase was updated consistently.
 */
@Tag("fast")
class SelectorSerializerCoverageTest {

    private val commonMainRoot: Path = run {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see shared/build.gradle.kts")
        Paths.get(root)
    }

    private fun source(relativePath: String): String {
        val file = commonMainRoot.resolve(relativePath)
        assertTrue(
            Files.exists(file),
            "expected source file at $file — the test's path resolution is broken, " +
                "and a scan of nothing would pass vacuously",
        )
        return file.readText()
    }

    /**
     * The variants declared in the sealed hierarchy, by their `@SerialName`.
     *
     * Line-based on purpose. The declarations are regular — `@SerialName("X")` on one
     * line, the declaration on the next — and a regex that tries to span the two has
     * to survive KDoc, annotations and default values in between, which is where the
     * first version of this scan silently matched one variant out of seventeen.
     */
    private fun declaredVariants(): Set<String> {
        val selectorSource = source("com/singularity/todo/feature/agenda/domain/model/Selector.kt")
        val serialName = Regex("""^\s*@SerialName\("([^"]+)"\)\s*$""")
        val declaration = Regex(
            """^\s*(?:data\s+)?(?:class|object)\s+\w+.*:\s*Selector\s*(?:\{.*)?$""",
        )
        val variants = mutableSetOf<String>()
        val lines = selectorSource.lines()
        lines.forEachIndexed { index, line ->
            val name = serialName.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            // The declaration is the next non-blank line.
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() } ?: return@forEachIndexed
            if (declaration.matches(next)) variants.add(name)
        }
        assertTrue(
            variants.size >= 10,
            "found only ${variants.size} Selector variants in Selector.kt — the scan is " +
                "broken and would pass vacuously",
        )
        return variants
    }

    /** The discriminators the serializer registry has an entry for. */
    private fun registeredVariants(): Set<String> {
        val serializerSource =
            source("com/singularity/todo/feature/agenda/domain/model/SelectorSerializer.kt")
        val entries = Regex("""put\(\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(serializerSource)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            entries.isNotEmpty(),
            "no registry entries found in SelectorSerializer.kt — the scan is broken",
        )
        return entries
    }

    @Test
    fun `every selector variant is registered in the serializer registry`() {
        val declared = declaredVariants()
        val registered = registeredVariants()

        val missing = declared - registered
        assertTrue(
            missing.isEmpty(),
            "Selector variant(s) ${missing.joinToString()} are declared in Selector.kt " +
                "but have no entry in SelectorSerializer's registry. Serializing one " +
                "throws \"Key <name> is missing in the map\" at runtime, and the " +
                "registry's own completeness check cannot see it because it compares " +
                "the registry against a list that was updated the same way.",
        )
    }

    @Test
    fun `the registry has no entry for a variant that does not exist`() {
        val declared = declaredVariants()
        val registered = registeredVariants()

        val orphaned = registered - declared
        assertTrue(
            orphaned.isEmpty(),
            "SelectorSerializer registers ${orphaned.joinToString()}, which Selector.kt " +
                "does not declare — a stale entry hides a renamed or removed variant",
        )
    }
}
