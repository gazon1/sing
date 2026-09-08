package com.singularity.todo.test

import org.junit.Test
import org.koin.core.qualifier.named
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Verifies that Koin scope isolation works as expected.
 */
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

        val app = startKoin {
            modules(loggingModule, profileModule, aiModule)
        }

        try {
            val result: String = app.koin.get(aiDep)
            assert(result == "profile-singleton") { "Expected 'profile-singleton' but got '$result'" }
        } finally {
            stopKoin()
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

        val domainModuleList: List<org.koin.core.module.Module> = listOf(
            tasksModule,
            profileModule,
            aiModule,
        )

        val app = startKoin {
            modules(platformModule, *domainModuleList.toTypedArray())
        }

        try {
            val result: String = app.koin.get(aiDep)
            assert(result == "profile-singleton") { "Expected 'profile-singleton' but got '$result'" }
        } finally {
            stopKoin()
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

        fun domainModule(): List<org.koin.core.module.Module> = listOf(
            tasksModule,
            profileModule,
            aiModule,
        )

        val app = startKoin {
            modules(platformModule, *domainModule().toTypedArray())
        }

        try {
            val result: String = app.koin.get(aiDep)
            assert(result == "profile-singleton") { "Expected 'profile-singleton' but got '$result'" }
        } finally {
            stopKoin()
        }
    }
}
