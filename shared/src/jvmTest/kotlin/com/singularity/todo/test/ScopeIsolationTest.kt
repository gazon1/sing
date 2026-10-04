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
    fun `a composed module list shares the root scope, not a child`() {
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
            modules(listOf(platformModule) + domainModuleList)
        }

        try {
            // aiModule resolves `profileStr`, which is defined in a *different* module in
            // the same list. This is the property that composition must not break: if the
            // composed list were assembled as child scopes, this lookup would fail rather
            // than resolve across the boundary.
            val result: String = app.koin.get(aiDep)
            assert(result == "profile-singleton") { "Expected 'profile-singleton' but got '$result'" }
        } finally {
            app.close()
        }
    }

    @Test
    fun `domainModule returning a List composes without a spread`() {
        // The shape production uses, from Modules.kt and the three app entry points:
        //     modules(listOf(platformModule(), coreLoggingModule()) + domainModule() + listOf(gateModule(…)))
        //
        // Not `*domainModule().toTypedArray()`. A spread is a dynamically-computed module
        // set, so the Koin compiler cannot verify the graph at that entry point and emits
        // KOIN-W003 — a warning, which means nothing fails and the pattern can creep back
        // unnoticed. This test used to assert the spread was correct and name it "the EXACT
        // pattern used in domainModule()", which is how a banned shape survives a
        // codebase that has since moved on.
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
            modules(listOf(platformModule) + domainModule())
        }

        try {
            val result: String = app.koin.get(aiDep)
            assert(result == "profile-singleton") { "Expected 'profile-singleton' but got '$result'" }
        } finally {
            app.close()
        }
    }
}
