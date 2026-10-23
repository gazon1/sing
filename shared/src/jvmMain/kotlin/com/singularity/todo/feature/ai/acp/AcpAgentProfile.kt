package com.singularity.todo.feature.ai.acp

/**
 * An external agent the application can host.
 *
 * ## Data, not behaviour
 *
 * A registry entry says *what to launch*. It says nothing about how the agent talks, how
 * it fails, or what it may do — that is the protocol's business, and the client's.
 *
 * ## The command is visible before it is run
 *
 * The user sees the exact command before connecting. That is not a convenience: an
 * external agent is a third-party program, installed by the user, on the user's
 * machine, and the application is about to start it. A command that cannot be seen is a
 * command that cannot be checked, and the people best placed to check it are the ones
 * who never see it.
 *
 * ## Never a shell string
 *
 * [command] is an executable and [args] are arguments, and they are passed as a list.
 * Joining them into a string and handing that to a shell would give every argument a way
 * out of being data.
 */
data class AcpAgentProfile(
    /** Stable identifier. Unique within the registry. */
    val id: String,
    /** What the user reads. */
    val displayName: String,
    /** The executable to launch. */
    val command: String,
    /** The arguments, in order. */
    val args: List<String> = emptyList(),
    /** One line explaining what this agent is. */
    val description: String = "",
    /** Whether this is the entry that is preselected. */
    val isDefault: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "An agent needs a stable identifier" }
        require(displayName.isNotBlank()) { "An agent needs a name the user can read" }
        require(command.isNotBlank()) { "An agent needs a command to launch" }
    }

    /** The command as the user reads it — for display, never for execution. */
    fun commandLine(): String = (listOf(command) + args).joinToString(" ")
}

/**
 * The agents this application offers.
 *
 * ## The entries
 *
 * The Gemini CLI in its ACP mode is the default: it speaks the protocol from a single
 * installed binary, with nothing else to arrange first.
 *
 * The Claude Agent and Codex entries are *bridges* — Node packages that speak ACP on
 * behalf of an agent that does not. A bridge needs a Node runtime, and that is a
 * prerequisite of those entries rather than of the application: the connect failure says
 * so, and names the command that failed, instead of failing opaquely.
 *
 * The two bridges live under the `@agentclientprotocol` scope, which is where the
 * protocol's own maintainers publish them. The older `@zed-industries` packages still
 * resolve and still work, but npm marks them deprecated and no new version will land
 * there — so the registry names the scope that will still be maintained.
 *
 * ## Exactly one default
 *
 * The registry is data, and the data has one rule that is checked rather than hoped
 * for: exactly one entry is preselected. A registry with no default opens on nothing;
 * one with two defaults opens on whichever the list happens to hold first, and the
 * preselection stops meaning anything.
 */
object AcpAgentRegistry {

    /** The Gemini CLI, in the mode that speaks the Agent Client Protocol. */
    val gemini = AcpAgentProfile(
        id = "gemini-cli",
        displayName = "Gemini CLI",
        command = "gemini",
        args = listOf("--experimental-acp"),
        description = "The Gemini CLI in its Agent Client Protocol mode.",
        isDefault = true,
    )

    /** The bridge that puts Claude Code on the protocol. Needs a Node runtime. */
    val claude = AcpAgentProfile(
        id = "claude-code",
        displayName = "Claude Code",
        command = "npx",
        args = listOf("@agentclientprotocol/claude-agent-acp"),
        description = "The Claude Code bridge. Needs a Node runtime and the bridge installed.",
    )

    /** The bridge that puts Codex on the protocol. Needs a Node runtime. */
    val codex = AcpAgentProfile(
        id = "codex",
        displayName = "Codex",
        command = "npx",
        args = listOf("@agentclientprotocol/codex-acp"),
        description = "The Codex bridge. Needs a Node runtime and the bridge installed.",
    )

    /** Every known agent, in the order the user reads them. */
    val entries: List<AcpAgentProfile> = listOf(gemini, claude, codex)

    /** The entry that is preselected. */
    val default: AcpAgentProfile = entries.single { it.isDefault }

    /** The entry with this identifier, or null when there is none. */
    fun byId(id: String): AcpAgentProfile? = entries.firstOrNull { it.id == id }
}
