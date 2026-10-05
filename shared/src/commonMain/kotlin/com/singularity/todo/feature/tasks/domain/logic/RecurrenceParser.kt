package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import com.singularity.todo.feature.tasks.domain.model.withTermination
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate

/**
 * Parses Orgzly / Tasks.org compatible recurrence DSL strings into [RecurrenceSpec].
 *
 * Grammar (in order):
 *   catchUp       → '!' '+'+ digit [dwm]
 *   yearlyDate    → 'every' monthName digit
 *   everyWeekday  → 'every' weekday (',' weekday)*
 *   everyInterval → 'every' [number] (day|week|month|year)['s']
 *   monthlyOrdinal → digit+ ('st'|'nd'|'rd'|'th') 'of' 'month'
 *   shortForm     → ['.'] '+'+ digit [dwm]
 *
 * ## Supported syntax
 *
 * | Input | Output |
 * |-------|--------|
 * | `+1d` | Interval(FROM_COMPLETION, 1, DAY) |
 * | `+1w` | Interval(FROM_COMPLETION, 1, WEEK) |
 * | `+1m` | Interval(FROM_COMPLETION, 1, MONTH) |
 * | `+1y` | Interval(FROM_COMPLETION, 1, YEAR) |
 * | `++1w` | Interval(FROM_DUE, 1, WEEK) |
 * | `!+1w` | Interval(CATCH_UP, 1, WEEK) |
 * | `.+1m` | Interval(FROM_COMPLETION, 1, MONTH) |
 * | `.++1m` | Interval(FROM_DUE, 1, MONTH) |
 * | `every Mon,Wed,Fri` | Weekly(FROM_DUE, [1,3,5]) |
 * | `every week` | Interval(FROM_DUE, 1, WEEK) |
 * | `every 2 weeks` | Interval(FROM_DUE, 2, WEEK) |
 * | `every 3 days` | Interval(FROM_DUE, 3, DAY) |
 * | `1st of month` | Monthly(FROM_DUE, 1) |
 * | `15th of month` | Monthly(FROM_DUE, 15) |
 * | `every Jan 1` | Yearly(FROM_DUE, 1, 1) |
 * | `every December 25` | Yearly(FROM_DUE, 12, 25) |
 *
 * Any rule may carry an `until` clause, which bounds the series:
 *
 * | Input | Output |
 * |-------|--------|
 * | `+1w until 2026-12-31` | Interval(…, termination = 2026-12-31) |
 * | `every monday until=20261231` | Weekly(…, termination = 2026-12-31) |
 *
 * Both the spelled-out (`until 2026-12-31`) and the RRULE-concatenated
 * (`until=20261231`) forms are accepted, since the two dialects in the wild
 * differ and this parser aims to read both. The clause is stripped **before**
 * the rule grammar runs, so it composes with every rule above without each one
 * having to know about it.
 *
 * @throws IllegalArgumentException if the string does not match a known pattern.
 */
object RecurrenceParser {

    /** Matches the `until` clause, either form, and captures the date text. */
    private val UNTIL_CLAUSE = Regex("\\s+until\\s*[=:]?\\s*(\\S+)", RegexOption.IGNORE_CASE)

    fun parse(input: String): RecurrenceSpec {
        val s = input.trim()
        require(s.isNotBlank()) { "Recurrence rule must not be blank" }

        val untilMatch = UNTIL_CLAUSE.find(s)
        val ruleText = if (untilMatch == null) s else s.removeRange(untilMatch.range).trim()
        val termination = untilMatch?.let { m ->
            val raw = m.groupValues[1]
            RecurrenceTermination(
                endDate = parseUntilDate(raw)
                    ?: throw IllegalArgumentException("Unrecognised end date in recurrence rule: '$raw'"),
            )
        }

        if (ruleText.isBlank()) {
            throw IllegalArgumentException("Recurrence rule has an end date but no rule: '$s'")
        }

        val tokens = tokenize(ruleText.lowercase())
        if (tokens.isEmpty()) throw IllegalArgumentException("Unrecognised recurrence rule: '$s'")

        val spec = try {
            parseRule(tokens, 0).first
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException("Unrecognised recurrence rule: '$s'", e)
        }

        // An unbounded rule keeps `termination = null` rather than an empty
        // wrapper, so `+1w` produces exactly the JSON it always did.
        return if (termination == null) spec else spec.withTermination(termination)
    }

