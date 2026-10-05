package com.singularity.todo.feature.genui.di

import com.singularity.todo.core.di.aiToolsModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.test.desktopPlatformModule
import com.singularity.todo.feature.genui.engine.GenuiSession
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.transport.GenuiTransport
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The GenUI graph, resolved the way production resolves it.
 *
 * This exists because of a gap that nothing else covered. `genuiModule()` is pulled into
 * `domainModule()`, but its transport decorator needs a platform `BaseGenuiTransport`, and that
 * binding lives in `aiToolsModule()` — which the existing graph test does not load. So the GenUI
 * definitions were present in the graph and *unresolvable*, and no test noticed, because
 * `KoinGraphValidationTest` asserts a hand-picked handful of singletons rather than calling
 * `verify()`.
 *
 * The failure mode it hides is specific: a definition whose lambda body never executes is a
 * definition that can be wrong in every way and stay invisible. The production symptom would be a
 * `NoDefinitionFoundException` on first `koinInject<GenuiSession>()` outside the chat path — at
 * runtime, on a user's screen, after every test passed.
 *
 * All three modules are therefore loaded together, which is what `ChatScreen` actually depends on,
 * and the layer's definitions are *resolved* rather than listed.
 *
 * Resolution, not Koin's `verify()`: `verify()` inspects constructor types statically and reports
 * `MissingKoinDefinitionException` for bindings that genuinely exist — Kermit's own `LoggerConfig`
 * is the documented case in `KoinGraphValidationTest`, and this graph needs a hand-maintained
 * ignore-list to get past it. Resolving runs the definition bodies, so it catches the thing that
 * matters (a binding nothing can satisfy, or one whose lambda throws) without the false positives.
 * The first version of this test failed exactly that way: `GenuiSession` could not be created,
 * because the usage recorder reaches for `Clock` and `SecureStoragePort` from the platform module
 * and no graph in the repository loaded all three.
 */
@Tag("fast")
class GenuiDiGraphTest {

    /**
     * The composition the chat's GenUI path actually needs — all three of its parts.
     *
     * Written down here because the fact it records is not obvious from any one module: the GenUI
     * bindings reach across all three. `domainModule()` carries the catalog, validator, processor
     * and surface controller. `aiToolsModule()` carries the platform `BaseGenuiTransport` the
     * usage-recording decorator wraps. `platformModule()` carries `Clock` and `SecureStoragePort`,
     * which the recorder needs for the usage row it writes. Load two and the graph is incomplete
     * in a way nothing reports — which is what happened: no test composed all three, so the fact
     * that `GenuiSession` was unresolvable went unnoticed until this test asked.
     *
     * Composed as a list rather than spread as varargs, matching every production entry point, and
     * for the same reason: `*domainModule().toTypedArray()` produces a graph the Koin compiler
     * cannot verify (ADR 2026-09-27-di-module-aggregator-narrative).
     */
    private fun genuiGraph(): List<Module> =
        listOf(desktopPlatformModule(), aiToolsModule()) + domainModule()

    /**
     * And the four the layer actually hands to a screen.
     *
     * `verify()` proves the definitions are satisfiable; these prove the types line up with
     * what `ChatScreen` injects, which is a different mistake and the more likely one.
     */
    @Test
    fun `the bindings the chat renders a surface with resolve`() {
        val app = koinApplication { modules(genuiGraph()) }
        try {
            assertNotNull(app.koin.get<GenuiSession>(), "The chat resolves one per turn")
            assertNotNull(app.koin.get<GenuiTransport>(), "The decorator wraps the platform transport")
            assertNotNull(app.koin.get<SurfaceController>(), "One controller holds every open surface")
            assertNotNull(app.koin.get<ComponentRegistry>(), "installAll ran, so domain components draw")
        } finally {
            app.close()
        }
    }

    /**
     * The registry the chat draws with has every catalog component in it.
     *
     * Not redundant with the catalog's own round-trip test: that one checks the two directions
     * agree in the abstract, and this one checks the binding production installs is the one that
     * has them. A DI change that swapped `installAll` for `install` would leave the first green and
     * a surface with three unwired domain components on it.
     */
    @Test
    fun `the installed registry draws the domain components too`() {
        val app = koinApplication { modules(genuiGraph()) }
        try {
            val registry: ComponentRegistry = app.koin.get()
            assertTrue(
                registry.has("task_card"),
                "install() covers Material3 only — production must use installAll()",
            )
            assertTrue(registry.has("due_date"))
            assertTrue(registry.has("project_chip"))
        } finally {
            app.close()
        }
    }
}
