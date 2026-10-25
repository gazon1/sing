package com.singularity.todo.mcp

/**
 * The step that refuses a profile id nobody owns.
 *
 * A profile that does not exist is not a profile the server may serve: the tools would
 * write under an id nobody owns, and the user would find their tasks missing rather
 * than told. Never a fallback.
 */
internal object RefuseAnUnknownProfile : ToolServerStartupStep {

    override val title: String = "refuse a profile that does not exist"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        val dependencies = checkNotNull(context.dependencies) {
            "The order does not resolve the dependencies before this step."
        }
        val refusal = refusalForUnknownProfile(context.fixedProfileId) { id -> profileExists(dependencies, id) }
        if (refusal == null) return null
        startupLog.e { "No profile with id ${context.fixedProfileId}" }
        return refusal
    }
}

/**
 * The refusal a profile id nobody owns earns, or null when the id may be served.
 *
 * The decision is a value rather than an exit, so that it can be checked without a
 * process, a database and a container — and the lookup is a parameter, so that the
 * check does not have to be the one that opens the database.
 */
internal suspend fun refusalForUnknownProfile(
    fixedProfileId: String?,
    profileExists: suspend (String) -> Boolean,
): ToolServerStartup.Refused? =
    if (fixedProfileId != null && !profileExists(fixedProfileId)) {
        ToolServerStartup.Refused(
            code = -32002,
            message = "No profile with that id exists.",
            exitCode = EXIT_USAGE,
        )
    } else {
        null
    }

/** Whether a profile with this id exists. */
private suspend fun profileExists(dependencies: ServerDependencies, profileId: String): Boolean =
    dependencies.database.profileDao().getById(profileId) != null
