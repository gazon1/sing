package com.singularity.todo.core.database

import com.singularity.todo.core.format.formatPadded
import kotlinx.datetime.LocalTime

/**
 * Manual string ↔ [LocalTime] conversion for the canonical ISO-8601 `HH:mm:ss` wire format.
 *
 * kotlinx-datetime 0.8.0 lacks a public [LocalTime.Format] builder and a public `parse` API.
 * The canonical wire format used by Room, backup DTOs, and AI tool inputs is `HH:mm:ss`.
 *
 * Accepts both `HH:mm` and `HH:mm:ss`; missing seconds default to 0.
 */
object LocalTimeFormats {
    /**
     * Parses `HH:mm` or `HH:mm:ss` into [LocalTime].
     *
     * Throws `IllegalArgumentException` when the string is malformed — it does
     * not return `null`, and a caller that needs a nullable result has to
     * catch it.
     */
    fun parse(value: String): LocalTime {
        val parts = value.split(":")
        require(parts.size in 2..3) { "Invalid time format: '$value' (expected HH:mm or HH:mm:ss)" }
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        val second = if (parts.size == 3) parts[2].toInt() else 0
        require(hour in 0..23 && minute in 0..59 && second in 0..59) {
            "Invalid time components in '$value'"
        }
        return LocalTime(hour, minute, second)
    }

    /**
     * Formats as `HH:mm:ss` (zero-padded).
     *
     * Padded by hand, not with `"%02d:%02d:%02d".format(...)`: that formats in the
     * *device's* locale, and on an Arabic phone it writes "١٤:٣٠:٠٠" into the
     * database — which [parse] then rejects, because `toInt()` does not read
     * Arabic-Indic digits. This is the wire format: Room, backup DTOs and AI tool
     * inputs all read it back.
     */
    fun format(time: LocalTime): String =
        "${formatPadded(time.hour)}:${formatPadded(time.minute)}:${formatPadded(time.second)}"
}
