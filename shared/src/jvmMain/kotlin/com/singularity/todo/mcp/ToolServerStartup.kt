package com.singularity.todo.mcp

import co.touchlab.kermit.Logger
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.observability.ToolCallContext
import com.singularity.todo.core.process.Subprocess
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.feature.profile.ProfileBootstrapper
import com.singularity.todo.feature.profile.ProfileId
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.time.Clock

/**
 * The server's log.
 *
 * The tag is the one the entry point has always used: the tag is part of every line
 * that whoever reads the client's standard error sees, so it is not a thing this
 * split may change.
 */
private val log = Logger.withTag("Main")

// ─── What the start-up decided ────────────────────────────────────────────────

/**
 * What the server's start-up decided.
 *
 * ## Why the outcome is a value and not an exit
 *
 * The start-up used to be one function that reserved the protocol channel, parsed the
 * arguments, refused the ones it could not obey, started the container, opened the
 * database and built the server — and two of its refusals were `exitProcess` calls in
 * the middle of that sequence. So "what does the server do when it cannot start?"
 * could only be answered by reading the whole function, and the answer was not a
 * thing a test could hold.
 *
 * Now the start-up returns what it decided: a server that is ready to serve, or a
 * refusal that names the code the client is told, the message the client is told and
 * the code the process leaves with. The entry point acts on the outcome; the outcome
 * never acts on the process.
 */
internal sealed interface ToolServerStartup {

    /**
     * The server is up: its container is started, its database is open, its tools are
     * registered and its shutdown hook is installed.
     *
     * It has not served a frame yet — that is the entry point's next step.
     *
     * @property server the server, with every tool registered on it.
     * @property sessionContext the holder the session is bound to, once it exists.
     */
    data class Ready(val server: Server, val sessionContext: SessionContextHolder) : ToolServerStartup

    /**
     * The server refused to start.
     *
     * @property code the JSON-RPC error code the client is told.
     * @property message what the client is told. Neutral by contract: it says what
     *   happened, and it carries no path, no stack trace and no exception message.
     * @property exitCode the code the process leaves with.
     */
    data class Refused(val code: Int, val message: String, val exitCode: Int) : ToolServerStartup
}

/** The server could not start: its dependencies or its database are unavailable. */
internal const val EXIT_STARTUP = 1

/** The server was asked for something it cannot do: conflicting or unknown arguments. */
internal const val EXIT_USAGE = 2

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

// ─── The start-up ─────────────────────────────────────────────────────────────

/**
 * Starts the server: everything that has to have happened before the protocol loop
 * begins.
 *
 * ## What this owns
 *
 * The container, the database, the profiles, the tool configuration and the server
 * itself — and the refusals the server can answer before it serves anything. What it
 * does not own is the protocol channel and the serving loop: the entry point reserves
 * the channel, because both the refusal and the transport speak on it, and it holds
 * the process up until the client leaves.
 *
 * ## Why the refusals come back as values
 *
 * The client is told one thing and the process leaves with another, and both belong
 * to the refusal rather than to the step that found it. So a refusal travels as
 * [ToolServerStartup.Refused] — the code and the message the client sees, and the exit
 * code the process uses — and the entry point is the only thing that acts on it.
 */
