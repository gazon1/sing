package com.singularity.todo.feature.ai.tools

/**
 * Accept-color-as-string helper.
 *
 * The MCP wire format only carries JSON values: numbers (Int / Long), strings,
 * nulls, booleans. Many MCP clients (ZCode, Claude Code, curl, arbitrary LLMs)
 * pass hex colors as strings like `"#4CAF50"` or `"#FF4CAF50"`. Others pass
 * raw ARGB integers like `4280391411` (= `0xFF4CAF50`, which Kotlin treats as
 * a Long because the literal overflows signed Int).
 *
 * The Input of [CreateTagInput] / [CreateProjectInput] historically was a
 * pure `Int`. With this helper the Input can stay a **string** (hex) or
 * **int** (ARGB). Inside the tool body we call [parseColor] once and
 * consume an `Int` regardless of which the caller chose.
 *
 * Recognised forms:
 * - `"#RRGGBB"`     → 0xFFRRGGBB
 * - `"#AARRGGBB"`   → 0xAARRGGBB
 * - `"RRGGBB"`      → 0xFFRRGGBB
 * - `"AARRGGBB"`    → 0xAARRGGBB
 * - `"0xAARRGGBB"`  → passthrough
 * - anything else  → [defaultColor]
 *
 * @param raw value as provided by the JSON-RPC caller; null is treated as missing.
 * @param defaultColor returned when [raw] is null or unparseable.
 */
fun parseColor(raw: String?, defaultColor: Int): Int {
    if (raw.isNullOrBlank()) return defaultColor
    val rawTrim = raw.trim()
    val hexOrEmpty = if (rawTrim.startsWith("0x") || rawTrim.startsWith("0X")) rawTrim.substring(2) else rawTrim
    return if (hexOrEmpty.startsWith("#")) {
        val hex = hexOrEmpty.substring(1)
        when (hex.length) {
            6 -> ("FF$hex").toLong(16).toInt()
            8 -> hex.toLong(16).toInt()
            else -> defaultColor
        }
    } else {
        // Try decimal ARGB int first; fall back to hex digits (no '#' but still parseable)
        val asDec: Long? = rawTrim.toLongOrNull()
        asDec?.// narrow Long → Int; on 64-bit JVMs any 32-bit value fits, so cast is safe.
        toInt()
            ?: when (hexOrEmpty.length) {
                6 -> {
                    ("FF$hexOrEmpty").toLong(16).toInt()
                }
                8 -> {
                    hexOrEmpty.toLong(16).toInt()
                }
                else -> {
                    defaultColor
                }
            }
    }
}

/** Convenience overload: parse to ARGB Int from any of [String], [Int], [Long]. */
fun parseColor(raw: Any?, defaultColor: Int): Int = when (raw) {
    null -> defaultColor
    is Int -> raw
    is Long -> raw.toInt()
    is String -> parseColor(raw, defaultColor)
    else -> defaultColor
}
