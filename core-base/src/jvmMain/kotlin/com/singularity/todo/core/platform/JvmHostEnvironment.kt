package com.singularity.todo.core.platform

/**
 * Desktop actual of [HostEnvironmentPort], over `java.lang.System` properties.
 *
 * This is the JVM seam: the properties themselves do not exist off the JVM, which
 * is exactly why the interface exists. Both answers come from the home this environment
 * was given — `user.home` by default — rather than from an empty string, because a
 * caller resolving a path against a blank base ends up with a relative path — which
 * resolves against whatever the process happens to consider current, silently.
 *
 * Lives in `:core-base` rather than in `:shared`'s platform module because
 * `:adr-cli` needs it and `:adr-cli` must not depend on the application.
 */
class JvmHostEnvironment(
    /**
     * The directory this environment treats as the user's home, or `null` to read
     * `user.home`. Production wants the default; a test that builds the platform
     * bindings against a temporary root passes that root, so the ports reading this
     * environment stay inside it instead of reaching the developer's real home.
     */
    private val userHome: String? = null,
) : HostEnvironmentPort {
    override fun workingDirectory(): String = property("user.dir")

    override fun homeDirectory(): String = userHome ?: property("user.home")

    /**
     * `~/.singularity-todo` — the same directory the application database lives in.
     *
     * A subdirectory of the home directory rather than the home directory itself: the
     * home directory belongs to the user, and an application that scatters its files
     * there is a guest that forgot to introduce itself.
     */
    override fun applicationDataDirectory(): String = homeDirectory() + "/.singularity-todo"

    private fun property(key: String): String =
        System.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: userHome
            ?: System.getProperty("user.home").orEmpty()
}