    /**
     * Parses the end date of an `until` clause.
     *
     * Accepts ISO extended (`2026-12-31`) and RRULE basic (`20261231`). Anything
     * else is rejected rather than guessed at — a silently wrong end date stops a
     * series early or late, and neither is visible until a task disappears.
     */
    private fun parseUntilDate(raw: String): LocalDate? {
        val compact = raw.filter { it != '-' }
        // A basic-form date is exactly 8 digits; an extended one is 8 digits + 2
        // separators. Anything else cannot be a date we are willing to guess.
        if (compact.length != 8 || !compact.all { it.isDigit() }) return null
        if (raw.contains('-') && raw.length != 10) return null
        val year = compact.take(4).toInt()
        val month = compact.substring(4, 6).toInt()
        val day = compact.substring(6, 8).toInt()
        return try {
            LocalDate(year, month, day)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // ─── Tokenizer ─────────────────────────────────────────────────────────────

    private sealed class Token {
        data class Bang(val pos: Int) : Token()
        data class Dot(val pos: Int) : Token()
        data class Comma(val pos: Int) : Token()
        data class Word(val value: String, val pos: Int) : Token()
        data class Number(val value: Int, val pos: Int) : Token()
    }

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '!' -> {
                    tokens.add(Token.Bang(i))
                    i++
                }

                c == '.' -> {
                    tokens.add(Token.Dot(i))
                    i++
                }

                c == ',' -> {
                    tokens.add(Token.Comma(i))
                    i++
                }

                c == '+' -> {
                    tokens.add(Token.Word("+", i))
                    i++
                }

                c in '0'..'9' -> {
                    var j = i
                    while (j < input.length && input[j] in '0'..'9') j++
                    tokens.add(Token.Number(input.substring(i, j).toInt(), i))
                    i = j
                }

                c in 'a'..'z' -> {
                    var j = i
                    while (j < input.length && input[j] in 'a'..'z') j++
                    tokens.add(Token.Word(input.substring(i, j), i))
                    i = j
                }

                c == ' ' -> i++

                // skip spaces
                else -> throw IllegalArgumentException("Unexpected character: '$c' at position $i")
            }
        }
        return tokens
    }

    // ─── Lookup tables ─────────────────────────────────────────────────────────

    private fun weekdayValue(word: String): Int? = when (word) {
        "monday", "mon" -> 1
        "tuesday", "tue", "tues" -> 2
        "wednesday", "wed" -> 3
        "thursday", "thu", "thur", "thurs" -> 4
        "friday", "fri" -> 5
        "saturday", "sat" -> 6
        "sunday", "sun" -> 7
        else -> null
    }

    private fun monthValue(word: String): Int? = when (word) {
        "january", "jan" -> 1
        "february", "feb" -> 2
        "march", "mar" -> 3
        "april", "apr" -> 4
        "may" -> 5
        "june", "jun" -> 6
        "july", "jul" -> 7
        "august", "aug" -> 8
        "september", "sep", "sept" -> 9
        "october", "oct" -> 10
        "november", "nov" -> 11
        "december", "dec" -> 12
        else -> null
    }

    private fun timeUnit(word: String): DateTimeUnit.DateBased? = when (word) {
        "d", "day", "days" -> DateTimeUnit.DAY
        "w", "week", "weeks" -> DateTimeUnit.WEEK
        "m", "month", "months" -> DateTimeUnit.MONTH
        "y", "year", "years" -> DateTimeUnit.YEAR
        else -> null
    }

    private fun ordinalSuffix(word: String): Boolean = word == "st" || word == "nd" || word == "rd" || word == "th"

    // ─── Grammar rule parsers ─────────────────────────────────────────────────

    // catchUp: '!' '+'+ digit [dwm]  (! always means CATCH_UP, count is after the +)
    private fun parseCatchUp(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Bang) return null
        var p = pos + 1
        if (p >= tokens.size || tokens[p] !is Token.Word || (tokens[p] as Token.Word).value != "+") return null
        var plusCount = 0
        while (p < tokens.size && tokens[p] is Token.Word && (tokens[p] as Token.Word).value == "+") {
            plusCount++
            p++
        }
        if (plusCount == 0) return null
        if (p >= tokens.size || tokens[p] !is Token.Number) return null
        val num = (tokens[p] as Token.Number).value
        p++
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val unit = (tokens[p] as Token.Word).value
        p++
        val dateUnit = timeUnit(unit) ?: return null
        return Pair(Interval(RecurrenceBase.CATCH_UP, num, dateUnit), p)
    }

    // yearlyDate: 'every' monthName digit
    private fun parseYearlyDate(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Word ||
            (tokens[pos] as Token.Word).value != "every"
        ) {
            return null
        }
        var p = pos + 1
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val monthWord = (tokens[p] as Token.Word).value
        val month = monthValue(monthWord) ?: return null
        p++
        if (p >= tokens.size || tokens[p] !is Token.Number) return null
        val day = (tokens[p] as Token.Number).value
        p++
        return Pair(Yearly(RecurrenceBase.FROM_DUE, month, day), p)
    }

    // everyWeekday: 'every' weekday (',' weekday)*
    private fun parseEveryWeekday(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Word ||
            (tokens[pos] as Token.Word).value != "every"
        ) {
            return null
        }
        var p = pos + 1
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val firstWord = (tokens[p] as Token.Word).value
        val firstDay = weekdayValue(firstWord) ?: return null
        p++
        val days = mutableSetOf(firstDay)
        while (p < tokens.size && tokens[p] is Token.Comma) {
            p++
            if (p >= tokens.size || tokens[p] !is Token.Word) return null
            val dayWord = (tokens[p] as Token.Word).value
            val day = weekdayValue(dayWord) ?: return null
            days.add(day)
            p++
        }
        return Pair(Weekly(RecurrenceBase.FROM_DUE, days), p)
    }

    // everyInterval: 'every' [number] (day|week|month|year)['s']
    private fun parseEveryInterval(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Word ||
            (tokens[pos] as Token.Word).value != "every"
        ) {
            return null
        }
        var p = pos + 1
        val num = if (p < tokens.size &&
            tokens[p] is Token.Number
        ) {
            (tokens[p] as Token.Number).value.also { p++ }
        } else {
            1
        }
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val unitWord = (tokens[p] as Token.Word).value
        val unit = timeUnit(unitWord) ?: return null
        p++
        if (p < tokens.size && tokens[p] is Token.Word && (tokens[p] as Token.Word).value == "s") p++
        return Pair(Interval(RecurrenceBase.FROM_DUE, num, unit), p)
    }

    // monthlyOrdinal: digit+ ('st'|'nd'|'rd'|'th') 'of' 'month'
    private fun parseMonthlyOrdinal(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Number) return null
        var p = pos
        val numStr = StringBuilder()
        while (p < tokens.size && tokens[p] is Token.Number) {
            numStr.append((tokens[p] as Token.Number).value)
            p++
        }
        if (numStr.isEmpty()) return null
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val suffix = (tokens[p] as Token.Word).value
        if (!ordinalSuffix(suffix)) return null
        p++
        if (p >= tokens.size || tokens[p] !is Token.Word || (tokens[p] as Token.Word).value != "of") return null
        p++
        if (p >= tokens.size || tokens[p] !is Token.Word || (tokens[p] as Token.Word).value != "month") return null
        p++
        return Pair(Monthly(RecurrenceBase.FROM_DUE, numStr.toString().toInt()), p)
    }

    // shortForm: ['.'] '+'+ digit [dwm]
    private fun parseShortForm(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        var p = pos
        val hasDot = p < tokens.size && tokens[p] is Token.Dot
        if (hasDot) p++
        if (p >= tokens.size || tokens[p] !is Token.Word || (tokens[p] as Token.Word).value != "+") return null
        var plusCount = 0
        while (p < tokens.size && tokens[p] is Token.Word && (tokens[p] as Token.Word).value == "+") {
            plusCount++
            p++
        }
        if (plusCount == 0) return null
        if (p >= tokens.size || tokens[p] !is Token.Number) return null
        val num = (tokens[p] as Token.Number).value
        p++
        if (p < tokens.size && tokens[p] is Token.Word) {
            val unit = (tokens[p] as Token.Word).value
            val dateUnit = timeUnit(unit) ?: return null
            p++
            val base = if (plusCount >= 2) RecurrenceBase.FROM_DUE else RecurrenceBase.FROM_COMPLETION
            return Pair(Interval(base, num, dateUnit), p)
        }
        return null
    }

    // Try each rule in order; first match that consumes ALL tokens wins
    private fun parseRule(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int> {
        val r1 = parseCatchUp(tokens, pos)
        if (r1 != null && r1.second == tokens.size) return r1
        val r2 = parseYearlyDate(tokens, pos)
        if (r2 != null && r2.second == tokens.size) return r2
        val r3 = parseEveryWeekday(tokens, pos)
        if (r3 != null && r3.second == tokens.size) return r3
        val r4 = parseEveryInterval(tokens, pos)
        if (r4 != null && r4.second == tokens.size) return r4
        val r5 = parseMonthlyOrdinal(tokens, pos)
        if (r5 != null && r5.second == tokens.size) return r5
        val r6 = parseShortForm(tokens, pos)
        if (r6 != null && r6.second == tokens.size) return r6
        throw IllegalArgumentException("Unrecognised token at position $pos")
    }
}
