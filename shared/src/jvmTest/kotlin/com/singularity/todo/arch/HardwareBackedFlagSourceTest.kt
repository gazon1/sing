package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No JVM secure-storage source reads the host's operating system.
 *
 * ## What the rule is protecting
 *
 * `JvmSecureStorage.isHardwareBacked()` once read `IS_LINUX && keyringAvailable()`, a leftover
 * from the `secret-tool` CLI that only ever existed on Linux. The java-keyring binding speaks
 * to libsecret, the macOS Keychain and the Windows Credential Manager, so on a macOS or
 * Windows host with a keyring the flag said `false` and the store described itself as
 * file-fallback — the one direction the decision record forbids, because the flag is the only
 * signal a caller gets that the store is not protected.
 *
 * ## Why the whole package, and not just that one expression
 *
 * The defect was not the expression, it was the decision: what this store can promise was
 * asked of the host's identity instead of the credential-store binding. The rule is therefore
 * stated over the package rather than over the flag's right-hand side, so that the next caller
 * shaped decision in these files — a new member, a helper the flag delegates to, a round of
 * refactoring that moves the gate one file over — is covered without anyone remembering to
 * widen a check. No OS-name read has a legitimate use here: the only host fact these sources
 * need is `user.home`, for the fallback's key derivation, and everything else is answered by
 * the keyring binding. A read of `os.name` anywhere in the package is the shape of the old
 * gate, whichever member it ends up in.
 *
 * Only `os.name` is matched. The reader this rule must not catch — presentation and launcher
 * decisions elsewhere in `jvmMain` — is outside the package and outside the scan.
 *
 * ## Why a source scan, and not a stubbed test
 *
 * Neither direction can be pinned by a test that stubs availability: on the Linux host the
 * suite runs on, `IS_LINUX` is `true`, so the old form answered `true` as well and both the
 * `true` and the `false` cases passed. The regression is invisible exactly where it is
 * measured. The only check that goes red on Linux when the OS-name gate returns is one that
 * reads the implementation.
 *
 * ## Vacuity
 *
 * A scan reports green over an empty list too, so every way of reaching nothing fails here
 * instead: the package is asserted to exist, its contents are enumerated twice (the stdlib
 * walk the rule reads, and a `java.nio` traversal that says what is actually there), a file
 * only one of them found is reported by name, a source type the rule cannot read is refused,
 * and the failure message names the files that were scanned. The positive control in
 * [the rule rejects the OS-name gate it was written for] runs the same scan over a committed
 * fixture holding the old gate, so a rule that stopped matching fails instead of passing
 * quietly.
 *
 * @see com.singularity.todo.core.security.JvmSecureStorage
 * @see docs/decisions/2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store.md
 */
@Tag("fast")
class HardwareBackedFlagSourceTest {
    @Test
    fun `no JVM secure-storage source reads the host OS name`() {
        val scanned = secureStorageSources()

        val offenders = osNameReadersIn(scanned)

        assertEquals(
            emptyList(),
            offenders,
            "these JVM secure-storage sources read the host's OS name: $offenders. The store " +
                "decides what it can do from the credential-store binding, never from the " +
                "platform: the removed gate `IS_LINUX && keyringAvailable()` reported a macOS " +
                "or Windows host with a keyring as file-fallback, and the fallback file must " +
                "never be called protected (ADR " +
                "2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store). " +
                "Scanned: ${scanned.map { it.relativePathUnder(securityPackage) }}",
        )
    }

    /**
     * The control: the rule still rejects the gate it was written for.
     *
     * A scan that stops matching reports success while checking nothing. This runs the same
     * scan — the same comment stripping, the same pattern, the same offender list — over a
     * committed fixture holding the exact form the flag carried before the java-keyring
     * migration, and requires it to be named as an offender.
     *
     * Reading a literal from the test body instead would prove only that the regex still
     * matches a string: it would stay green if the walk pointed somewhere else, if the
     * filter were inverted, or if the offender list were built with the wrong half of the
     * partition.
     */
    @Test
    fun `the rule rejects the OS-name gate it was written for`() {
        val fixture = File(fixturesRoot, FIXTURE_NAME)
        assertTrue(fixture.isFile, "the positive control's fixture is missing: ${fixture.path}")

        assertEquals(
            listOf(FIXTURE_NAME),
            osNameReadersIn(listOf(fixture)),
            "the rule no longer reports the gate it exists to reject, so the check above " +
                "proves nothing. Fixture: ${fixture.path}",
        )
    }