internal suspend fun startToolServer(selection: ProfileSelection): ToolServerStartup {
    // Both arguments at once is a request that says two different things, and neither of
    // them can be obeyed. Failing here is the honest answer; picking one would silently
    // serve a profile the caller did not ask for.
    val refusal = refusalFor(selection)
    if (refusal != null) {
        log.e {
            "Both --profile and --profile-id were given; refusing to guess which profile to use."
        }
        return refusal
    }

    val fixedProfileId: String? = (selection as? ProfileSelection.FixedId)?.id
    val legacyLabel: String? = (selection as? ProfileSelection.LegacyLabel)?.name

    if (legacyLabel != null) {
        log.w {
            "--profile is deprecated and will be removed; use --profile-id with a profile id. " +
                "The label still works, and still means what it always meant."
        }
    }

    val dependencies = resolveServerDependencies(fixedProfileId)
    if (dependencies == null) {
        return ToolServerStartup.Refused(
            code = -32000,
            message = "The server could not start: its dependencies are unavailable.",
            exitCode = EXIT_STARTUP,
        )
    }

    val refusalBeforeServing = verifyTheServerMayServe(dependencies, fixedProfileId)
    if (refusalBeforeServing != null) {
        return refusalBeforeServing
    }

    // After Koin is up and DB is reachable, ensure default + agent profiles exist
    // and point DataStore at the agent profile when --profile=ai-agent (or any
    // other agent-like flag) was passed. This makes subsequent tool calls write
    // under the AI Agent's UUID instead of Personal's.
    bootstrapProfiles(dependencies, legacyLabel)

    // The remote configuration is read once, here, and the answer is handed to the
    // registrar. Reading it per tool call would let the exposed toolbox change under a
    // running client; reading it once means the toolbox a client sees is the toolbox the
    // server announced.
    val toolConfiguration = readToolConfiguration(dependencies)
    log.i {
        "Tool configuration: ${toolConfiguration.status}, " +
            "${toolConfiguration.flags.count { it.value == false }} tool(s) disabled"
    }

    log.i { "singularity-todo MCP server started" }

    val sessionContext = SessionContextHolder()
    val server = buildServer(dependencies, toolConfiguration, sessionContext)
    installShutdownHook(server)

    return ToolServerStartup.Ready(server = server, sessionContext = sessionContext)
}

/**
 * Starts the container and resolves the server's dependencies from it.
 *
 * The two failures it can have are the same failure to the client — the server's
 * dependencies are unavailable — so they are one null here and one refusal above. The
 * cause of each goes to the log, where whoever reads the standard error can see it.
 */
private fun resolveServerDependencies(fixedProfileId: String?): ServerDependencies? {
    if (!bootstrapKoin(fixedProfileId)) return null
    return resolveDependencies()
}

/**
 * The refusal a profile id nobody owns earns, or null when the id may be served.
 *
 * A profile that does not exist is not a profile the server may serve: the tools
 * would write under an id nobody owns, and the user would find their tasks missing
 * rather than told. Never a fallback.
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

/**
 * Checks the two things that decide whether this server may serve at all: the
 * database opens, and the profile the caller named exists.
 *
 * Both refusals travel as values, because the client is told one thing and the process
 * leaves with another, and neither belongs to the step that found it.
 */
private suspend fun verifyTheServerMayServe(
    dependencies: ServerDependencies,
    fixedProfileId: String?,
): ToolServerStartup.Refused? {
    if (!verifyDatabase(dependencies)) {
        return ToolServerStartup.Refused(
            code = -32001,
            message = "The server could not open its database.",
            exitCode = EXIT_STARTUP,
        )
    }
    val refusal = refusalForUnknownProfile(fixedProfileId) { id -> profileExists(dependencies, id) }
    if (refusal != null) {
        log.e { "No profile with id $fixedProfileId" }
        return refusal
    }
    return null
}

// ─── Bootstrap helpers ────────────────────────────────────────────────────────

/**
 * Starts Koin with the shared JVM platform [platformModule] and the shared [domainModule].
 *
 * The binding set is deliberately the SAME one the desktop app uses — a hand-rolled
 * subset here drifted and left `SyncWorkScheduler`/proposal DAOs unbound, which killed
 * `ToolRegistrar` at startup. Profile data isolation is handled post-Koin by
 * [ProfileBootstrapper.run] via the `user_id` scope on each DAO query; the
 * `--profile=NAME` argument is a label, not a directory suffix.
 *
 * On failure, logs the cause and returns false: the refusal the client is told is
 * the caller's to make, so that the client and the process are told the same thing.
 */
