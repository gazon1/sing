package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The E2E build loads the onboarding override, and only in the E2E build.
 *
 * The onboarding record is what makes an Android E2E run start after the spotlight
 * tour: the tour's scrim swallows every tap and prunes the agenda out of the
 * accessibility tree, so a flow that starts on a fresh install cannot see the row it
 * just seeded (issue #2819). The binding that fixes that lives in
 * `shared/src/androidMain` and is exercised by `TestOnboardingModuleTest` — but that
 * test proves the *module* works, not that the application *loads* it. Drop
 * `testOnboardingModule()` from `extraKoinModules()` and every test still passes
 * while the E2E build silently returns to its broken first-run state.
 *
 * ## Why the wiring is read, not called
 *
 * The composition root is `SingularityApp`, in `:androidApp`. That module has no
 * unit-test lane in CI — `:androidApp:test` is NO-SOURCE and
 * `:androidApp:connectedDebugAndroidTest` is run by no job — so the composition
 * cannot be instantiated in a test that CI executes. The alternative, a unit test in
 * `:androidApp`, would need a test source set, `testOptions.unitTests.isReturnDefaultValues`,
 * and a CI job that builds with `-PTEST_AUTH_BYPASS=true`, because the assertion is
 * about a build flag.
 *
 * Reading the composition root is what the other wiring gates in this package do
 * (`KoinEntryPointIsWhereItSaysTest`, `ScreenStateCollectionsTest`), and it is what
 * catches the regression this rule exists for: the module stops being loaded, or it
 * stops being loaded only in the E2E build.
 *
 * The scan is comment-stripped, so the KDoc that names the module cannot satisfy it.
 */
@Tag("fast")
class E2eOnboardingWiringTest {
    @Test
    fun `the E2E build loads the onboarding module`() {
        val body = bodyOfExtraKoinModules(appSource())

        assertTrue(
            body.contains("testOnboardingModule"),
            "SingularityApp.extraKoinModules() must load the onboarding override. " +
                "Without it the E2E build starts as a user who has never seen the " +
                "spotlight tour, and the tour's scrim hides the agenda from every flow.",
        )
        assertTrue(
            body.contains("TEST_AUTH_BYPASS"),
            "the onboarding override belongs to the E2E build only. Without the " +
                "TEST_AUTH_BYPASS condition it would also change the release build.",
        )
        assertTrue(
            body.indexOf("TEST_AUTH_BYPASS") < body.indexOf("testOnboardingModule"),
            "the onboarding override must be loaded under the TEST_AUTH_BYPASS " +
                "condition, not unconditionally.",
        )
    }

    /**
     * A guard whose only observed result is green is not known to work. The control
     * proves the body is really read out of the source: a body that does not name the
     * module fails the rule, and one that does passes it.
     */
    @Test
    fun `positive control - the rule reads the composition root's body`() {
        val without =
            bodyOfExtraKoinModules(
                """
                open fun extraKoinModules(): List<Module> {
                    if (BuildConfig.TEST_AUTH_BYPASS) {
                        return listOf(testAuthModule())
                    }
                    return emptyList()
                }
                """.trimIndent(),
            )
        val with =
            bodyOfExtraKoinModules(
                """
                open fun extraKoinModules(): List<Module> {
                    if (BuildConfig.TEST_AUTH_BYPASS) {
                        return listOf(testAuthModule(), testOnboardingModule())
                    }
                    return emptyList()
                }
                """.trimIndent(),
            )

        assertFalse(without.contains("testOnboardingModule"))
        assertTrue(with.contains("testOnboardingModule"))
    }

    /** The body of `extraKoinModules()` in [source], comments stripped. */
    private fun bodyOfExtraKoinModules(source: String): String {
        val stripped = KotlinText.stripComments(source)
        val declaration = stripped.indexOf("fun extraKoinModules")
        assertTrue(declaration >= 0, "the source must declare extraKoinModules()")
        val open = stripped.indexOf('{', declaration)
        assertTrue(open >= 0, "extraKoinModules() must have a body")
        val close = KotlinText.matchingBrace(stripped, open)
        assertTrue(close > open, "the body of extraKoinModules() must balance")
        return stripped.substring(open + 1, close)
    }

    /** The composition root of the Android application. */
    private fun appSource(): String {
        val repoRoot =
            File(
                System.getProperty("commonMain.root")
                    ?: error("commonMain.root is not set — see the jvmTest task config"),
            ).parentFile.parentFile.parentFile.parentFile
        val file = File(repoRoot, "androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt")
        assertTrue(file.isFile, "the composition root must be at ${file.path}")
        return file.readText()
    }
}
