package com.singularity.todo.core.platform

/**
 * Desktop actual of [HostEnvironmentPort], over `java.lang.System` properties.
 *
 * This is the JVM seam: the properties themselves do not exist off the JVM, which
 * is exactly why the interface exists. Both answers fall back to `user.home` rather
 * than to an empty string, because a caller resolving a path against a blank base
 * ends up with a relative path — which resolves against whatever the process
 * happens to consider current, silently.
 */
class JvmHostEnvironment : HostEnvironmentPort {

    override fun workingDirectory(): String = property("user.dir")

    override fun homeDirectory(): String = property("user.home")

    private fun property(key: String): String =
        System.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home").orEmpty()
}
