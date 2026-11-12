package com.singularity.todo.core.ui

import java.io.File
import kotlin.test.assertTrue

/**
 * The text a Compose resource bundle gives a key, in one language.
 *
 * A test that says "this label is translated" is a claim about the **bundle**:
 * the composable can only be as translated as the resources behind it, and the
 * bundle is a file the build feeds into the resource cache. Reading it says the
 * same thing without touching the process locale — setting the default locale
 * would be a global, order-dependent change to every test in the module.
 *
 * One owner for that read: two tests that each parse the XML separately are two
 * parsers that drift apart.
 *
 * @param language The bundle directory under `composeResources` — `values`,
 *   `values-ru`, and so on.
 * @param key The resource key, e.g. `agenda_save_current`.
 */
fun bundleString(
    language: String,
    key: String,
): String {
    val bundle = File(repositoryRoot(), "shared/src/commonMain/composeResources/$language/strings.xml")
    assertTrue(bundle.isFile, "expected the resource bundle at ${bundle.path}")
    return Regex("""<string\s+name="$key"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
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
fun repositoryRoot(): File =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("could not find the repository root from ${System.getProperty("user.dir")}")
