package com.singularity.todo.mcp

/**
 * The start-up's steps, in the order they run.
 *
 * ## What the order is for
 *
 * The order is a contract with three things:
 *
 * - the shutdown hook, which has to be installed after the server exists, because it
 *   closes it;
 * - the refusals, which have to happen before the container is started, because a
 *   refusal after it leaves a half-started process behind;
 * - the client, which is told one thing and whose process leaves with another — so the
 *   step that refuses is the step that found the reason, and the refusal carries both.
 *
 * ## The whole sequence
 *
 * The entry point's own steps are not here: it reserves the protocol channel before
 * the start-up and serves the frames after it. The twelve steps of the whole thing are
 * therefore:
 *
 *  1. reserve the protocol channel — the entry point
 *  2. parse the arguments — the entry point
 *  3. refuse an ambiguous request — [RefuseAnAmbiguousRequest]
 *  4. warn about a deprecated argument — [WarnAboutADeprecatedArgument]
 *  5. build the graph — [BuildTheGraph]
 *  6. resolve the dependencies — [ResolveTheDependencies]
 *  7. verify the database — [VerifyTheDatabase]
 *  8. refuse a profile that does not exist — [RefuseAnUnknownProfile]
 *  9. bootstrap the profiles — [BootstrapTheProfiles]
 *  10. read the configuration — [ReadTheConfiguration]
 *  11. build the server — [BuildTheServer]
 *  12. serve — the entry point
 *
 * and the shutdown hook — [InstallTheShutdownHook] — is installed by the start-up,
 * between the server being built and the entry point serving.
 */
internal object ToolServerStartupOrder {

    /**
     * The steps, in the order they run.
     *
     * The list is the order: the runner walks it, and a test that pins it is what says
     * the order did not change by accident.
     */
    val steps: List<ToolServerStartupStep> = listOf(
        RefuseAnAmbiguousRequest,
        WarnAboutADeprecatedArgument,
        BuildTheGraph,
        ResolveTheDependencies,
        VerifyTheDatabase,
        RefuseAnUnknownProfile,
        BootstrapTheProfiles,
        ReadTheConfiguration,
        BuildTheServer,
        InstallTheShutdownHook,
    )
}
