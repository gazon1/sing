package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM secure store's "is this hardware-backed" answer follows the credential store that
 * exists, never the host's OS name.
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
 * ## Why a source scan, and not a stubbed test
 *
 * Neither direction can be pinned by a test that stubs availability: on the Linux host the
 * suite runs on, `IS_LINUX` is `true`, so the old form answered `true` as well and both the
 * `true` and the `false` cases passed. The regression is invisible exactly where it is
 * measured. The only check that goes red on Linux when the OS-name gate returns is one that
 * reads the implementation.
 *
 * @see com.singularity.todo.core.security.JvmSecureStorage
 * @see docs/decisions/2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store.md
 */
@Tag("fast")
class HardwareBackedFlagSourceTest {
    @Test
    fun `no secure-storage source reads the host OS name`() {
        val scanned = secureStorageSources()

        // Not vacuous. A walk that reached no file, or missed the one under the rule, would
        // compare an empty offender list against an empty offender list and report success
        // while checking nothing — so a miss fails here instead of passing silently.
        assertTrue(
            scanned.any { it.name == "JvmSecureStorage.kt" },
            "the scan did not reach JvmSecureStorage.kt; it scanned: ${scanned.map { it.name }}",
        )

        val offenders = scanned.filter { OS_NAME.containsMatchIn(it.readText()) }.map { it.name }

        assertEquals(
            emptyList(),
            offenders,
            "the hardware-backed flag must follow the keyring backend, not the OS name: " +
                "$offenders. On a macOS or Windows host with a keyring the flag would report " +
                "the store as file-fallback, and the fallback file must never be called " +
                "protected (ADR 2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store).",
        )
    }

    /**
     * The rule still rejects the gate it was written for.
     *
     * A scan that stops matching reports success while checking nothing. This feeds it the
     * exact form the flag carried before the java-keyring migration.
     */
    @Test
    fun `the rule rejects the OS-name gate it was written for`() {
        val oldGate =
            """
            override fun isHardwareBacked(): Boolean = IS_LINUX && keyringAvailable()

            private val IS_LINUX =
                System.getProperty("os.name", "").lowercase().contains("linux")
            """.trimIndent()

        assertTrue(
            OS_NAME.containsMatchIn(oldGate),
            "the OS-name rule no longer recognises the gate it exists to reject: $oldGate",
        )
    }

    /**
     * Every JVM secure-storage source, derived from the tree.
     *
     * The set is walked, not listed by hand: a file added beside `JvmSecureStorage` is covered
     * without anyone remembering to add it, and the caller asserts the walk reached the file
     * the rule is about.
     */
    private fun secureStorageSources(): List<File> {
        val root =
            System.getProperty("commonMain.root")
                ?: error(
                    "commonMain.root system property is not set — " +
                        "see the jvmTest task config in shared/build.gradle.kts",
                )
        val sourceRoot =
            File(root).parentFile?.parentFile
                ?: error("cannot locate the module source root above $root")
        val security = File(sourceRoot, SECURITY_SOURCE_SET)
        return security
            .listFiles { file -> file.isFile && file.extension == "kt" }
            ?.sorted()
            .orEmpty()
    }

    private companion object {
        const val SECURITY_SOURCE_SET = "jvmMain/kotlin/com/singularity/todo/core/security"

        /**
         * A read of the host's operating system, however it is spelled.
         *
         * Only this property is matched. `user.home` is what the fallback's key derivation
         * uses and is legitimately read in these files.
         */
        val OS_NAME = Regex("""os\.name""")
    }
}
