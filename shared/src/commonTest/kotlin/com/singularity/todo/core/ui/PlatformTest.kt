package com.singularity.todo.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse

class PlatformTest {
    @Test
    fun isAndroidPlatform_is_false_on_jvm() {
        assertFalse(isAndroidPlatform)
    }
}
