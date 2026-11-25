package com.singularity.todo.feature.ai.chat.persistence

import co.touchlab.kermit.Logger
import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlin.time.Clock

/**
 * Writes one chat conversation to the transcript, turn by turn.
 *
 * ## Why this is not the ViewModel
 *
 * The ViewModel decides what the conversation *is* — the intent, the turn, the events, the
 * outcome. Where a row goes, what its position is, and how often streamed text is written
 * is a different question with its own state, and it was the part of the ViewModel that
 * grew a conversation id, a position counter and a persist clock. This class owns exactly
 * that state and nothing about the agent.
 *
 * ## What it guarantees
 *
 * - **One conversation.** The first turn resolves the newest conversation (or creates one)
 *   and every later turn writes into it, so an update can never land in somebody else's
 *   conversation.
 * - **Deterministic order.** Positions come from one counter that starts after the last
 *   restored row, so a restored transcript and a fresh one order the same way.
 * - **Batched text.** [onDelta] writes at most once per [STREAM_INTERVAL_MS]; a token is not
 *   a write. The terminal state is always written.
 * - **No replay.** Nothing here starts a turn. A restored transcript is a record, not a
 *   resumption.
 *
 * Every write returns nothing and reports failure through the log: a transcript that cannot
 * be written must not take the conversation down with it, and the caller has already shown
 * the answer on screen. The one exception is [beginTurn], whose failure the caller has to
 * see — a turn whose question could not be recorded is a turn nobody can restore, and the
 * ViewModel refuses to start it rather than losing the question silently.
 */
