package com.singularity.todo.core.di

import org.junit.jupiter.api.Tag
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The optional-dependency shape: a dependency that is *not available* must
 * degrade the feature, not take the screen down.
 *
 * The distinction the function makes is the whole point: a definition that is
 * not registered is the normal case for an optional dependency, while a
 * definition that is registered and cannot be created is a defect — and the
 * defect has to be reported, or a broken binding would be indistinguishable
 * from a feature that is simply switched off.
 */
@Tag("fast")
class OptionalDependenciesTest {

    /** A type whose binding throws when it is created. */
    private class Broken

    /** Carries the optional dependency, so a test can look at what was resolved. */
    private class Holder(val optional: Broken?)

    @Test
    fun a_missing_definition_is_not_reported() {
        var reported: Throwable? = null
        val app = koinApplication {
            modules(
                module {
                    single<Holder> { Holder(getOptionalOrNull<Broken> { reported = it }) }
                },
            )
        }
        try {
            assertNull(app.koin.get<Holder>().optional)
            assertNull(
                reported,
                "a missing definition is the normal case for an optional dependency, not a failure",
            )
        } finally {
            app.close()
        }
    }

    @Test
    fun a_broken_definition_is_reported_and_resolves_to_null() {
        var reported: Throwable? = null
        val app = koinApplication {
            modules(
                module {
                    factory<Broken> { error("the binding is broken") }
                    single<Holder> { Holder(getOptionalOrNull<Broken> { reported = it }) }
                },
            )
        }
        try {
            assertNull(
                app.koin.get<Holder>().optional,
                "a broken binding must degrade the feature, not throw out of the graph",
            )
            val failure = assertNotNull(reported, "a broken binding must be reported")
            assertEquals(
                "the binding is broken",
                failure.message,
                "the report must name the root cause, not the wrapper Koin put around it",
            )
        } finally {
            app.close()
        }
    }
}
