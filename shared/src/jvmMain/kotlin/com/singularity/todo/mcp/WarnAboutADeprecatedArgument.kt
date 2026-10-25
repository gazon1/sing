package com.singularity.todo.mcp

/**
 * The step that says the legacy argument is deprecated.
 *
 * The label still works, and still means what it always meant: a manifest in the wild
 * passes it, and a silently reinterpreted label would serve the wrong profile. The
 * step says it is on its way out and stops there.
 */
internal object WarnAboutADeprecatedArgument : ToolServerStartupStep {

    override val title: String = "warn about a deprecated argument"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        if (context.legacyLabel == null) return null
        startupLog.w {
            "--profile is deprecated and will be removed; use --profile-id with a profile id. " +
                "The label still works, and still means what it always meant."
        }
        return null
    }
}
