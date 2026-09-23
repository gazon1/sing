package com.singularity.todo.core.log

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertContains

class DebugInfoTest {

    @Test
    fun `debugInfo contains version and build type`() {
        val info = debugInfo("1.2.3", isDebug = true)
        assertContains(info, "v1.2.3")
        assertContains(info, "debug")
    }

    @Test
    fun `debugInfo release build type`() {
        val info = debugInfo("0.1.0", isDebug = false)
        assertContains(info, "release")
    }

    @Test
    fun `debugInfo contains OS description`() {
        val info = debugInfo("1.0.0", isDebug = true)
        val os = osDescription()
        assertContains(info, os)
    }

    @Test
    fun `debugInfo contains timezone`() {
        val info = debugInfo("1.0.0", isDebug = false)
        val tz = TimeZone.currentSystemDefault().id
        assertContains(info, tz)
    }

    @Test
    fun `debugInfo contains locale`() {
        val info = debugInfo("1.0.0", isDebug = false)
        assertContains(info, "Locale:")
    }

    @Test
    fun `logStartup does not throw`() {
        logStartup("1.0.0", isDebug = true)
        logStartup("0.1.0", isDebug = false)
    }
}
