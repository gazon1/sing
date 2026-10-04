package com.singularity.todo.test

import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Verifies that Koin scope isolation works as expected.
 */
@Tag("fast")
class ScopeIsolationTest {

    private val profileStr = named("profile-string")
    private val aiDep = named("ai-dep")

    @Test
    fun `sibling modules registered via separate args share root scope`() {
        val loggingModule = module {
            single<String> { "logging-singleton" }
        }
        val profileModule = module {
            single(profileStr) { "profile-singleton" }
        }
        val aiModule = module {
            single(aiDep) { get<String>(profileStr) }
        }

        val app = org.koin.dsl.koinApplication {
            modules(loggingModule, profileModule, aiModule)
        }

        try {
            val result: String = app.koin.get(aiDep)
            assert(result == "profile-singleton") { "Expected 'profile-singleton' but got '$result'" }
        } finally {
            app.close()
        }
    }

    @Test
    fun `domainModule pattern — modules list spread as varargs`() {
        val platformModule = module {
            single<String> { "platform" }
        }
        val profileModule = module {
            single(profileStr) { "profile-singleton" }
        }
        val aiModule = module {
            factory(aiDep) { get<String>(profileStr) }
        }
        val tasksModule = module {
            single<String> { "tasks" }
        }

        val domainModuleList: List<Module> = listOf(
            tasksModule,
            profileModule,
            aiModule,
        )

        val app = org.koin.dsl.koinApplication {
            modules(platformModule, *domainModuleList.toTypedArray())
        }

        try {
            val result: String = app.koin.get(aiDep)
            assert(result == "profile-singleton") { "Expected 'profile-singleton' but got '$result'" }
        } finally {
            app.close()
        }
    }

    @Test
    fun `module returning List spreads correctly into modules varargs`() {
        // This is the EXACT pattern used in domainModule():
        // modules(platformModule(), *domainModule().toTypedArray())
        val platformModule = module {
            single<String> { "platform" }
        }
        val profileModule = module {
            single(profileStr) { "profile-singleton" }
        }
        val aiModule = module {
            factory(aiDep) { get<String>(profileStr) }
        }
        val tasksModule = module {
            single<String> { "tasks" }
        }

        fun domainModule(): List<Module> = listOf(
            tasksModule,
            profileModule,
            aiModule,
        )

        val app = org.koin.dsl.koinApplication {
            modules(platformModule, *domainModule().toTypedArray())
        }

        try {
            val result: String = app.koin.get(aiDep)
            assert(result == "profile-singleton") { "Expected 'profile-singleton' but got '$result'" }
        } finally {
            app.close()
        }
    }
}
