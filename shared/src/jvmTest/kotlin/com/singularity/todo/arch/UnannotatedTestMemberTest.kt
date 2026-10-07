package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every non-private member of a test class carries an annotation.
 *
 * ## The defect
 *
 * A function written with a test's name, in a test class, that no annotation wires
 * to JUnit is not a test. It is a line of code that reads like a check, compiles,
 * and never runs — and the suite stays green, because a green suite means "every
 * selected test passed", not "every check was selected".
 *
 * This is not theoretical. PR #223 fixed two of them in `SingularityCatalogTest`:
 * `aDueDateWithNeitherPathNorValueIsRejected` and `componentNamesAreUnique`, both
 * complete assertions with no `@Test`. Eight tests ran; the author had written ten.
 * Everything about the defect is invisible from the outside, which is why it
 * survived review and why the fixture in `src/jvmTest/fixtures/` exists.
 *
 * ## Why this is a Konsist scan and not a regex
 *
 * The first attempt at this gate was a line scanner, and it failed its own control
 * before it ever ran on the repository. Its positive control was the pre-#223 file,
 * which contains **two** unannotated members; the scanner reported **one**. The
 * second was inside a class body whose brace count had been thrown off by a
 * multi-line raw-string JSON fixture, so the body appeared to end four lines early
 * and the member was never examined.
 *
 * That is worth recording because it is the second time this repository has paid
 * for a text scanner that counts braces — `ClassBodyScannerAgreementTest` already
 * measures 97 test-tree lines carrying an unbalanced brace inside a literal, and
 * today the naive and string-aware scanners happen to agree. Here they did not
 * agree, and the disagreement was silent.
 *
 * Konsist was already a test dependency (`com.lemonappdev:konsist` 0.17.3, used by
 * [DiFacadeTest] and [ArchitectureTest]), so an AST is available at no new cost and
 * with none of the blind spots: nesting, visibility, annotations and line numbers
 * come from PSI rather than from brace arithmetic. A gate that needs a new build
 * dependency is a gate that gets removed the first time that dependency is
 * inconvenient; this one needed nothing new.
 *
 * ## The rule
 *
 * Inside a class whose name ends in `Test`, a member `fun` must either state its
 * intent with an annotation or be a declaration that cannot be a test on its own —
 * `private`, `abstract`, or `override`.
 *
 * - **Annotated** — the declaration states its intent, whatever that intent is.
 *   `@Test`, `@ParameterizedTest`, and `@BeforeEach` are all fine: an earlier draft
 *   of this rule demanded a *test* annotation and flagged all 32 lifecycle callbacks
 *   in the repository, which is a rule that teaches people to suppress it.
 * - **`private`** — a helper by definition, out of scope.
 * - **`abstract` / `override`** — a supertype's shape, not a check. This exemption is
 *   not theoretical: without it the first run reported the contract-test seam of
 *   `TaskRepositoryContractTest`, whose `protected abstract suspend fun newRepository`
 *   and whose two `override`s in the fake and Room subclasses are all bodyless hooks.
 * - **Anything else is a finding**, including an `internal` helper. There is no
 *   annotation that means "helper", so the two honest remedies are `private`, or
 *   moving the helper into a non-`*Test` file where it is not mistaken for a test.
 *   Measured on 2026-10-07 across 331 test files in all six trees: zero findings,
 *   so this rule closes a real gap without asking anyone to carve out an exception.
 *
 * `functions(includeNested = false, includeLocal = false)` is load-bearing: the
 * Konsist default is `true` for both, which would walk into every lambda and report
 * every local helper in the repository.
 */
@Tag("fast")
class UnannotatedTestMemberTest {

    @Test
    fun every_non_private_member_of_a_test_class_carries_an_annotation() {
        val findings = mutableListOf<String>()
        var classesSeen = 0
        for ((dir, classes) in classesByDir) {
            if (dir == FIXTURE_DIR.absolutePath) continue
            for (owner in classes) {
                if (!owner.name.endsWith(TEST_CLASS_SUFFIX)) continue
                classesSeen++
                for (member in directMembers(owner)) {
                    if (isUnwired(member)) findings += describe(owner, member)
                }
            }
        }

        // A vacuous pass is the failure mode of a scan, so the coverage is asserted
        // too: a traversal that found nothing would agree with a clean repository.
        assertTrue(
            classesSeen > 200,
            "only $classesSeen test classes were examined — the scan is not covering the " +
                "trees, so finding nothing would mean nothing",
        )
        if (findings.isEmpty()) return
        fail(
            "${findings.size} member(s) of a *Test class that JUnit will never execute and " +
                "nobody marked as a helper:\n" +
                findings.joinToString("\n") { "  - $it" } +
                "\n\nIf it is a test, add the annotation it was missing — an unannotated " +
                "test is the defect this gate exists for (PR #223 shipped two). If it is a " +
                "helper, make it private, or move it to a file not named *Test.kt. Do not " +
                "suppress it: a suppression here records that a gate stopped seeing.",
        )
    }

