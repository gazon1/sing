package com.singularity.todo.mcp

import ai.koog.agents.core.tools.Tool
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.observability.ToolCallRecorder
import com.singularity.todo.core.platform.HostEnvironmentPort
import com.singularity.todo.feature.ai.tools.AdrStorage
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileBootstrapper
import org.koin.core.context.GlobalContext

/**
 * The dependencies the server needs, resolved once.
 *
 * ## Why this exists
 *
 * The entry point used to reach into the global Koin container seven times: for the
 * database, for the profiles, for the remote configuration, for the tool list, for the
 * recorder, for the ADR storage. Every one of those lookups was a place where the
 * server could not be exercised without starting a container — and a container is
 * global state, which is exactly what makes a test race with its neighbours.
 *
 * So the lookups happen **here**, once, and everything downstream takes what it needs as
 * an argument. The container is still the source of the bindings — that is the app's
 * architecture — but the server's own code no longer depends on where they came from.
 *
 * The two optional dependencies are optional on purpose: the recorder and the ADR
 * storage are both absent in some deployments, and a server that cannot record calls
 * or answer the ADR resources still serves its tools.
 */
class ServerDependencies(
    /** The tools this server may expose. */
    val tools: List<Tool<*, *>>,
    /** The database, opened eagerly so a broken schema surfaces at startup. */
    val database: AppDatabase,
    /** The remote configuration the kill switch reads. */
    val remoteConfig: RemoteConfigPort,
    /** Seeds and activates the profiles. */
    val profileBootstrapper: ProfileBootstrapper,
    /** The current user, for the profile-scoped ids. */
    val currentUser: ProfileAwareCurrentUser,
    /** Where tool calls are recorded. Absent when the platform has nowhere to write. */
    val recorder: ToolCallRecorder?,
    /** The ADR storage the resources read. Absent when it is not wired. */
    val adrStorage: AdrStorage?,
    /** Where the registry snapshot and the call records are written. */
    val dataDirectory: String,
) {
    companion object {
        /**
         * Reads every dependency from the started container.
         *
         * The one place in the server that knows about the global context.
         */
        fun fromKoin(): ServerDependencies = GlobalContext.get().let { koin ->
            ServerDependencies(
                tools = koin.get(),
                database = koin.get(),
                remoteConfig = koin.get(),
                profileBootstrapper = koin.get(),
                currentUser = koin.get(),
                recorder = koin.getOrNull(),
                adrStorage = koin.getOrNull(),
                dataDirectory = koin.get<HostEnvironmentPort>().applicationDataDirectory(),
            )
        }
    }
}
