package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.singularity.todo.App
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.feature.gate.gateModule
import org.koin.compose.KoinIsolatedContext
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

private const val RELEASES_URL = "https://github.com/singularity-todo/singularity/releases"

/**
 * Mounts the production [App] composable inside a headless
 * [runDesktopComposeUiTest] — the desktop mirror of a Maestro flow.
 *
 * The Koin graph is assembled per test rather than through `startKoin`, so no
 * process-wide singleton leaks between test classes. It mirrors
 * `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`, except that
 * [testPlatformModule] replaces the real `platformModule()` so nothing reads or
 * writes the developer's `~/.singularity-todo` and no test shells out to
 * `secret-tool` or `notify-send`.
 *
 * [overrides] loads last and therefore wins Koin's last-definition-wins rule,
 * which is how a test swaps a binding for a fake.
 */
@OptIn(ExperimentalTestApi::class)
fun runDesktopAppTest(
    overrides: Module = module {},
    test: suspend DesktopComposeUiTest.(koin: Koin) -> Unit,
) = runDesktopComposeUiTest {
    val app: KoinApplication = koinApplication {
        modules(
            coreLoggingModule(),
            *domainModule().toTypedArray(),
            // Loaded after domainModule() on purpose. Koin resolves duplicate
            // definitions last-wins, so coreModule()'s SupabaseAuthRepository
            // would otherwise override the fake here — and its userId resolves
            // from "anonymous" to a generated ULID a moment after startup, which
            // orphans anything written in that window and makes the row invisible
            // to every subsequent read.
            testPlatformModule(),
            gateModule(RELEASES_URL),
            overrides,
        )
    }
    setContent { KoinIsolatedContext(app) { App(deeplinkViewId = null, deeplinkTaskId = null) } }
    test(app.koin)
}
