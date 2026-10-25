package com.singularity.todo.mcp

/**
 * The step that refuses a request that says two different things.
 *
 * It is the first step, and it is first for a reason: a refusal after the container is
 * started leaves a half-started process behind, and the client is told one thing while
 * the process leaves with another.
 */
internal object RefuseAnAmbiguousRequest : ToolServerStartupStep {

    override val title: String = "refuse an ambiguous request"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        val refusal = refusalFor(context.selection) ?: return null
        startupLog.e {
            "Both --profile and --profile-id were given; refusing to guess which profile to use."
        }
        return refusal
    }
}

/**
 * The refusal a profile selection earns, or null when the selection may be served.
 *
 * Both arguments at once is a request that says two different things, and neither of
 * them can be obeyed. Failing here is the honest answer; picking one would silently
 * serve a profile the caller did not ask for.
 *
 * The decision is a value rather than an exit, so that it can be checked without a
 * process, a database and a container.
 */
internal fun refusalFor(selection: ProfileSelection): ToolServerStartup.Refused? =
    if (selection is ProfileSelection.Ambiguous) {
        ToolServerStartup.Refused(
            code = -32002,
            message = "Conflicting profile arguments: pass either --profile or --profile-id, not both.",
            exitCode = EXIT_USAGE,
        )
    } else {
        null
    }
