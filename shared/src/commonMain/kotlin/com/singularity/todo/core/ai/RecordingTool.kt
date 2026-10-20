package com.singularity.todo.core.ai

import ai.koog.agents.core.tools.Tool
import com.singularity.todo.core.observability.ToolCallContext
import com.singularity.todo.core.observability.ToolCallError
import com.singularity.todo.core.observability.ToolCallRecorder
import com.singularity.todo.core.observability.ToolCallSource

/**
 * A tool that records how it was used, and then behaves exactly like the tool it wraps.
 *
 * ## Why a decorator, and where it is not used
 *
 * The MCP server measures at its dispatch boundary instead: the registrar already owns
 * the call, the arguments and the outcome classification, and wrapping every tool there
 * would break the `SimpleTool` cast its decoder relies on. This decorator exists for
 * the *in-app* agent, where the only place that sees a tool call is the tool itself.
 *
 * ## What it must preserve
 *
 * The wrapper is transparent, and "transparent" has to be checked rather than assumed:
 *
 * * the **name and description** the agent reads — they come from the delegate's
 *   descriptor, not from a copy;
 * * the **argument type**, so the arguments still decode;
 * * the **result type**, so the agent's expectations still hold;
 * * the **metadata** the tool carries.
 *
 * A wrapper that compiles but drops the descriptor would still "work" and would quietly
 * tell the agent something different about the tool — which is the failure this class of
 * defect produces.
 */
class RecordingTool<TArgs, TResult>(
    private val delegate: Tool<TArgs, TResult>,
    private val recorder: ToolCallRecorder,
    private val source: ToolCallSource,
    private val context: () -> ToolCallContext = { ToolCallContext() },
    private val classify: (Throwable) -> ToolCallError? = { null },
) : Tool<TArgs, TResult>(delegate.argsType, delegate.resultType, delegate.descriptor, delegate.metadata) {

    override suspend fun execute(args: TArgs): TResult = recorder.timed(
        tool = delegate.descriptor.name,
        source = source,
        context = context(),
        classify = classify,
    ) {
        delegate.execute(args)
    }
}
