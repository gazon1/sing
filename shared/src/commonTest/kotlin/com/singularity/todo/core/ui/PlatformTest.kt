package com.singularity.todo.core.ui

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse

@Tag("fast")
class PlatformTest {
    @Test
    fun isAndroidPlatform_is_false_on_jvm() {
        assertFalse(isAndroidPlatform)
    }
}
