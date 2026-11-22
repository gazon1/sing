package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android screen-inspector host exists only in the debug build.
 *
 * ## The defect this pins
 *
 * Issue #3074's third acceptance line is "unavailable in release builds, enforced by the
 * build rather than by convention". A `BuildConfig.DEBUG` check inside a `main`-source-set
 * class is exactly the convention that line rules out: the code is compiled into the
 * release APK and a single flag decides whether it runs. The parent ADR refuses that shape
 * for the desktop host for the same reason — a release artefact must not *carry* the
 * capability, not merely leave it switched off.
 *
 * So the host is a class in `androidApp/src/debug`, a provider declared in
 * `androidApp/src/debug/AndroidManifest.xml`, and nothing else. Three assertions follow,
 * and each one is a different way the boundary has been drawn in this repository before:
 *
 * 1. the provider and its manifest entry exist only in the debug source set;
 * 2. no `main`/`pro` source names the host at all — a call from shared release code would
 *    drag it back in, and a manifest merge from `main` would declare it in a release APK;
 * 3. the *control*: the scan finds the provider where it is supposed to be. A gate that
 *    passes because it read nothing is the failure mode this class is written against, and
 *    it is the one every source-scanning gate in this repository has hit at least once.
 *
 * ## Why static, and what it does not prove
 *
 * This reads the tree; it does not build a release APK and unzip it. That is a deliberate
 * trade: the static check runs in the fast lane and catches the change that would matter
 * (someone moving the class, or calling it from `main`), while an R8-backed assertion
 * would cost a full release build on every gate run. The residual risk is a build
 * configuration change that keeps the file in `src/debug` but adds that directory to the
 * release source set — so the declarations that would have to change are named below and
 * asserted to stay as they are.
 *
 * @see docs/decisions/2026-11-22-the-android-screen-inspector-host-is-a-debug-provider.md
 */
@Tag("fast")
class ScreenInspectorHostIsDebugOnlyTest {
    private val repoRoot: File =
        File(
            System.getProperty("commonMain.root")
                ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
        ).parentFile.parentFile.parentFile.parentFile

    private companion object {
        const val PROVIDER_CLASS = "ScreenInspectorHostProvider"
        const val PROVIDER_PACKAGE = "com/singularity/todo/debug"
        const val DEBUG_HOST_CLASS = "ScreenInspectorDeviceHost"
        const val AUTHORITY_SUFFIX = "screen-inspector-host"

        /** Source sets a release APK is assembled from. The host must not be in any. */
        val RELEASE_SOURCE_SETS = listOf("shared/src/commonMain", "shared/src/jvmMain", "androidApp/src/main")

        /** Where the host is allowed to be, and nowhere else. */
        const val DEBUG_SOURCE_SET = "androidApp/src/debug"

        /** The literal the provider's guard must contain to let `content call` through. */
        const val SHELL_UID_MARKER = "2000"
    }

