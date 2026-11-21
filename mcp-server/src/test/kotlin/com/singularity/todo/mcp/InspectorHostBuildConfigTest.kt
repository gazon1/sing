package com.singularity.todo.mcp

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The generated build capability is the release gate, and this test reads the constant the
 * build actually compiled in.
 *
 * The value is not supplied by the test: a test that passes `false` to itself proves the
 * `Boolean` gate and nothing about the artifact. This one reads
 * [InspectorHostBuildConfig.INSPECTOR_HOST_ENABLED] and compares it with the Gradle
 * property the build was invoked with, which the build hands over as the
 * `singularity.inspector.host.enabled` system property.
 *
 * Both directions are asserted, because both are supported configurations:
 * - a default build (what a release artifact is packaged from) must generate `false`;
 * - a build that passed `-Psingularity.inspector.host.enabled=true` must generate `true`,
 *   or the host-enabled development build this module exists for would not work.
 *
 * Asserting `false` unconditionally was the earlier shape, and it made
 * `:mcp-server:test -Psingularity.inspector.host.enabled=true` fail: the test rejected the
 * one configuration it was meant to exercise.
 */
@Tag("fast")
class InspectorHostBuildConfigTest {
    @Test
    fun `the generated constant follows the build property`() {
        assertEquals(
            expectedBuildProperty(),
            InspectorHostBuildConfig.INSPECTOR_HOST_ENABLED,
            "The generated build config does not match the property this build was invoked " +
                "with, so the release gate and the compiled-in capability disagree.",
        )
    }

    @Test
    fun `a default build compiles the inspector host out`() {
        // The default (no `-P`) is the configuration a release artifact is packaged from:
        // it must be off, and nothing at runtime can turn it on.
        if (expectedBuildProperty()) return
        assertFalse(
            InspectorHostBuildConfig.INSPECTOR_HOST_ENABLED,
            "A default build must compile the screen-inspector host out.",
        )
    }

    private fun expectedBuildProperty(): Boolean =
        System
            .getProperty("singularity.inspector.host.enabled")
            ?.toBooleanStrictOrNull()
            ?: error(
                "The build must pass -Dsingularity.inspector.host.enabled to this test JVM: " +
                    "without it the generated constant has nothing to be checked against.",
            )
}