    /**
     * The one scan, shared by the guard and its control.
     *
     * Comments are blanked first because a gate that greps raw text matches the comment
     * *documenting* the rule it forbids — the keyring section of `JvmSecureStorage` explains
     * in prose that availability is decided by the binding and not by the platform, and an
     * earlier draft of a scan like this matched its own explanation. String literals are
     * deliberately kept: `System.getProperty("os.name", …)` carries the property name in a
     * literal, so blanking strings would make the scan blind to the exact call it exists to
     * find.
     */
    private fun osNameReadersIn(sources: List<File>): List<String> =
        sources
            .filter { OS_NAME.containsMatchIn(stripComments(it.readText())) }
            .map { it.name }

    /**
     * Every source in the JVM security package, walked rather than listed.
     *
     * The root is the `jvmMain.root` the build declares, not a path reconstructed from
     * another property: `check-test-task-inputs.py` fails when a path-valued system property
     * points outside its module with no input covering it, so a derived root is invisible to
     * that gate — deriving it is the defect, not the style.
     */
    private fun secureStorageSources(): List<File> {
        assertTrue(
            securityPackage.isDirectory,
            "the JVM security package is not where the guard looks: ${securityPackage.path}",
        )

        // Recursive: a source added in a subdirectory of the package is covered without
        // anyone remembering to add it.
        val walked =
            securityPackage
                .walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.path }
                .toList()
        val walkedPaths = walked.map { it.relativePathUnder(securityPackage) }.toSet()

        // A file the walk did not reach is a file the rule did not apply to, and a walk over
        // nothing agrees with a clean tree. Enumerating the same directory a second way and
        // comparing turns both into a failure that names the file.
        val present = pathsUnder(securityPackage)
        val missed = present - walkedPaths
        assertTrue(
            missed.isEmpty(),
            "the scan did not reach ${missed.sorted()} under ${securityPackage.path} — a file " +
                "the guard intended to read but never opened is one it passed over silently. " +
                "It scanned: ${walkedPaths.sorted()}",
        )

        val unreadable = walkedPaths.filterNot { it.endsWith(KOTLIN_SUFFIX) }
        assertTrue(
            unreadable.isEmpty(),
            "the rule reads Kotlin only, so ${unreadable.sorted()} would sit in the JVM " +
                "security package unexamined and a secure-storage decision written there " +
                "would be unpinned. Teach the scan this file type or move the file out of " +
                "the package.",
        )

        assertTrue(
            walked.isNotEmpty(),
            "no source was scanned under ${securityPackage.path}: a package that is empty, " +
                "or named wrongly, would report success over zero files",
        )

        return walked
    }

    /**
     * The directory's regular files, enumerated by `java.nio` instead of the stdlib walk.
     *
     * Two traversals rather than one because a single walk cannot notice its own skip: the
     * walk above is what the rule read, this is what the package holds.
     */
    private fun pathsUnder(dir: File): Set<String> =
        Files.walk(dir.toPath()).use { stream ->
            stream
                .iterator()
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .map { dir.toPath().relativize(it).toFile().invariantSeparatorsPath }
                .toSet()
        }

    private fun File.relativePathUnder(dir: File): String = relativeTo(dir).invariantSeparatorsPath

    private fun sourceRoot(property: String): File =
        File(
            System.getProperty(property)
                ?: error(
                    "$property system property is not set — " +
                        "see the jvmTest task config in shared/build.gradle.kts",
                ),
        )

    /** `jvmMain.root` is `shared/src/jvmMain/kotlin`; the package is under it. */
    private val securityPackage: File by lazy { File(sourceRoot("jvmMain.root"), SECURITY_PACKAGE) }

    /**
     * The fixture lives beside the test sources, not under them.
     *
     * `jvmTest.root` is `shared/src/jvmTest/kotlin`, so its sibling `fixtures/arch` needs no
     * derived parent walk — and that tree is declared as an input of `:shared:archTest`, so
     * editing the fixture re-runs this gate instead of leaving it to report a stale verdict.
     */
    private val fixturesRoot: File by lazy {
        File(sourceRoot("jvmTest.root").parentFile, "fixtures/arch")
    }

    private companion object {
        /** The package under `jvmMain.root` that answers secure-storage questions. */
        const val SECURITY_PACKAGE = "com/singularity/todo/core/security"
        const val KOTLIN_SUFFIX = ".kt"

        /** The old gate, kept as a file the control reads. */
        const val FIXTURE_NAME = "HardwareBackedFlagFixtureSource.kt"

        /**
         * A read of the host's operating system, however it is spelled.
         *
         * Only this property is matched: the command-line form (`uname`, `ver`) is not how
         * this codebase asks, and `user.home` is what the fallback's key derivation uses and
         * is legitimately read in these files.
         */
        val OS_NAME = Regex("""os\.name""")
    }
}