    private fun kotlinFiles(relative: String): List<File> {
        val dir = File(repoRoot, relative)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun relative(file: File): String = file.relativeTo(repoRoot).path.replace(File.separatorChar, '/')

    /** Comments removed: prose that names the host is documentation, not a call into it. */
    private fun code(file: File): String =
        file
            .readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")

    @Test
    fun `the provider is declared in the debug source set and nowhere else`() {
        val holders =
            listOf(
                "androidApp/src/main",
                "androidApp/src/debug",
                "androidApp/src/pro",
                "shared/src/commonMain",
                "shared/src/jvmMain",
            ).flatMap { kotlinFiles(it) }
                .filter { it.nameWithoutExtension == PROVIDER_CLASS }
                .map(::relative)

        assertEquals(
            listOf("$DEBUG_SOURCE_SET/kotlin/$PROVIDER_PACKAGE/$PROVIDER_CLASS.kt"),
            holders,
            "the screen-inspector provider must exist exactly once, in androidApp/src/debug. " +
                "A release build compiles src/main; a copy there ships the host.",
        )
    }

    @Test
    fun `the release source sets never name the host`() {
        // A call from shared code is the way this boundary would actually be broken: the
        // class would still live in `src/debug` and the release build would fail to
        // compile only if it *required* it — which it would after somebody "fixed" the
        // missing symbol by moving the class up.
        val offenders =
            RELEASE_SOURCE_SETS
                .flatMap { kotlinFiles(it) }
                .filter { file ->
                    val text = code(file)
                    text.contains(PROVIDER_CLASS) || text.contains(DEBUG_HOST_CLASS)
                }.map(::relative)

        assertTrue(
            offenders.isEmpty(),
            "release source sets name the debug screen-inspector host: $offenders. " +
                "The host is a development/test capability; code compiled into a release " +
                "APK must not reference it, or the capability comes back with it.",
        )
    }

    @Test
    fun `the provider is declared in the debug manifest only`() {
        val debugManifest = File(repoRoot, "$DEBUG_SOURCE_SET/AndroidManifest.xml")
        val mainManifest = File(repoRoot, "androidApp/src/main/AndroidManifest.xml")

        assertTrue(debugManifest.isFile, "the debug manifest is gone: ${debugManifest.path}")
        val debugText = debugManifest.readText()
        assertTrue(
            debugText.contains(PROVIDER_CLASS),
            "the debug manifest no longer declares $PROVIDER_CLASS — the host is unreachable " +
                "on a device, and the Issue's discover/invoke criterion cannot be met.",
        )
        assertTrue(
            debugText.contains(AUTHORITY_SUFFIX),
            "the debug manifest declares the provider without its authority suffix '$AUTHORITY_SUFFIX'.",
        )
        assertTrue(
            debugText.contains("\${applicationId}"),
            "the provider authority is not parameterised by applicationId: a debug build " +
                "installed beside a release build would collide on the authority.",
        )
        assertTrue(
            !mainManifest.readText().contains(PROVIDER_CLASS),
            "the *main* manifest declares $PROVIDER_CLASS. A release APK merges src/main, so " +
                "the host would be declared in every release build.",
        )
    }

    @Test
    fun `the provider is exported only behind a caller check`() {
        // `exported="true"` is what makes `adb shell content call` possible; the guard in
        // `call()` is what keeps that from meaning "any app on the device". Both halves are
        // asserted, because either alone is a hole: a private provider is unreachable from
        // adb, and an exported provider with no guard is a public surface.
        val providerFile =
            File(repoRoot, "$DEBUG_SOURCE_SET/kotlin/$PROVIDER_PACKAGE/$PROVIDER_CLASS.kt")
        assertTrue(providerFile.isFile, "the provider source is gone: ${providerFile.path}")

        val text = code(providerFile)
        assertTrue(
            text.contains("Binder.getCallingUid()"),
            "the provider does not read the calling UID: an exported provider with no caller " +
                "check is reachable by any app on the device.",
        )
        assertTrue(
            text.contains("isAllowedCaller"),
            "the provider reads a UID but has no caller guard around it.",
        )
        assertTrue(
            text.contains(SHELL_UID_MARKER),
            "the caller check does not recognise the adb shell UID ($SHELL_UID_MARKER), so the " +
                "same-device development path the Issue allows cannot reach the host.",
        )
    }

    @Test
    fun `the scan is not vacuous`() {
        // The positive control. Every assertion above passes trivially if the walk finds no
        // files, which is how a source-scanning gate reports green over an empty corpus.
        val debugFiles = kotlinFiles("$DEBUG_SOURCE_SET/kotlin")
        assertTrue(
            debugFiles.isNotEmpty(),
            "no Kotlin sources found under $DEBUG_SOURCE_SET/kotlin — the scan is measuring nothing",
        )
        assertTrue(
            debugFiles.any { it.nameWithoutExtension == PROVIDER_CLASS },
            "the provider is not among the debug sources, so the boundary assertions above " +
                "are describing a tree that does not exist",
        )
        assertTrue(
            RELEASE_SOURCE_SETS.all { kotlinFiles(it).isNotEmpty() },
            "one of the release source sets has no Kotlin sources, so `release sets never name " +
                "the host` checked nothing for it: $RELEASE_SOURCE_SETS",
        )
    }
}
