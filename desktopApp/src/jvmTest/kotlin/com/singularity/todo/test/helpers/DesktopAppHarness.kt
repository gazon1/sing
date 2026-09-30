package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.singularity.todo.App
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.feature.gate.gateModule
import org.koin.compose.KoinContext
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files

private const val RELEASES_URL = "https://github.com/singularity-todo/singularity/releases"

/**
 * Binds [FakeRemoteConfigPort] and records the instance in [remoteConfigHolder]
 * so a test can re-seed the served snapshot mid-flow.
 *
 * The port is the app's only network dependency on the startup path: the
 * version gate reads it in its `init`, and `WhatsNewScreen` observes it. Serving
 * [com.singularity.todo.core.config.RemoteConfigSnapshot.defaults] keeps the
 * gate in `Allowed` and the What's New sheet hidden, with no HTTP call.
 */
val remoteConfigHolder = mutableListOf<FakeRemoteConfigPort>()

internal fun fakeRemoteConfigModule(): Module = module {
    val port = FakeRemoteConfigPort()
    remoteConfigHolder += port
    single<RemoteConfigPort> { port }
}

/**
 * Redirects `user.home` at a fresh temp directory for the duration of one test.
 *
 * `platformModule()` resolves the SQLite path and all three DataStore paths from
 * `System.getProperty("user.home")` **while its module body is being built**, so
 * the property must be swapped before `platformModule()` is called — not merely
 * before the first Koin resolution.
 *
 * Without this, every desktop UI test would open — and migrate — the developer's
 * real `~/.singularity-todo/singularity-todo.db`, so a failing test could leave
 * seeded fixtures behind and the next test would start from a dirty database.
 */
private fun <T> withTempUserHome(block: () -> T): T {
    val original = System.getProperty("user.home")
    val temp = Files.createTempDirectory("singularity-desktop-ui-test").toFile()
    remoteConfigHolder.clear()
    System.setProperty("user.home", temp.absolutePath)
    try {
        return block()
    } finally {
        System.setProperty("user.home", original)
        temp.deleteRecursively()
        remoteConfigHolder.clear()
    }
}

/**
 * Mounts the production [App] composable inside a headless
 * [runDesktopComposeUiTest] — the desktop mirror of a Maestro flow.
 *
 * The Koin graph is assembled per test rather than through `startKoin`, so no
 * process-wide singleton leaks between the suite's parallel test classes. It
 * mirrors `desktopApp/src/main/kotlin/com/singularity/todo/main.kt` with one
 * substitution: [fakeRemoteConfigModule] replaces the network-backed
 * [RemoteConfigPort].
 *
 * [overrides] loads last and therefore wins Koin's last-definition-wins rule,
 * which is how a test swaps a repository for a fake.
 */
@OptIn(ExperimentalTestApi::class)
fun runDesktopAppTest(
    overrides: Module = module {},
    test: DesktopComposeUiTest.() -> Unit,
) = withTempUserHome {
    runDesktopComposeUiTest {
        val koin: Koin = koinApplication {
            modules(
                fakeRemoteConfigModule(),
                platformModule(),
                coreLoggingModule(),
                *domainModule().toTypedArray(),
                gateModule(RELEASES_URL),
                overrides,
            )
        }.koin
        setContent { KoinContext(koin) { App(deeplinkViewId = null, deeplinkTaskId = null) } }
        test()
    }
}
