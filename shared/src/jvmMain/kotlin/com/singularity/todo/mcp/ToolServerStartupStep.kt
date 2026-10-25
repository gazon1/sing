package com.singularity.todo.mcp

/**
 * One step of the tool server's start-up.
 *
 * ## Why a step is a type
 *
 * The order the steps run in is a contract, and not with the client alone: the shutdown
 * hook has to be installed after the server exists, because it closes it, and a refusal
 * has to happen before the container is started, because a refusal after it leaves a
 * half-started process behind.
 *
 * When the steps were functions in one file, only the sequence of statements knew that
 * order. A step that is a type has a name, and the order is a list of those names, so
 * the order can be read — and pinned by a test — without reading the bodies.
 *
 * @see ToolServerStartupOrder for the order itself.
 */
internal interface ToolServerStartupStep {

    /**
     * The name of the step.
     *
     * It is what the order is written down in, and what a test that pins the order
     * compares.
     */
    val title: String

    /**
     * Runs the step.
     *
     * @param context the state the steps share.
     * @return the refusal that stops the start-up, or null to carry on to the next step.
     */
    suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused?
}