// The server's start-up has to fail in one place, whatever the failure was: a missing
// dependency, a locked store, a broken native library. The caller gets a JSON-RPC error
// and a non-zero exit code, not a stack trace.
@Suppress("TooGenericExceptionCaught") // start-up has to fail in one place, whatever the failure was
private fun bootstrapKoin(fixedProfileId: String?): Boolean = try {
    startKoin {
        // A process that was told which profile to serve never opens the store that holds
        // the active profile — the store takes a file lock, and the application is holding
        // it. That is decided by the module list, not by anything the server does later:
        // the profile repository is built without a store, and the tools read their one
        // setting through the narrow port, which answers with the default rather than
        // pulling the application's settings store in with it.
        //
        // The active profile has a file of its own — `profile_state.preferences_pb` — so
        // the settings file this server does open, for the AI configuration, does not
        // say anything about which profile the user is working in.
        modules(
            listOf(platformModule(), coreLoggingModule()) + domainModule(fixedProfileId?.let(ProfileId::fromString)),
        )
    }
    true
} catch (e: Throwable) {
    log.e(e) { "Koin initialization failed: ${e.message}" }
    false
}

/**
 * Resolves the server's dependencies, or says in the log why it could not.
 *
 * The same one-place failure as the rest of the start-up. Resolving the tool list
 * builds every collaborator the server has, and any of them can fail to build — a
 * missing binding, a host that cannot be read, a database that cannot be opened.
 * Left unguarded, such a failure escaped `main`: a stack trace on standard error and
 * an exit, with **nothing on the protocol channel at all**. A client waiting for an
 * answer saw the connection close and no reason, which is the one answer a client
 * cannot act on.
 */
// The same one-place failure as the rest of the start-up: a dependency that cannot be
// built is a start-up failure, and the caller gets a JSON-RPC error and a non-zero exit
// code, not a stack trace.
@Suppress("TooGenericExceptionCaught") // start-up has to fail in one place, whatever the failure was
private fun resolveDependencies(): ServerDependencies? = try {
    ServerDependencies.fromKoin()
} catch (e: Throwable) {
    log.e(e) { "The server's dependencies could not be resolved: ${e.message}" }
    null
}

/** Whether a profile with this id exists. */
private suspend fun profileExists(dependencies: ServerDependencies, profileId: String): Boolean =
    dependencies.database.profileDao().getById(profileId) != null

/**
 * Eagerly opens the Room database and runs a single SELECT to surface schema/migration
 * problems at startup instead of mid-session.
 *
 * On failure, logs the cause and returns false, like every other start-up failure.
 */
// The same one-place failure as the start-up: the database either opens or it does not.
@Suppress("TooGenericExceptionCaught") // the database either opens or it does not
private suspend fun verifyDatabase(dependencies: ServerDependencies): Boolean = try {
    dependencies.database.profileDao().count()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    log.e(e) { "Database initialization failed: ${e.message}" }
    false
}

/**
 * The profile the legacy label activates, or null when the label activates none.
 *
 * The label is a name whose value only one label ever had. `ai-agent` — and its
 * spelling `agent` — activates the "AI Agent" profile; `default` and `personal`
 * leave the stored one alone; every other label is ignored, exactly as it was
 * before this change, because a manifest in the wild passes it and a silently
 * reinterpreted label serves the wrong profile.
 *
 * The mapping is a value rather than a branch inside the bootstrap, so that the
 * legacy meaning can be checked without a database, a container and a process.
 */
internal fun profileNameToActivate(legacyLabel: String?): String? = when (legacyLabel?.lowercase()) {
    "ai-agent", "agent" -> "AI Agent"

    // "default", "personal", and every label nobody knows: the stored profile stays.
    else -> null
}

/**
 * Seeds default profiles (Personal + AI Agent) and switches the active profile
 * to one matching the host's `--profile=NAME` argument, when reasonable.
 *
 * Mapping rule:
 *  - null / "default" / "personal" → leave whatever DataStore had (usually Personal)
 *  - "ai-agent" / "agent" → seed & activate "AI Agent"
 *  - any other name → don't activate (the user opted into a custom profile that
 *    the bootstrapper is unaware of)
 *
 * If activating AI Agent and the DB already has rows owned by the un-scoped
 * local userId (from earlier dogfooding runs before profiles existed), retro-migrate
 * them under the AI Agent's scoped userId so they become visible after switching
 * in the UI. Safe to run repeatedly: only touches rows owned by `user_id = LOCAL_UID`.
 */
