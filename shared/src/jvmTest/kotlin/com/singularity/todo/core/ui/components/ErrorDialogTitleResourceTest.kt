package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The error dialog's heading is a translated string, and not a literal.
 *
 * ## What this pins, and why it is not a Compose test
 *
 * [ErrorResultDialog] takes its title from `Res.string.error`, so what a device
 * shows is whatever the bundle for its language holds. "A Russian device shows a
 * Russian heading" is therefore a claim about the **bundle**: the composable can
 * only be as translated as the resources behind it.
 *
 * Asserting it by rendering would mean setting the process's default locale and
 * hoping the resource cache had not already been filled by another test — a
 * global, order-dependent change to every test in the module. Reading the two
 * files the build feeds into that cache says the same thing without touching the
 * process, and it fails on exactly the mistakes worth catching: `values-ru`
 * missing the key, or carrying the English word.
 *
 * ## What it does not check
 *
 * That every English key has a Russian one. That is a property of the whole
 * bundle rather than of this dialog, and a rule for all of them belongs in a
 * gate, not in a test that names one key.
 */
@Tag("fast")
class ErrorDialogTitleResourceTest {
    @Test
    fun `the error heading is defined in the default bundle`() {
        assertTrue(
            headingIn("values").isNotEmpty(),
            "values/strings.xml must define the `error` heading the error dialog wears",
        )
    }

    @Test
    fun `the russian heading is russian, not the english word`() {
        val english = headingIn("values")
        val russian = headingIn("values-ru")

        assertTrue(russian.isNotEmpty(), "values-ru/strings.xml must translate the `error` heading")
        assertNotEquals(
            english,
            russian,
            "the Russian bundle carries the English `error` heading, so a Russian device reads English",
        )
        assertTrue(
            russian.any { it in '\u0410'..'\u044f' || it == '\u0401' || it == '\u0451' },
            "the Russian `error` heading '$russian' has no Cyrillic in it",
        )
    }

    /** The heading's text in one language, or `""` when the bundle does not define it. */
    private fun headingIn(language: String): String {
        val bundle =
            File(repositoryRoot(), "shared/src/commonMain/composeResources/$language/strings.xml")
        assertTrue(bundle.isFile, "expected the resource bundle at ${bundle.path}")
        return Regex("""<string\s+name="error"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(bundle.readText())
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
    }

    /**
     * The checkout root, found by walking up from the working directory.
     *
     * The JVM does not start at a fixed depth — Gradle starts it in the module's own
     * directory — so the marker is a file only the root has.
     */
    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
}
