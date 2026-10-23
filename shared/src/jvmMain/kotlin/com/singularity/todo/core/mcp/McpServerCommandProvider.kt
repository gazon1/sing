package com.singularity.todo.core.mcp

import com.singularity.todo.feature.profile.ProfileId
import java.io.File

/**
 * The command that starts the tool server, or the reason there is none.
 *
 * The two cases are not the same thing: a command that cannot be produced is a
 * **diagnosis**, and it has to say what was looked for and where. "Tool access
 * unavailable" is not a diagnosis; "the packaged launcher at /opt/singularity-todo/bin
 * is not executable" is.
 */
sealed interface ToolServerCommand {

    /** A command that starts the tool server. */
    data class Available(val command: String, val args: List<String>) : ToolServerCommand

    /**
     * No command could be produced.
     *
     * @param reason what was looked for and where it was expected. It names a path:
     *   the person who has to fix this is the one who can see the filesystem.
     */
    data class Unavailable(val reason: String) : ToolServerCommand
}

/**
 * What the process knows about the machine it runs on.
 *
 * The facts are read from the running process rather than assumed, because the two
 * ways this application runs differ in exactly the facts that decide the command:
 * a packaged image has no `java` to run and does have an executable of its own, and a
 * development run has both a JVM and no executable.
 *
 * @property packaged whether this process runs from a packaged image.
 * @property launcher the application's own executable, when there is one.
 * @property javaBinary the JVM running this process.
 * @property classpath the classpath this process was started with.
 */
data class ToolServerEnvironment(
    val packaged: Boolean,
    val launcher: String?,
    val javaBinary: String?,
    val classpath: String,
) {
    companion object {

        /**
         * Reads the facts from the running process.
         *
         * **Packaged or not** — `jpackage.app-version` is set by the packaging tool in
         * the launcher's own options, so it is present in a packaged image and absent
         * everywhere else. It is read rather than guessed from the layout, because the
         * layout differs per platform and a guess would be wrong on two of the three.
         *
         * **The launcher** — the process' own executable. The packaging tool's launcher
         * loads the JVM into its own process, so this is the launcher and not the JVM.
         */
        fun current(): ToolServerEnvironment {
            val javaHome = System.getProperty("java.home").orEmpty()
            val windows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
            val javaName = if (windows) "java.exe" else "java"
            return ToolServerEnvironment(
                packaged = System.getProperty("jpackage.app-version") != null,
                launcher = ProcessHandle.current().info().command().orElse(null),
                javaBinary = File(javaHome, "bin/$javaName").takeIf { it.isFile }?.path,
                classpath = System.getProperty("java.class.path").orEmpty(),
            )
        }
    }
}

/**
 * Produces the command an external agent is handed to reach the user's tasks.
 *
 * ## What the command is
 *
 * **The application's own executable, in tool-server mode.**
 *
 * The application already runs headless work through its own launcher — a reminder
 * unit invokes `singularity-todo fire-reminder` — and the reason is written down where
 * that decision lives: a second helper binary is work that silently does not happen on
 * every machine where nobody remembered to install it. The tool server is the same
 * kind of work: it is the same program, asked for a different job.
 *
 * So the packaged application hands the agent a command that is its own launcher plus
 * `--mcp-server`, and the launcher starts the server instead of the window. The server
 * is then, by construction, the one that runs on the runtime shipped with the
 * application — a packaged machine has no system Java to fall back to, and this
 * command does not need one.
 *
 * ## Why the tool server's code lives in the shared runtime
 *
 * The command starts *this* application, so the server's classes must be on this
 * application's classpath. That is why the implementation moved into the shared JVM
 * runtime and the `:mcp-server` module became a launcher over it: one server, one
 * toolset, and the agent reaches exactly the tools the documentation describes.
 *
 * ## Development
 *
 * A development run has no packaged launcher to hand over, so the command is the JVM
 * that is running the application, with the application's own classpath and entry
 * point. The shape of the command is the same in both cases — an executable and a list
 * of arguments — which is what the protocol asks for.
 *
 * ## What happens when there is no command
 *
 * The session starts without tool access and the user is told, before the first
 * message, that the agent cannot see their tasks. That is deliberately not a failure:
 * the connection is fine, and the agent is simply blind to the user's data.
 */
class McpServerCommandProvider(private val environment: ToolServerEnvironment = ToolServerEnvironment.current()) {

    /**
     * The command that starts the tool server for [profileId].
     *
     * @param profileId the profile the server works as. It is passed on the command
     *   line so the server never opens the stored active-profile state: that store
     *   takes a file lock, and the application is holding it.
     */
    fun commandFor(profileId: ProfileId): ToolServerCommand {
        // One argument, not two: the tool server parses `--profile-id=ID`, and a
        // command that says `--profile-id ID` is a command it does not recognise —
        // and a command it does not recognise is a command that silently serves the
        // wrong profile.
        val profileArgs = listOf("$PROFILE_ID_FLAG=${profileId.value}")
        return if (environment.packaged) {
            packagedCommand(profileArgs)
        } else {
            developmentCommand(profileArgs)
        }
    }

    private fun packagedCommand(profileArgs: List<String>): ToolServerCommand {
        val launcher = environment.launcher
            ?: return ToolServerCommand.Unavailable(
                "the running process does not report its own executable, so the tool server " +
                    "cannot be started from the application's launcher",
            )
        if (!File(launcher).canExecute()) {
            return ToolServerCommand.Unavailable(
                "the application's own executable at $launcher is not executable, so the " +
                    "tool server cannot be started",
            )
        }
        return ToolServerCommand.Available(
            command = launcher,
            args = listOf(TOOL_SERVER_FLAG) + profileArgs,
        )
    }

    private fun developmentCommand(profileArgs: List<String>): ToolServerCommand {
        val java = environment.javaBinary
            ?: return ToolServerCommand.Unavailable(
                "no Java runtime was found at ${System.getProperty("java.home")}/bin, so the " +
                    "tool server cannot be started outside a packaged application",
            )
        if (!File(java).canExecute()) {
            return ToolServerCommand.Unavailable(
                "the Java runtime at $java is not executable, so the tool server cannot be " +
                    "started outside a packaged application",
            )
        }
        if (environment.classpath.isBlank()) {
            return ToolServerCommand.Unavailable(
                "the running process reports an empty classpath, so the tool server cannot " +
                    "be started outside a packaged application",
            )
        }
        return ToolServerCommand.Available(
            command = java,
            args = listOf("-cp", environment.classpath, DESKTOP_MAIN_CLASS, TOOL_SERVER_FLAG) + profileArgs,
        )
    }

    companion object {
        /** The flag that turns the application into the tool server. */
        const val TOOL_SERVER_FLAG = "--mcp-server"

        /** The flag that tells the server which profile to work as. */
        const val PROFILE_ID_FLAG = "--profile-id"

        /** The desktop application's entry point, which dispatches on the flag above. */
        const val DESKTOP_MAIN_CLASS = "com.singularity.todo.MainKt"
    }
}
