package com.singularity.todo

import androidx.navigation3.runtime.deeplink.DeepLinkRequest
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.deeplink.DeepLinkRegistry
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.testPlatformModule
import org.junit.jupiter.api.Tag
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deep-link registry must be *resolvable* from the real graph.
 *
 * A registration that cannot be created is the failure mode the DI checks in
 * this repo keep running into: the binding is there, the class compiles, and
 * the first link the user taps throws. This test resolves the registry from the
 * same module set the app starts with and asks it to open a link.
 *
 * The graph is built from the same pieces the desktop harness uses
 * (`domainModule()` + `testPlatformModule()`), so nothing here touches the
 * developer's home directory, a real database or a real port.
 */
@Tag("fast")
class DeepLinkGraphTest {

    @Test
    fun `the registry resolves a link from the domain graph`() {
        val app = koinApplication {
            // KOIN-W003 here is an accepted, documented tradeoff — the same one
            // `DomainGraphResolutionTest` takes: the module set is computed at
            // runtime, so the compile-time graph check cannot see it. This test
            // is the runtime validation that replaces it.
            @Suppress("KoinCompilationForInternal")
            modules(
                listOf(coreLoggingModule()) +
                    domainModule() +
                    listOf(testPlatformModule()),
            )
        }
        try {
            val registry = app.koin.get<DeepLinkRegistry>()

            val target = registry.resolve(DeepLinkRequest("singularity://task/t1"))

            assertEquals(
                AppDestination.AgendaGraph(AgendaStartRoute.Today),
                target?.tab,
            )
            assertEquals(
                AppDestination.TasksGraph(
                    AppDestination.TasksStartRoute.Detail(TaskId("t1")),
                ),
                target?.screen,
            )
        } finally {
            app.close()
        }
    }
}
