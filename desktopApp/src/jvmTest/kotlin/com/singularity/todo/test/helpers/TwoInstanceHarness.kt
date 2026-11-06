package com.singularity.todo.test.helpers

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.SharedSyncServer
import kotlinx.coroutines.flow.first
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.module
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/**
 * Two app instances in one JVM, each with its own database file, talking to one
 * shared sync server.
 *
 * ## What this is for
 *
 * The desktop sync carriers need two *whole instances*: two Koin graphs, two
 * databases, two clients — one per side of a sync. A test that mounts the app
 * twice through [runDesktopAppTest] cannot do that: the harness builds one graph
 * per test, and two databases in one graph are not two instances.
 *
 * So the two instances are built here, without a UI. What each one *is* is
 * deliberately the production thing — the same module list, the same
 * repositories, the same ports — with exactly the substitutions a carrier needs:
 * a per-instance real database (Room file, not the in-memory fake), a shared
 * fake sync transport ([SyncApiClient] → [SharedSyncServer]), and a deterministic
 * local auth seam ([AuthRepository] → [FakeAuthRepository]) so `signIn`/sync scope
 * never touch Supabase or the network.
 *
 * ## How the substitutions are made
 *
 * All go through the `overrides` seam of [buildDesktopAppKoin], which loads
 * last and therefore wins Koin's last-definition-wins rule. Nothing is rebound
 * behind the harness's back: the DAO bindings in the test platform module read
 * `get<AppDatabase>()`, so rebinding the database reaches every DAO, and the
 * sync/auth bindings are replaced wholesale.
 *
 * The `UnitOfWork` is *not* replaced: the test platform module binds the
 * pass-through fake, which runs the block unchanged. These tests never roll a
 * block back, so a fake that cannot roll back is not a difference they can
 * observe. A carrier that needs rollback semantics must bind the real
 * `RoomUnitOfWork` instead.
 *
 * ## No process-wide state
 *
 * Every instance gets its own [KoinApplication]; `startKoin` is never called and
 * the global Koin context is never touched, so two instances in one JVM cannot
 * race for one graph. Everything written lives under one temp directory, which
 * is removed when the harness is closed.
 *
 * ## Usage
 *
 * ```kotlin
 * TwoInstanceHarness.start().use { harness ->
 *     harness.instanceA.syncClient.batchPush(request)
 *     harness.instanceB.syncClient.getEventsSince(0L)
 * }
 * ```
 */
class TwoInstanceHarness private constructor(
    /** The first instance. */
    val instanceA: InstanceHandle,
    /** The second instance. */
    val instanceB: InstanceHandle,
    private val applications: List<KoinApplication>,
    private val databases: List<AppDatabase>,
    private val tempDir: File,
) : Closeable {
    /**
     * Closes both graphs, both databases, and removes the temp directory.
     *
     * The graphs are closed before the databases, so nothing can resolve a DAO
     * against a closed file.
     */
    override fun close() {
        applications.forEach { it.close() }
        databases.forEach { it.close() }
        tempDir.deleteRecursively()
    }

    /**
     * One of the two instances.
     *
     * @property name which instance this is — `A` or `B`.
     * @property koin this instance's own Koin graph.
     * @property databasePath the absolute path of this instance's database file.
     * @property database the database this graph resolves, exposed so a test
     *   can prove the graph uses the file at [databasePath] and not a fake.
     * @property syncClient the client this instance syncs through.
     * @property account the account this instance is authenticated as.
     */
    class InstanceHandle internal constructor(
        val name: String,
        val koin: Koin,
        val databasePath: String,
        val database: AppDatabase,
        val syncClient: SyncApiClient,
        val account: String,
    )

    companion object {
        /** The account both instances act as, unless the caller names another. */
        const val DEFAULT_ACCOUNT: String = "two-instance-account"

        /**
         * Starts two instances against one shared server.
         *
         * Returns only when both graphs' profile-scoped identity has settled:
         * `ProfileAwareCurrentUser.scopedUserId` is seeded with `anonymous` and
         * corrected by a background collector, so a caller that reads it right
         * after construction can get a different user on each instance. The
         * repositories read that cached value, so a test that does not wait
         * here proves "the row is invisible" with two different owners instead
         * of two databases.
         *
         * @param account the account both instances are authenticated as — the
         *   same one, because a sync is a conversation between two devices of
         *   one account.
         */
        suspend fun start(account: String = DEFAULT_ACCOUNT): TwoInstanceHarness {
            val tempDir = Files.createTempDirectory("singularity-two-instance-").toFile()
            val server = SharedSyncServer()
            val built = mutableListOf<Instance>()
            var completed = false
            return try {
                built += buildInstance("A", tempDir, server, account)
                built += buildInstance("B", tempDir, server, account)
                val harness =
                    TwoInstanceHarness(
                        instanceA = built[0].handle,
                        instanceB = built[1].handle,
                        applications = built.map { it.application },
                        databases = built.map { it.database },
                        tempDir = tempDir,
                    )
                harness.awaitIdentitySettled()
                completed = true
                harness
            } finally {
                if (!completed) {
                    built.forEach { it.close() }
                    tempDir.deleteRecursively()
                }
            }
        }

        /** Waits until each graph's cached scoped identity equals the live one. */
        private suspend fun TwoInstanceHarness.awaitIdentitySettled() {
            listOf(instanceA, instanceB).forEach { instance ->
                val user = instance.koin.get<ProfileAwareCurrentUser>()
                val live = user.liveScopedUserId.first()
                user.scopedUserId.first { it == live }
            }
        }

        private fun buildInstance(
            name: String,
            tempDir: File,
            server: SharedSyncServer,
            account: String,
        ): Instance {
            val databaseFile = File(tempDir, "instance-$name.db")
            val database = AppDatabaseFactory.build(createSqlDriver(), databaseFile.absolutePath)
            return try {
                val client = server.client(account)
                val overrides =
                    module {
                        single<AppDatabase> { database }
                        single<SyncApiClient> { client }
                        // Deterministic local auth: carriers call signIn/syncOnce and must
                        // never reach Supabase or the network. testPlatformModule() also
                        // binds FakeAuthRepository(), but binding it here makes the harness
                        // contract explicit regardless of module ordering.
                        single<AuthRepository> { FakeAuthRepository() }
                    }
                val application = buildDesktopAppKoin(overrides = overrides)
                Instance(
                    handle =
                        InstanceHandle(
                            name = name,
                            koin = application.koin,
                            databasePath = databaseFile.absolutePath,
                            database = database,
                            syncClient = client,
                            account = account,
                        ),
                    application = application,
                    database = database,
                )
            } catch (failure: Throwable) {
                database.close()
                throw failure
            }
        }

        /** One built instance: its handle, its graph and its database. */
        private class Instance(
            val handle: InstanceHandle,
            val application: KoinApplication,
            val database: AppDatabase,
        ) {
            fun close() {
                application.close()
                database.close()
            }
        }
    }
}
