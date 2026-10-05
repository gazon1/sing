package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.error.FailureType

/**
 * Lossless string codec for [CalendarSyncStatus], for the settings DataStore.
 *
 * ## Why this is a hand-written codec and not [com.singularity.todo.core.settings.EnumPref]
 *
 * `EnumPref` recovers a value by name out of `EnumEntries`, which is exactly right for a
 * plain enum. `CalendarSyncStatus` is a sealed interface whose two interesting cases carry
 * data — `Idle(lastSyncedAt)` and `Failed(reason, type)` — so there are no `entries` to
 * recover. The codec has to carry a tag *and* a payload, and the payload contains a
 * delimiter (`:`) and a type name that must not be confused with a reason.
 *
 * ## Why the previous codec could not be repaired in place
 *
 * It read with a `when` whose `else` branch assumed anything unrecognised was a `Failed`
 * reason: `statusName?.removePrefix("Failed:")`. That made `"Idle"` — which *this same class*
 * writes at the `Idle` branch — decode to `Failed("Idle")`, so `Idle` could never be
 * reconstructed. `FailureType` was not stored at all, so it always came back as `Unknown`
 * and the settings screen could never offer a recovery action it knew about.
 *
 * ## Format
 *
 * `<tag>[:<field>[:<field>]]`, fields separated by a `:` that is escaped in free text
 * (see [escape]). The tag is always the sealed type's simple name, so an unknown tag is
 * detectable rather than silently reinterpreted.
 *
 * - `Disabled`
 * - `Syncing`
 * - `Idle:<lastSyncedAt>` — absent field means "never synced"
 * - `Failed:<escaped reason>:<FailureType name>`
 *
 * Decoding is total: any string produces a [CalendarSyncStatus], so a value written by an
 * older build, or corrupted on disk, can never throw on read.
 */
object CalendarSyncStatusCodec {

    private const val TAG_DISABLED = "Disabled"
    private const val TAG_SYNCING = "Syncing"
    private const val TAG_IDLE = "Idle"
    private const val TAG_FAILED = "Failed"
    private const val SEPARATOR = ':'
    private const val ESCAPE = '\\'

    /**
     * Encodes [status] to its stored form.
     *
     * Round-trips through [decode] for every value of [CalendarSyncStatus], including
     * `Failed` with a reason that itself contains colons, backslashes, or the literal
     * words "Idle"/"Syncing" — which is what the previous `when`-based reader got wrong.
     */
    fun encode(status: CalendarSyncStatus): String = when (status) {
        CalendarSyncStatus.Disabled -> TAG_DISABLED

        CalendarSyncStatus.Syncing -> TAG_SYNCING

        is CalendarSyncStatus.Idle -> buildString {
            append(TAG_IDLE)
            status.lastSyncedAt?.let {
                append(SEPARATOR)
                append(it)
            }
        }

        is CalendarSyncStatus.Failed -> buildString {
            append(TAG_FAILED)
            append(SEPARATOR)
            append(escape(status.reason))
            append(SEPARATOR)
            append(status.type.name)
        }
    }

    /**
     * Decodes a stored value back to a [CalendarSyncStatus].
     *
     * Total by construction — an unknown tag, a truncated value, or a `FailureType` that no
     * longer exists all degrade to a value the UI can still render, never an exception.
     * [fallback] (supplied by the caller, because it may need to read other preferences)
     * is returned only when [raw] is null or blank, i.e. nothing was ever written.
     */
    fun decode(raw: String?, fallback: CalendarSyncStatus): CalendarSyncStatus {
        if (raw.isNullOrBlank()) return fallback
        val parts = split(raw)
        return when (parts.firstOrNull()) {
            TAG_DISABLED -> CalendarSyncStatus.Disabled

            TAG_SYNCING -> CalendarSyncStatus.Syncing

            TAG_IDLE -> CalendarSyncStatus.Idle(parts.getOrNull(1)?.toLongOrNull())

            TAG_FAILED -> {
                val reason = parts.getOrNull(1)?.let(::unescape).orEmpty()
                val type = parts.getOrNull(2)?.let { name ->
                    FailureType.entries.firstOrNull { it.name == name }
                } ?: FailureType.Unknown
                CalendarSyncStatus.Failed(reason, type)
            }

            // An unrecognised tag means a newer build wrote this. Reporting it as a
            // failure keeps the previous behaviour of surfacing the problem, without
            // pretending the stored text was a reason.
            else -> CalendarSyncStatus.Failed(raw, FailureType.Unknown)
        }
    }

    /** Escapes the separator and the escape character so free text survives a round trip. */
    private fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (ch == SEPARATOR || ch == ESCAPE) append(ESCAPE)
            append(ch)
        }
    }

    /** Reverses [escape]. A trailing lone backslash is kept, because dropping data is worse. */
    private fun unescape(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == ESCAPE && i + 1 < text.length) {
                append(text[i + 1])
                i += 2
            } else {
                append(ch)
                i++
            }
        }
    }

    /** Splits on unescaped separators, honouring [ESCAPE] runs. */
    private fun split(raw: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                ch == ESCAPE && i + 1 < raw.length -> {
                    current.append(ch).append(raw[i + 1])
                    i += 2
                }

                ch == SEPARATOR -> {
                    out.add(current.toString())
                    current.clear()
                    i++
                }

                else -> {
                    current.append(ch)
                    i++
                }
            }
        }
        out.add(current.toString())
        return out
    }
}
