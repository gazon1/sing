package com.singularity.todo.test.fakes

import com.singularity.todo.core.config.RemoteConfigCacheDao
import com.singularity.todo.core.config.RemoteConfigCacheEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A [RemoteConfigCacheDao] whose read can be told to block.
 *
 * The interesting behaviour is [ReadBehaviour.Blocks]: it models the read the version
 * gate has to survive — a Room call that blocks in the driver. It does not merely
 * suspend for a long time; it ignores cancellation altogether for [blockMs], which is
 * what a blocking driver call does. A deadline around such a read in the *caller's*
 * coroutine cannot end it, so a test that used a merely-slow read would pass against an
 * implementation that only looks bounded.
 *
 * The block is bounded in real time rather than infinite, so a test that runs against an
 * implementation *without* the bound fails on an assertion instead of hanging the suite.
 *
 * @param cached What [RemoteConfigCacheDao.getDefault] answers when it answers at all.
 * @param readBehaviour How the read behaves.
 * @param blockMs How long a blocking read ignores cancellation before it answers. It has to
 *   outlast every deadline it is used against, or the read answers before the deadline and
 *   the bound is never exercised.
 */
class FakeRemoteConfigCacheDao(
    private val cached: RemoteConfigCacheEntity? = null,
    private val readBehaviour: ReadBehaviour = ReadBehaviour.Answer,
    private val blockMs: Long = 60_000L,
) : RemoteConfigCacheDao {

    /** What the cached read does. */
    enum class ReadBehaviour {
        /** Answers [cached] (or null). */
        Answer,

        /** Ignores cancellation for [blockMs] and then answers [cached]. */
        Blocks,

        /** Fails. */
        Throws,
    }

    /** The rows this DAO was asked to store, in order. */
    val upserts: MutableList<RemoteConfigCacheEntity> = mutableListOf()

    /** How many times the cache was read. */
    var readCount: Int = 0
        private set

    override fun watchDefault(): Flow<RemoteConfigCacheEntity?> = emptyFlow()

    override suspend fun getDefault(): RemoteConfigCacheEntity? {
        readCount++
        return when (readBehaviour) {
            ReadBehaviour.Answer -> cached

            ReadBehaviour.Blocks -> withContext(NonCancellable) {
                withTimeoutOrNull(blockMs) { awaitCancellation() }
                cached
            }

            ReadBehaviour.Throws -> throw IllegalStateException("cache unavailable")
        }
    }

    override suspend fun upsert(entity: RemoteConfigCacheEntity) {
        upserts += entity
    }

    override suspend fun deleteDefault() {
        upserts.clear()
    }
}
