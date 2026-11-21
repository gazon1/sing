package com.singularity.todo

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The generated build capability is the release gate, and this test reads the constant the
 * build actually compiled in.
 *
 * The value is not passed to the test by hand: a test that supplies `false` itself proves
 * the `Boolean` gate and nothing about the artifact. This one reads
 * [DesktopInspectorHostBuildConfig.INSPECTOR_HOST_ENABLED], which the
 * `generateDesktopInspectorHostBuildConfig` task writes from the Gradle property — so the
 * default build (and every packaged release build) fails here if the host is compiled in
 * by default.
 *
 * The negative direction is the one that must never regress: the positive direction is
 * reachable only with `-Psingularity.inspector.host.enabled=true`, so asserting it would
 * pin the developer's own build flag rather than the invariant.
 */
@Tag("fast")
class DesktopInspectorHostBuildConfigTest {
    @Test
    fun `the default build compiles the inspector host out`() {
        assertFalse(
            DesktopInspectorHostBuildConfig.INSPECTOR_HOST_ENABLED,
            "The screen-inspector host must be off unless the build explicitly enables it. " +
                "A packaged release artifact is built without " +
                "-Psingularity.inspector.host.enabled=true, so this constant is false there.",
        )
    }
}
