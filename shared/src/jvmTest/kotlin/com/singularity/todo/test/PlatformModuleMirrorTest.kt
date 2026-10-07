package com.singularity.todo.test

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [desktopPlatformModule] re-declares the platform bindings instead of using
 * [com.singularity.todo.core.di.platformModule], because the real one opens the
 * user's database and cannot be loaded twice in a process.
 *
 * That duplication is a trap, and it has already caught two production problems in
 * this change: `SyncStateDao` was bound in neither platform module, and
 * `projectReminderDao` was bound on desktop but not in the mirror. In both cases the
 * graph test was green, because it resolves the mirror rather than the app. A test that
 * mirrors the thing it is testing cannot report the thing going missing from both
 * copies at once.
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
        // Simple names, not the text as written: the production module binds
        // `com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries` in
        // full while the mirror imports it, and comparing the raw text reported a
        // difference that was only a difference of spelling. A mirror is allowed to
        // shorten a name; it is not allowed to drop a binding.
        typedBindings.findAll(file.readText()).map { it.groupValues[1].substringAfterLast(".") }.toSet()

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
        //
        // The mirror moved out of `KoinGraphValidationTest.kt` into its own file so the
        // GenUI graph test can build the same graph; this path has to follow it, and
        // `assertTrue` below is what says so when it does not.
        val root = System.getProperty("jvmMain.root")
            ?: error("jvmMain.root is not set — see the jvmTest task config")
        val file = File(
            File(File(root).parentFile.parentFile, "jvmTest/kotlin/com/singularity/todo/test"),
            "DesktopPlatformGraph.kt",
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
            "desktopPlatformModule mirrors the platform module by hand; these DAOs are " +
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

    @Test
    fun `the mirror binds every typed binding the desktop module binds`() {
        val production = typedBindingsIn(sourceFile("PlatformModule.jvm.kt"))
        val mirror = typedBindingsIn(mirrorFile())

        // This is the gap `UnitOfWork` fell through, and the two checks above could not
        // have caught it in either direction.
        //
        // The DAO check reads `get<AppDatabase>().(\w+)()` and so sees DAOs only. The
        // platform check compares desktop against *Android*, not against the mirror. So a
        // new non-DAO binding in `PlatformModule.jvm.kt` had no check at all: `dc7f1d5d`
        // extracted the mirror from a copy predating the unit-of-work work, `UnitOfWork`
        // disappeared from it silently, and every test stayed green while the mirror could
        // not construct a `TaskRepository`.
        //
        // It surfaced only because something now *resolves* the sync chain
        // (`SyncDiGraphResolutionTest`) rather than reading the mirror's text. That is the
        // whole lesson: a mirror drift is invisible to source-scanning checks, because the
        // thing that is missing is precisely the thing nothing references.
        assertEquals(
            emptySet(),
            production - mirror,
            "desktopPlatformModule mirrors the platform module by hand; these types are " +
                "bound on desktop and absent from the mirror, so the graph test resolves a " +
                "graph the app cannot build",
        )
    }
}