// The profile bootstrap is best-effort: a failure here leaves the server on the
// personal/default profile, which is a working server.
@Suppress("TooGenericExceptionCaught") // best-effort: the server works on the default profile
private suspend fun bootstrapProfiles(dependencies: ServerDependencies, profileCliArg: String?) {
    try {
        val bootstrapper: ProfileBootstrapper = dependencies.profileBootstrapper
        val activateName = profileNameToActivate(profileCliArg)

        // The un-scoped local userId, read from the session BEFORE the switch.
        //
        // It must not come from `ProfileAwareCurrentUser.current`: that is a cached
        // StateFlow that a background collector rewrites, and `bootstrapper.run` is
        // suspend, so it gives that collector a chance to run. Once it has, `current`
        // is already the agent-scoped id, and the migration below would build
        // newUserId = "{agent}/{agent}/{user}" — an id no profile matches, leaving
        // every migrated row unreachable. Whether that happened depended on dispatcher
        // timing. The session is not touched by the switch, so this read is exact.
        val localUserId: String = dependencies.currentUser
            .liveLocalUserId
            .first()
            .value

        val result = bootstrapper.run(
            seedExtras = listOf(ProfileBootstrapper.SeedProfile.AI_AGENT),
            activateName = activateName,
        )
        result.activated?.let { activated ->
            // Retro-migrate rows from the unscoped local user id.
            val agentId = activated.value
            retromigrateRowsToAgentScope(
                localUserId = localUserId,
                newUserId = "$agentId/$localUserId",
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log.e(e) { "Profile bootstrap failed: ${e.message}" }
        // Non-fatal: the rest of the server can still operate against the
        // personal/default profile.
    }
}

/**
 * Move rows whose user_id equals the un-scoped local user into the AI Agent
 * scoped user_id (`"{AI Agent id}/{local user id}"`). Idempotent: no-op if
 * no rows match or if the sqlite3 CLI is unavailable.
 *
 * Done via shell `sqlite3` because this is a one-off bootstrap migration
 * touching DB rows that pre-date the profile model.
 *
 * The shell-out goes through [Subprocess] rather than a `ProcessBuilder` of its own:
 * spawning a process is confined to that one file so that every subprocess in the
 * repository is drained and closed the way that does not deliver SIGPIPE to the child.
 */
private fun retromigrateRowsToAgentScope(localUserId: String, newUserId: String) {
    // Always use the default DB path — Desktop, Android, and MCP all share it now.
    val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
    val file = File(dbPath)
    if (!file.exists()) return
    val sql = buildString {
        for (table in listOf("tasks", "notes", "projects", "tags")) {
            append(
                "UPDATE $table SET user_id='",
            ).append(newUserId).append("' WHERE user_id='").append(localUserId).append("';")
        }
    }
    // sqlite3 prints affected row counts to stdout when group statements use
    // "--changes" (we'll skip that for portability). Better: count before/after by
    // another route, but for the bootstrap we just trust the operation succeeded if
    // the exit was 0 — which is exactly what a non-null result means here.
    val result = Subprocess.runCapturing(listOf("sqlite3", dbPath), stdin = sql)
    if (result != null) {
        log.i { "Retro-migrated rows to AI Agent scope ($dbPath)" }
    } else {
        log.w { "Retro-migration did not run: sqlite3 is unavailable or the update failed" }
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
private suspend fun readToolConfiguration(dependencies: ServerDependencies): ToolConfiguration = try {
    val snapshot = dependencies.remoteConfig.snapshot()
    ToolConfiguration(
        flags = snapshot.mcpToolFlags,
        status = ToolsetSnapshot.statusOf(snapshot, Clock.System.now().toEpochMilliseconds()),
        fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis.takeIf { it > 0L },
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    log.w { "Remote configuration unavailable, every tool is enabled: ${e.message}" }
    ToolConfiguration(
        flags = emptyMap(),
        status = ConfigurationStatus.ERROR,
        fetchedAtEpochMillis = null,
    )
}

/** The flags that decide the exposed toolbox, and where they came from. */
data class ToolConfiguration(
    val flags: Map<String, Boolean>,
    val status: ConfigurationStatus,
    val fetchedAtEpochMillis: Long?,
)

/**
 * Builds the server and registers every tool on it.
 *
 * The tool configuration is handed to the registrar here and not read again: the
 * toolbox a client sees is the toolbox the server announced when it started.
 */
private fun buildServer(
    dependencies: ServerDependencies,
    configuration: ToolConfiguration,
    sessionContext: SessionContextHolder,
): Server {
    val server = Server(
        serverInfo = Implementation(
            name = "singularity-todo",
            // The real application version, from the canonical source the desktop app
            // and the Android build both use. A literal here would tell a client it had
            // connected to a version nobody could check against the checkout.
            version = appVersion().name,
            title = null,
            websiteUrl = null,
            icons = emptyList(),
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(listChanged = true),
            ),
        ),
        instructions = "Singularity Todo MCP server. Use tools to read/write tasks, notes, " +
            "projects, tags, and ADR entries. Call describe_toolbox to see which tools this " +
            "server is exposing and why. Surface contract version: " +
            "${McpToolCatalog.SURFACE_CONTRACT_VERSION}.",
    )
    val registrar = ToolRegistrar(
        server = server,
        tools = dependencies.tools,
        toolFlags = configuration.flags,
        configurationStatus = configuration.status,
        configurationFetchedAtEpochMillis = configuration.fetchedAtEpochMillis,
        recorder = dependencies.recorder,
        sessionContext = { sessionContext.current() },
    )
    registrar.registerAll()
    ToolboxTool(registrar.snapshot).register(server)
    ToolboxResources(
        snapshot = registrar.snapshot,
        storage = dependencies.adrStorage,
    ).register(server)
    RegistrySnapshot.write(registrar.snapshot, dependencies.dataDirectory)
    log.i {
        "Toolbox: ${registrar.snapshot.registered.size} tool(s) registered, " +
            "${registrar.snapshot.disabled.size} disabled, " +
            "fingerprint ${registrar.snapshot.identity.activeToolsetFingerprint}"
    }
    if (registrar.snapshot.unmatchedFlags.isNotEmpty()) {
        // A flag that names no tool switched nothing off. The operator who set it
        // believes it did, so the server says so rather than staying quiet.
        log.w {
            "Remote configuration names ${registrar.snapshot.unmatchedFlags.size} tool(s) " +
                "this server does not expose: ${registrar.snapshot.unmatchedFlags.joinToString()}"
        }
    }
    return server
}

/**
 * Who is connected, and in which session.
 *
 * Late-bound, because the session is created *after* the tools are registered, and the
 * tool calls it describes happen after that. A holder rather than a constructor
 * argument, for the same reason the registrar takes a supplier: the value is not known
 * when the thing that needs it is built.
 */
internal class SessionContextHolder {
    @Volatile
    var session: ServerSession? = null

    fun current(): ToolCallContext {
        val session = this.session
        return ToolCallContext(
            sessionId = session?.sessionId,
            client = session?.clientVersion?.let { "${it.name}@${it.version}" },
        )
    }
}

/**
 * Closes the server and the container when the process is on its way out.
 *
 * A shutdown hook has no coroutine context of its own, and the JVM is on its way out:
 * the hook has to block until the session is closed, because after it returns the JVM
 * tears the process down whatever the session was doing.
 */
@Suppress("NoRunBlocking") // a shutdown hook has to block: the JVM is leaving
private fun installShutdownHook(server: Server) {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { server.close() }
            stopKoin()
        },
    )
}
