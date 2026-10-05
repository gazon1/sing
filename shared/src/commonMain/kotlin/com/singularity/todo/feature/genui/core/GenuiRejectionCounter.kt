package com.singularity.todo.feature.genui.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private val logger = Logger.withTag("GenuiRejections")

/**
 * How often each contract violation has been hit, since the process started.
 *
 * The floor of the layer had no instruments: every rejection went to a log line and to the
 * correction prompt, and neither can be counted after the fact. So the question "which mistake does
 * the model actually make" — the one that decides whether the answer is a better prompt, a looser
 * catalog or a different component set — had no way to be answered by anything but an impression.
 *
 * Deliberately in memory and deliberately cumulative. It is a development instrument, not a
 * statistic to report on: a persisted table would be a schema migration and a privacy question in
 * exchange for a distribution that only a developer asks for.
 *
 * [snapshot] renders the running distribution as one line, which is what gets logged when a turn
 * produces several rejections — at which point the individual reasons have scrolled past, and the
 * question is which of them is common rather than which happened last.
 */
class GenuiRejectionCounter {

    private val _counts = MutableStateFlow<Map<A2uiErrorCode, Int>>(emptyMap())

    /** Live tally, ordered by frequency by whoever renders it rather than by who recorded it. */
    val counts: StateFlow<Map<A2uiErrorCode, Int>> = _counts.asStateFlow()

    /** Records every rejection of one turn. */
    fun record(errors: List<A2uiError>) {
        if (errors.isEmpty()) return
        _counts.update { current: Map<A2uiErrorCode, Int> ->
            errors.fold(current) { tally: Map<A2uiErrorCode, Int>, error: A2uiError ->
                tally + (error.code to ((tally[error.code] ?: 0) + 1))
            }
        }
    }

    /** The tally as one line, most frequent first. Empty when nothing has been rejected. */
    fun snapshot(): String = _counts.value
        .entries
        .sortedByDescending { it.value }
        .joinToString(", ") { "${it.key.name}=${it.value}" }

    /** Logs [errors] and returns the running distribution, so a caller can log both in one line. */
    fun recordAndReport(errors: List<A2uiError>): String {
        record(errors)
        val running: String = snapshot()
        logger.i { "Rejected ${errors.size}: ${errors.map { it.asFeedback() }}; so far $running" }
        return running
    }

    /** Forgets everything. Only meaningful for a test or a deliberately fresh session. */
    fun reset() {
        _counts.value = emptyMap()
    }
}
