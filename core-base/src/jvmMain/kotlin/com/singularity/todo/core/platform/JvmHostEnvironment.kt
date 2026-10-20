package com.singularity.todo.core.platform

/**
 * Desktop actual of [HostEnvironmentPort], over `java.lang.System` properties.
 *
 * This is the JVM seam: the properties themselves do not exist off the JVM, which
 * is exactly why the interface exists. Both answers fall back to `user.home` rather
 * than to an empty string, because a caller resolving a path against a blank base
 * ends up with a relative path — which resolves against whatever the process
 * happens to consider current, silently.
 *
 * Lives in `:core-base` rather than in `:shared`'s platform module because
 * `:adr-cli` needs it and `:adr-cli` must not depend on the application.
 */
class JvmHostEnvironment : HostEnvironmentPort {

    override fun workingDirectory(): String = property("user.dir")

    override fun homeDirectory(): String = property("user.home")

    /**
     * `~/.singularity-todo` — the same directory the application database lives in.
     *
     * A subdirectory of the home directory rather than the home directory itself: the
     * home directory belongs to the user, and an application that scatters its files
     * there is a guest that forgot to introduce itself.
     */
    override fun applicationDataDirectory(): String =
        property("user.home") + "/.singularity-todo"

    private fun property(key: String): String =
        System.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home").orEmpty()
}