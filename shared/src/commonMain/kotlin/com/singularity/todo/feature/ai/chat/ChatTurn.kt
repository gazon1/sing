package com.singularity.todo.feature.ai.chat

import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.feature.genui.engine.AnswerText
import com.singularity.todo.feature.genui.engine.SurfaceTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One turn of the built-in chat.
 *
 * ## What a turn is
 *
 * A question, one model call, and everything that came back: the text as it arrives, the tool
 * calls and their outcomes, and the screen, if the answer drew one. The turn owns the call, so
 * there is exactly one place where the model is asked — and therefore exactly one place where a
 * second call could grow.
 *
 * ## The screen is part of the answer
 *
 * The model is told, in that one call, that it may draw a screen; the answer's text carries
 * the screen as JSON Lines; and the screen's own rules decide what was drawn and what was
 * rejected. A screen that was rejected is asked for again, with the question named again and
 * the rejections named one by one.
 *
 * ## What the reader sees
 *
 * The prose, and only the prose: the screen is drawn, not printed. The filter that keeps the
 * two apart is the format's own rule, shared with the screen's parser, so the two cannot
 * disagree about what a screen is.
 *
 * ## What the turn does not decide
 *
 * How the turn ended. The turn says whether the *answer* completed — and it says so on the
 * strength of its last attempt, because an answer that was corrected and then cut off is not
 * a complete answer. Whether that is a failure or a cancellation is the caller's: a stream
 * that ends without the completion event did not finish, and only the caller can say which
 * of the two it was.
 */
internal class ChatTurn(
    /** The model, with the tools it may call. */
    private val agent: TextGenPort,
    /** The screen this answer may draw, or null when the answer draws nothing. */
    private val surface: SurfaceTurn?,
    /** The conversation this turn belongs to. */
    private val history: List<ChatTurnRecord>,
) {

    /**
     * Runs the turn and emits its activity.
     *
     * The events of every attempt are one stream: a correction is part of the turn, not a
     * second turn, and a reader who saw the first attempt's text should not see the screen
     * arrive as if it were a different answer.
     */
    fun run(prompt: String): Flow<AgentEvent> = flow {
        // The filter exists only where there is something to hide: with no screen, a line that
        // opens with a brace is the answer, not a message.
        val answer: AnswerText? = if (surface != null) AnswerText() else null
        var request: String = historyBlock() + prompt
        var attempt: Int = 0
        // Whether the attempt that is running finished. The agent completes a *call*, and a
        // turn may be several calls: only the last attempt's completion says the turn is
        // complete. An earlier attempt that completed and was corrected says nothing about
        // the answer the reader ends up with.
        var completed: Boolean = false

        while (true) {
            surface?.beginAttempt()
            completed = false
            agent.streamChat(request, instructions = surface?.instructions.orEmpty()).collect { event: AgentEvent ->
                when (event) {
                    is AgentEvent.TextDelta -> {
                        surface?.accept(event.text)
                        val shown: String = answer?.accept(event.text) ?: event.text
                        if (shown.isNotEmpty()) emit(AgentEvent.TextDelta(shown))
                    }

                    is AgentEvent.TurnCompleted -> completed = true

                    else -> emit(event)
                }
            }
            surface?.finish()

            val rejections = surface?.rejections.orEmpty()
            if (rejections.isEmpty() || attempt >= MAX_CORRECTIONS) break
            attempt++
            request = historyBlock() + prompt + surface!!.correctionRequest()
        }

        // The turn completes once, and it completes on the strength of the last attempt: an
        // answer that was corrected and then cut off is not a complete answer.
        if (completed) emit(AgentEvent.TurnCompleted)

        val tail: String = answer?.finish().orEmpty()
        if (tail.isNotEmpty()) emit(AgentEvent.TextDelta(tail))
        surface?.report()
    }

    /**
     * The conversation so far, in a form a model can use.
     *
     * The user's words, and the answer's words. An answer that drew only a screen has no words,
     * and it is named as "(showed a screen)": replaying whole screens would put a JSON document
     * into the conversation that the model might copy from, which is exactly how a surface
     * starts repeating the last one instead of being built for the new question.
     */
    private fun historyBlock(): String {
        if (history.isEmpty()) return ""
        val lines: List<String> = history.takeLast(MAX_HISTORY).map { record: ChatTurnRecord ->
            val answer: String = when {
                record.answer.isBlank() -> "(showed a screen)"
                else -> record.answer.take(REPLY_EXCERPT)
            }
            "user: ${record.user.take(REPLY_EXCERPT)}\nassistant: $answer"
        }
        return buildString {
            append("Conversation so far:\n")
            lines.forEach { line: String ->
                append("- ")
                append(line)
                append('\n')
            }
            append("\nNow answer this:\n")
        }
    }

    private companion object {
        /**
         * How many times a rejected screen may be asked for again.
         *
         * A compromise that the screen's owner already made: one is often not enough for a model
         * that has misunderstood the format, and three starts costing more than the screen is
         * worth.
         */
        const val MAX_CORRECTIONS: Int = 2

        /** Turns kept for context. Enough for "and now only overdue", not enough to grow forever. */
        const val MAX_HISTORY: Int = 6

        private const val REPLY_EXCERPT: Int = 200
    }
}

/**
 * One turn of the conversation, as the next turn needs to hear it.
 *
 * @param user what the reader asked.
 * @param answer what the agent answered, in words. An answer that drew only a screen has no
 *   words: it is told about as "showed a screen" rather than as the screen itself, so the
 *   model is not handed a JSON document it might copy from.
 */
internal data class ChatTurnRecord(val user: String, val answer: String)
