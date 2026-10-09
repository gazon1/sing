package com.singularity.todo.arch

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Tag

/**
 * Every production ViewModel has a test — or is on a list, with a reason.
 *
 * ## The bug class this catches
 *
 * A ViewModel with no test is a ViewModel whose state machine nobody has
 * reasoned about: the intents it handles, the events it emits, and the failure
 * paths it swallows are all unverified. In this codebase that is not
 * hypothetical — a copy-to-profile flow that resolved the wrong user id was
 * found only by a Maestro run on a real device, and the repository underneath
 * it had a cross-profile write bug that its `FakeRepo` was structurally unable
 * to reproduce (see `docs/plans/2026-10-04-mr5-retro-gate.md`).
 *
 * So the rule is deliberately blunt: a new ViewModel without a test fails the
 * build. The way to ship one without tests is to add it to [KNOWN_UNCOVERED]
 * *and* say why in `docs/decisions/deferred-backlog.md`, which is a visible,
 * reviewable act rather than a silent default.
 *
 * ## What "has a test" means here
 *
 * A test source that declares `class <Name>Test` somewhere under the test
 * roots. It does not check that the test is thorough — that is what coverage
 * and the behaviour tests are for. The point is that the author is on the hook
 * for having written *something*, at the moment the ViewModel is created.
 *
 * ## Scope
 *
 * commonMain production classes only. A ViewModel is a commonMain concept;
 * platform source sets and the desktop/Android shells have none of their own.
 * Files under `test/` are excluded — fakes are not production classes even
 * though they live in commonMain.
 */

// ─── Scan ─────────────────────────────────────────────────────────────────────

private val commonMainRoot: String = System.getProperty("commonMain.root")
    ?: error("commonMain.root is not set — see the jvmTest config in shared/build.gradle.kts")

private val commonTestRoot: String = System.getProperty("commonTest.root")
    ?: error("commonTest.root is not set — see the jvmTest config in shared/build.gradle.kts")

private val jvmTestRoot: String = System.getProperty("jvmTest.root")
    ?: error("jvmTest.root is not set — see the jvmTest config in shared/build.gradle.kts")

private val desktopAppJvmTestRoot: String = System.getProperty("desktopAppJvmTest.root")
    ?: error("desktopAppJvmTest.root is not set — see the jvmTest config in shared/build.gradle.kts")

private val CLASS_DECL =
    Regex("""^\s*(?:internal |data |open |abstract |sealed )*class\s+(\w+)""", RegexOption.MULTILINE)

/** Every `*ViewModel` class declared in commonMain production code. */
private fun viewModels(): Set<String> = productionFiles()
    .flatMapTo(mutableSetOf()) { file ->
        CLASS_DECL.findAll(file.readText())
            .map { it.groupValues[1] }
            .filter { it.endsWith("ViewModel") }
    }

/** Test class names declared anywhere in the test roots. */
private fun declaredTestClasses(): Set<String> = testRoots()
    .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
    .flatMapTo(mutableSetOf()) { file -> CLASS_DECL.findAll(file.readText()).map { it.groupValues[1] } }

private fun testRoots(): List<File> = listOf(commonTestRoot, jvmTestRoot, desktopAppJvmTestRoot)
    .map(::File)
    .filter { it.isDirectory }

private fun productionFiles(): List<File> = File(commonMainRoot)
    .walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .filterNot { it.path.contains("${File.separator}test${File.separator}") }
    .toList()

/** ViewModels that ship without a test. Each is tracked in the backlog. */
private val KNOWN_UNCOVERED = setOf(
    "AccountSettingsViewModel",
    "AiUsageViewModel",
    "AppVersionGateViewModel",
    "ArchiveViewModel",
    "AttachmentsViewModel",
    "AuthViewModel",
    "CalendarSyncViewModel",
    "ProfileSwitcherViewModel",
)

@Tag("fast")
class ViewModelTestCoverageTest {

    private val tested: Set<String> by lazy {
        val tests = declaredTestClasses()
        viewModels().filterTo(mutableSetOf()) { "${it}Test" in tests }
    }

    @Test
    fun `every ViewModel has a test or is explicitly listed as uncovered`() {
        val uncovered = viewModels().filterNot { it in tested || it in KNOWN_UNCOVERED }

        if (uncovered.isEmpty()) return
        fail(
            "ViewModel(s) with no test and no entry in KNOWN_UNCOVERED:\n" +
                uncovered.joinToString("\n") { "  - $it" } +
                "\n\nWrite a test for it, or — if it genuinely cannot be tested yet —\n" +
                "add it to KNOWN_UNCOVERED in this file *and* an entry to\n" +
                "docs/decisions/deferred-backlog.md explaining why. An allowlist entry\n" +
                "without a backlog entry is a regression of the same kind this rule\n" +
                "exists to prevent.",
        )
    }

    /**
     * A rule that matches nothing passes forever while enforcing nothing, so
     * assert the scan has teeth — and that the allowlist has not quietly
     * swallowed the whole project.
     */
    @Test
    fun `the scan actually finds the project's ViewModels`() {
        val found = viewModels()
        assertTrue(
            found.size >= 20,
            "expected the project's ViewModels, found ${found.size}: ${found.sorted()}",
        )
        val stale = KNOWN_UNCOVERED.filterNot { it in found }
        assertTrue(
            stale.isEmpty(),
            "KNOWN_UNCOVERED lists classes that no longer exist: ${stale.joinToString()}. " +
                "Remove them — a stale entry means the rule stopped checking something real.",
        )
    }
}
