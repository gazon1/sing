package com.singularity.todo.mcp

import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * The step that reads the remote configuration.
 *
 * The remote configuration is read once, here, and the answer is handed to the
 * registrar. Reading it per tool call would let the exposed toolbox change under a
 * running client; reading it once means the toolbox a client sees is the toolbox the
 * server announced.
 */
internal object ReadTheConfiguration : ToolServerStartupStep {

    override val title: String = "read the configuration"

    override suspend fun run(context: ToolServerStartupContext): ToolServerStartup.Refused? {
        val dependencies = checkNotNull(context.dependencies) {
            "The order does not resolve the dependencies before this step."
        }
        val configuration = read(dependencies)
        context.configuration = configuration
        startupLog.i {
            "Tool configuration: ${configuration.status}, " +
                "${configuration.flags.count { it.value == false }} tool(s) disabled"
        }
        return null
    }
}

/**
 * The remote-config flags that decide which tools are exposed, and how they were read.
 *
 * Read through the existing port, which is bounded and absorbs its own failures: a
 * timeout yields the last known good snapshot, and with no cached snapshot at all the
 * defaults are returned — which enable every tool.
 *
 * The policy is **fail-open**: a tool that an operator switched off is exposed again
 * when the configuration cannot be read. That is a deliberate choice — the flags are an
 * operational control for a broken tool, not a safety boundary — and it is why the
 * status travels with the flags all the way to `describe_toolbox`, so an operator can
 * see that the switch was *not* applied rather than assume it was.
 */
// The configuration is fail-open: an unreadable configuration exposes every tool, which
// is a deliberate choice and not an accident.
@Suppress("TooGenericExceptionCaught") // fail-open by design: the flags are an operational control
private suspend fun read(dependencies: ServerDependencies): ToolConfiguration = try {
    val snapshot = dependencies.remoteConfig.snapshot()
    ToolConfiguration(
        flags = snapshot.mcpToolFlags,
        status = ToolsetSnapshot.statusOf(snapshot, Clock.System.now().toEpochMilliseconds()),
        fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis.takeIf { it > 0L },
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    startupLog.w { "Remote configuration unavailable, every tool is enabled: ${e.message}" }
    ToolConfiguration(
        flags = emptyMap(),
        status = ConfigurationStatus.ERROR,
        fetchedAtEpochMillis = null,
    )
}