    /**
     * The control: the gate must still see the shape it was written for.
     *
     * Run against [FIXTURE_DIR], a committed class that is broken on purpose. If the
     * traversal loses nested or local functions, loses annotations, or stops
     * recognising the `Test` suffix, this fails while [every_non_private_member_of_a
     * test_class_carries_an_annotation] keeps passing — which is the dangerous
     * combination, because a gate that cannot detect the defect is indistinguishable
     * from a repository without one.
     */
    @Test
    fun the_gate_still_finds_a_test_that_was_never_wired_to_junit() {
        val classes = classesByDir.getValue(FIXTURE_DIR.absolutePath)
        val fixture = classes.singleOrNull { it.name == FIXTURE_CLASS_NAME }
            ?: error(
                "positive control: ${FIXTURE_CLASS_NAME} not found in ${FIXTURE_DIR.absolutePath}; " +
                    "parsed ${classes.map { it.name }}",
            )
        val found = directMembers(fixture).filter { isUnwired(it) }.map { it.name }
        assertEquals(
            listOf("looksExactlyLikeATest"),
            found,
            "the gate no longer reports the one member of the fixture that carries no " +
                "annotation — it has stopped detecting the defect it exists for",
        )
    }

    /** Direct members only: Konsist defaults to including nested and local functions. */
    private fun directMembers(owner: KoClassDeclaration): List<KoFunctionDeclaration> =
        owner.functions(includeNested = false, includeLocal = false)

    /**
     * A member that is not private and states no intent.
     *
     * The annotation check is on *any* annotation rather than on a set of test
     * annotations, because `@BeforeEach` and `@TestFactory` are both declarations
     * that mean something and neither is a test.
     */
    private fun isUnwired(member: KoFunctionDeclaration): Boolean =
        !isHook(member) && member.annotations.isEmpty()

    /**
     * A declaration that cannot be a standalone test, whatever it is annotated with.
     *
     * `private` is a helper by definition. `abstract` has no body, and `override`
     * is a supertype's shape re-spelled — both are the template-method seam of a
     * contract test: `TaskRepositoryContractTest` declares
     * `protected abstract suspend fun newRepository(userId)`, and its fake and Room
     * subclasses `override` it. All three were reported by the first run of this
     * gate, which is the rule being wrong rather than the tree.
     *
     * The `override` case also covers a member of an anonymous object: `MviViewModelTest`
     * passes `override fun onIntent` to the `MviViewModel` builder lambda, and
     * `functions(includeNested = false, includeLocal = false)` does not exclude it,
     * because an object expression is a declaration rather than a nesting level.
     */
    private fun isHook(member: KoFunctionDeclaration): Boolean =
        member.hasPrivateModifier || member.hasAbstractModifier || member.hasOverrideModifier

    private fun describe(owner: KoClassDeclaration, member: KoFunctionDeclaration): String =
        "${member.location} ${owner.name}.${member.name}()"

    private companion object {

        private const val TEST_CLASS_SUFFIX = "Test"
        private const val FIXTURE_CLASS_NAME = "UnannotatedMemberFixtureTest"

        /**
         * `commonMain.root` is `shared/src/commonMain/kotlin`; three levels up is
         * `shared/`, one more is the root that also holds `desktopApp/`.
         */
        private val repoRoot: File = property("commonMain.root")
            .let { File(it).parentFile.parentFile.parentFile.parentFile }

        /**
         * The class the positive control reads. Not on any compiler source path, and
         * reached from the declared `commonMain.root` rather than from a path relative
         * to the test JVM's working dir, which the build file already records as not
         * guaranteed to be the project directory.
         */
        private val FIXTURE_DIR: File = File(repoRoot, "shared/src/jvmTest/fixtures/arch")

        /**
         * Every Kotlin tree that holds tests, not the four
         * `TestTagCoverageTest` walks.
         *
         * `androidApp/src/androidTest` is the deliberate addition. Its classes are
         * instrumented tests that CI does not run, and the tag gate does not cover
         * them, so they are where an unannotated test accumulates unnoticed: a class
         * nothing selects is a class nothing checks.
         *
         * `shared/src/androidHostTest` is **not** listed: it holds an
         * `AndroidManifest.xml` and no Kotlin at all. Listing it would make this gate
         * fail on a path that does not exist rather than on a defect — which is the
         * same failure as a gate that passes vacuously, with the extra step of looking
         * like a finding.
         */
        private val scannedDirs: List<File> = listOf(
            File(property("commonTest.root")),
            File(property("jvmTest.root")),
            File(property("desktopAppJvmTest.root")),
            File(repoRoot, "mcp-server/src/test"),
            File(repoRoot, "androidApp/src/androidTest"),
            FIXTURE_DIR,
        )

        /** Parsed once: Konsist scopes are expensive and the trees do not change mid-run. */
        private val classesByDir: Map<String, List<KoClassDeclaration>> by lazy {
            scannedDirs.associate { dir ->
                require(dir.isDirectory) { "missing test source dir: ${dir.path}" }
                dir.absolutePath to Konsist.scopeFromExternalDirectory(dir.path).classes().toList()
            }
        }

        /** Paths come from properties the build declares, never from the working dir. */
        private fun property(name: String): String =
            System.getProperty(name) ?: error(
                "$name system property is not set — see the jvmTest task config in " +
                    "shared/build.gradle.kts",
            )
    }
}