internal class ChatTranscriptWriter(
    private val repository: ChatConversationRepository,
    private val unitOfWork: UnitOfWork,
    private val clock: Clock,
    private val log: Logger,
) {
    /** The conversation every turn of this writer belongs to, once resolved. */
    private var conversationId: ChatConversationId? = null

    /** The position the next row of [conversationId] takes. */
    private var nextPosition: Int = 0

    /** The answer currently being written, when a turn is running. */
    private var answerId: ChatMessageId? = null

    /** The text last written for [answerId], so an unchanged answer is not rewritten. */
    private var lastWritten: String = ""

    /** When [lastWritten] was written, for the batching interval. */
    private var lastWrittenAt: Long = 0L

    /**
     * The newest conversation, reconciled and in order, or an empty list when there is none.
     *
     * Reconciling is part of reading: a row still marked `InProgress` cannot be running,
     * because the process that wrote it is gone. It becomes `Interrupted`, its partial text
     * kept — the alternative is a transcript that claims a request is still in flight.
     */
    suspend fun restore(): List<ChatMessageRecord> =
        runCatchingCancellable {
            val conversation =
                repository.observeLatestConversation().firstOrNull() ?: return@runCatchingCancellable emptyList()
            val now = clock.now().toEpochMilliseconds()
            // The reconciliation is part of reading, so its failure is part of the read: a
            // transcript that still says `InProgress` would be shown as an answer that is
            // running, and the row would be restored with `interrupted = false`. Failing the
            // whole restore is the honest outcome — no transcript is better than one that
            // claims a request survived the process that was running it.
            repository.markInProgressInterrupted(conversation.id, now).getOrThrow()
            val records = repository.getMessages(conversation.id)
            conversationId = conversation.id
            nextPosition = (records.maxOfOrNull { it.position } ?: -1) + 1
            records
        }.getOrElse { error ->
            log.e(error) { "Chat transcript restoration failed" }
            emptyList()
        }

    /**
     * Records the question and the answer's placeholder before generation starts.
     *
     * @param question what the reader asked, or null for a turn started by a press inside a
     *   screen: a press is not something a person typed, and the transcript keeps what people
     *   said.
     * @return false when the question could not be recorded, so the caller can refuse the turn.
     */
    suspend fun beginTurn(
        question: Question?,
        assistantId: ChatMessageId,
    ): Boolean =
        runCatchingCancellable {
            val id = conversation() ?: return@runCatchingCancellable false
            answerId = assistantId
            lastWritten = ""
            lastWrittenAt = 0L
            val now = clock.now().toEpochMilliseconds()
            // The counter moves only if the transaction commits. A position consumed by a
            // rolled-back write is a gap no retry fills, and a retry that renumbers from a
            // counter already past the row it failed to write would leave the two orders
            // disagreeing.
            val first = nextPosition
            // One transaction, because the question and its answer are one turn: a row written
            // without its twin is either a question nobody answered or an answer to nothing,
            // and the restored transcript would show a turn that never happened.
            unitOfWork.write {
                if (question != null) {
                    repository
                        .insertMessage(
                            ChatMessageRecord(
                                id = question.id,
                                conversationId = id,
                                userId = "",
                                role = PersistedChatRole.User,
                                content = question.text,
                                status = ChatMessageStatus.Completed,
                                position = first,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        ).getOrThrow()
                }
                repository
                    .insertMessage(
                        ChatMessageRecord(
                            id = assistantId,
                            conversationId = id,
                            userId = "",
                            role = PersistedChatRole.Assistant,
                            content = "",
                            status = ChatMessageStatus.InProgress,
                            position = if (question != null) first + 1 else first,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ).getOrThrow()
                repository.touchConversation(id, now).getOrThrow()
            }
            nextPosition = first + if (question != null) 2 else 1
            true
        }.getOrElse { error ->
            log.e(error) { "Chat transcript could not record the start of a turn" }
            false
        }

    /**
     * Writes the text streamed so far, at most once per [STREAM_INTERVAL_MS].
     *
     * Called per delta, which is why the interval exists: a write per token would make the
     * database the busiest participant in a conversation.
     */
    suspend fun onDelta(content: String) {
        val now = clock.now().toEpochMilliseconds()
        if (now - lastWrittenAt < STREAM_INTERVAL_MS) return
        write(content, ChatMessageStatus.InProgress, now)
    }

    /**
     * Writes the answer's final text and the state the turn ended in.
     *
     * Always writes, even when the text is unchanged: the status is the part that has to
     * land, and a turn that finished with no new text still finished.
     */
    suspend fun finish(
        content: String,
        status: ChatMessageStatus,
    ) {
        val now = clock.now().toEpochMilliseconds()
        write(content, status, now)
    }

    private suspend fun write(
        content: String,
        status: ChatMessageStatus,
        now: Long,
    ) {
        val id = answerId ?: return
        val conversation = conversationId ?: return
        if (content == lastWritten && status == ChatMessageStatus.InProgress) return
        runCatchingCancellable {
            repository.updateMessageContent(id, conversation, content, status, now).getOrThrow()
            repository.touchConversation(conversation, now).getOrThrow()
            lastWritten = content
            lastWrittenAt = now
        }.onFailure { error ->
            log.w(error) { "Chat transcript could not record the answer" }
        }
    }

    /** The conversation this writer writes into, creating one only when none exists. */
    private suspend fun conversation(): ChatConversationId? {
        conversationId?.let { return it }
        val existing = repository.observeLatestConversation().firstOrNull()
        if (existing != null) {
            val now = clock.now().toEpochMilliseconds()
            repository.markInProgressInterrupted(existing.id, now).getOrThrow()
            conversationId = existing.id
            nextPosition = (repository.getMessages(existing.id).maxOfOrNull { it.position } ?: -1) + 1
            return existing.id
        }
        val now = clock.now().toEpochMilliseconds()
        val created =
            ChatConversation(
                id = ChatConversationId.generate(),
                userId = "",
                createdAt = now,
                updatedAt = now,
            )
        repository.createConversation(created).getOrThrow()
        conversationId = created.id
        nextPosition = 0
        return created.id
    }

    /** A question the reader asked, with the identity its row will carry. */
    data class Question(val id: ChatMessageId, val text: String)

    private companion object {
        /** How often streamed text reaches the database. A cadence, not a write per token. */
        const val STREAM_INTERVAL_MS = 250L
    }
}
