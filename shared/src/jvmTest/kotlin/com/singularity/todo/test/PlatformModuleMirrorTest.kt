package com.singularity.todo.test

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [KoinGraphValidationTest] re-declares the platform bindings instead of using
 * [com.singularity.todo.core.di.platformModule], because the real one pulls in
 * Android-only dependencies.
 *
 * That duplication is a trap, and it has already caught two production problems in
 * this change: `SyncStateDao` was bound in neither platform module, and
 * `projectReminderDao` was bound on desktop but not in the mirror. In both cases
 * `KoinGraphValidationTest` was green, because it resolves the mirror rather than the
 * app. A test that mirrors the thing it is testing cannot report the thing going
 * missing from both copies at once.
 *
 * So the mirror is checked against its source, one directionally — see the test for
 * why. Cheap, and it fails with a sentence naming the missing DAO rather than with a
 * green build.
 */
@Tag("fast")
class PlatformModuleMirrorTest {

    private val daoBindings = Regex("""get<AppDatabase>\(\)\.(\w+)\(\)""")

    /**
     * Every explicitly-typed Koin binding: `single<Foo>`, `factory<Foo> {`,
     * `viewModel<Foo>`, and so on.
     *
     * DAOs alone were not enough, and the gap was found the hard way. The desktop
     * module resolved `scope = get()` for a `CoroutineScope` that only the *Android*
     * module bound — so the desktop graph could not be built, and the DAO scan could
     * not see it because the type is not a DAO. Typed bindings are what makes the
     * difference visible; an untyped `single { … }` is inferred and stays invisible
     * here, which is the accepted limit of a source-level gate.
     */
    private val typedBindings = Regex(
        """\b(?:single|factory|viewModel|viewModelOf|factoryOf|singleOf)<\s*([\w.]+)""",
    )

    private fun bindingsIn(file: File): Set<String> =
        daoBindings.findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun typedBindingsIn(file: File): Set<String> =
        typedBindings.findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun androidModuleFile(): File {
        val root = System.getProperty("jvmMain.root")
            ?: error("jvmMain.root is not set — see the jvmTest task config")
        val sharedSrc = File(root).parentFile.parentFile
        val file = File(File(sharedSrc, "androidMain/kotlin/com/singularity/todo/core/di"), "PlatformModule.android.kt")
        assertTrue(file.exists(), "android platform module not found at $file")
        return file
    }

    private fun sourceFile(name: String): File {
        val root = System.getProperty("jvmMain.root")
            ?: error("jvmMain.root is not set — see the jvmTest task config")
        val file = File(File(File(root).parentFile.parentFile, "jvmMain/kotlin/com/singularity/todo/core/di"), name)
        assertTrue(file.exists(), "platform module not found at $file")
        return file
    }

    private fun mirrorFile(): File {
        // jvmMain.root = <shared>/src/jvmMain/kotlin; this test lives in the sibling
        // source set. Deriving from commonMain.root would look in the wrong tree —
        // the mirror is a test double and has no business being in commonMain.
        val root = System.getProperty("jvmMain.root")
            ?: error("jvmMain.root is not set — see the jvmTest task config")
        val file = File(
            File(File(root).parentFile.parentFile, "jvmTest/kotlin/com/singularity/todo/test"),
            "KoinGraphValidationTest.kt",
        )
        assertTrue(file.exists(), "Koin mirror not found at $file")
        return file
    }

    @Test
    fun `the test's platform mirror binds every DAO the desktop module binds`() {
        val production = bindingsIn(sourceFile("PlatformModule.jvm.kt"))
        val mirror = bindingsIn(mirrorFile())

        // One direction only, and the asymmetry is the point.
        //
        // A binding the app has and the mirror lacks means the graph test resolved a
        // graph the desktop app cannot build — the test passes on a graph that would
        // throw `NoDefinitionFoundException` on first use. That is a false green and
        // it is what this gate exists to stop.
        //
        // A binding only the mirror has goes the other way: the test resolves code the
        // desktop never runs, which makes the test slightly weaker. `calendarSyncTaskMapDao`
        // is exactly that case — the feature is Android-only and binds `NoopCalendarSyncRepositoryImpl`
        // on the JVM — and demanding symmetry would force either a fake binding in
        // production or a permanent exemption list that nobody reads.
        assertEquals(
            emptySet(),
            production - mirror,
            "KoinGraphValidationTest mirrors the platform module by hand; these DAOs are " +
                "bound on desktop and absent from the mirror, so the graph test resolves a " +
                "graph the app cannot build",
        )
    }

    @Test
    fun `every type the desktop module binds is also bound on Android`() {
        val desktop = typedBindingsIn(sourceFile("PlatformModule.jvm.kt"))
        val android = typedBindingsIn(androidModuleFile())

        // The direction that matters: a type the desktop module binds and Android
        // does not is a graph that only works on one platform. `CoroutineScope` was
        // exactly that — bound on Android, resolved by `get()` on the desktop, and
        // invisible to the DAO scan above because it is not a DAO.
        assertEquals(
            emptySet(),
            desktop - android,
            "these types are bound by PlatformModule.jvm.kt but not by PlatformModule.android.kt, " +
                "so the desktop graph cannot be built. One direction only: a type bound on " +
                "Android and not on desktop is usually an Android-only feature with a " +
                "no-op desktop implementation, which is legitimate",
        )
    }
}
