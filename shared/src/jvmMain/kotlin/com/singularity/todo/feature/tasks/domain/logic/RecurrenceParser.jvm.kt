package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import kotlinx.datetime.DateTimeUnit

/**
 * Manual recursive-descent parser for Orgzly/Tasks.org recurrence DSL.
 *
 * Grammar (in order):
 *   catchUp     → '!' '+'+ digit [dwm]
 *   yearlyDate  → 'every' monthName digit
 *   everyWeekday → 'every' weekday (',' weekday)*
 *   everyInterval → 'every' [number] (day|week|month|year)['s']
 *   monthlyOrdinal → digit+ ('st'|'nd'|'rd'|'th') 'of' 'month'
 *   shortForm   → ['.'] '+'+ digit [dwm]
 */
actual object RecurrenceParser {

    actual fun parse(input: String): RecurrenceSpec {
        val s = input.trim()
        require(s.isNotBlank()) { "Recurrence rule must not be blank" }
        val tokens = tokenize(s.lowercase())
        if (tokens.isEmpty()) throw IllegalArgumentException("Unrecognised recurrence rule: '$s'")

        return try {
            parseRule(tokens, 0).first
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException("Unrecognised recurrence rule: '$s'", e)
        }
    }

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
                c == '!' -> { tokens.add(Token.Bang(i)); i++ }
                c == '.' -> { tokens.add(Token.Dot(i)); i++ }
                c == ',' -> { tokens.add(Token.Comma(i)); i++ }
                c == '+' -> { tokens.add(Token.Word("+", i)); i++ }
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
                c == ' ' -> i++ // skip spaces
                else -> throw IllegalArgumentException("Unexpected character: '$c' at position $i")
            }
        }
        return tokens
    }

    // weekname → weekday base (Mon, Tue, etc.)
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

    // monthName → month base (Jan, Feb, etc.)
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

    // timeUnit → d/w/m/y
    private fun timeUnit(word: String): DateTimeUnit.DateBased? = when (word) {
        "d", "day", "days" -> DateTimeUnit.DAY
        "w", "week", "weeks" -> DateTimeUnit.WEEK
        "m", "month", "months" -> DateTimeUnit.MONTH
        "y", "year", "years" -> DateTimeUnit.YEAR
        else -> null
    }

    // ordinalSuffix → st|nd|rd|th
    private fun ordinalSuffix(word: String): Boolean = word == "st" || word == "nd" || word == "rd" || word == "th"

    // ─── Parsers ───────────────────────────────────────────────────────────────

    // catchUp: ! +digit [dwm]  (! always means CATCH_UP, count is after the +)
    private fun parseCatchUp(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Bang) return null
        var p = pos + 1
        // consume one or more '+'
        if (p >= tokens.size || tokens[p] !is Token.Word || (tokens[p] as Token.Word).value != "+") return null
        var plusCount = 0
        while (p < tokens.size && tokens[p] is Token.Word && (tokens[p] as Token.Word).value == "+") {
            plusCount++; p++
        }
        if (plusCount == 0) return null
        if (p >= tokens.size || tokens[p] !is Token.Number) return null
        val num = (tokens[p] as Token.Number).value; p++
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val unit = (tokens[p] as Token.Word).value; p++
        val dateUnit = timeUnit(unit) ?: return null
        // ! always means CATCH_UP, the + count is the multiplier
        return Pair(Interval(RecurrenceBase.CATCH_UP, num, dateUnit), p)
    }

    // yearlyDate: every monthName digit
    private fun parseYearlyDate(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Word || (tokens[pos] as Token.Word).value != "every") return null
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

    // everyWeekday: every weekday (',' weekday)*
    private fun parseEveryWeekday(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Word || (tokens[pos] as Token.Word).value != "every") return null
        var p = pos + 1
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val firstWord = (tokens[p] as Token.Word).value
        val firstDay = weekdayValue(firstWord) ?: return null
        p++
        val days = mutableSetOf(firstDay)
        while (p < tokens.size && tokens[p] is Token.Comma) {
            p++ // skip comma
            if (p >= tokens.size || tokens[p] !is Token.Word) return null
            val dayWord = (tokens[p] as Token.Word).value
            val day = weekdayValue(dayWord) ?: return null
            days.add(day); p++
        }
        return Pair(Weekly(RecurrenceBase.FROM_DUE, days), p)
    }

    // everyInterval: every [number] (day|week|month|year)['s']
    private fun parseEveryInterval(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Word || (tokens[pos] as Token.Word).value != "every") return null
        var p = pos + 1
        val num = if (p < tokens.size && tokens[p] is Token.Number) { (tokens[p] as Token.Number).value.also { p++ } } else 1
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val unitWord = (tokens[p] as Token.Word).value
        val unit = timeUnit(unitWord) ?: return null
        p++
        // optional 's'
        if (p < tokens.size && tokens[p] is Token.Word && (tokens[p] as Token.Word).value == "s") p++
        return Pair(Interval(RecurrenceBase.FROM_DUE, num, unit), p)
    }

    // monthlyOrdinal: number+ ('st'|'nd'|'rd'|'th') 'of' 'month'
    private fun parseMonthlyOrdinal(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        if (pos >= tokens.size || tokens[pos] !is Token.Number) return null
        var p = pos
        // number part
        val numStr = StringBuilder()
        while (p < tokens.size && tokens[p] is Token.Number) {
            numStr.append((tokens[p] as Token.Number).value); p++
        }
        if (numStr.isEmpty()) return null
        if (p >= tokens.size || tokens[p] !is Token.Word) return null
        val suffix = (tokens[p] as Token.Word).value
        if (!ordinalSuffix(suffix)) return null; p++
        if (p >= tokens.size || tokens[p] !is Token.Word || (tokens[p] as Token.Word).value != "of") return null; p++
        if (p >= tokens.size || tokens[p] !is Token.Word || (tokens[p] as Token.Word).value != "month") return null; p++
        return Pair(Monthly(RecurrenceBase.FROM_DUE, numStr.toString().toInt()), p)
    }

    // shortForm: ['.'] +digit [dwm]
    private fun parseShortForm(tokens: List<Token>, pos: Int): Pair<RecurrenceSpec, Int>? {
        var p = pos
        val hasDot = p < tokens.size && tokens[p] is Token.Dot; if (hasDot) p++
        if (p >= tokens.size || tokens[p] !is Token.Word || (tokens[p] as Token.Word).value != "+") return null
        var plusCount = 0
        while (p < tokens.size && tokens[p] is Token.Word && (tokens[p] as Token.Word).value == "+") {
            plusCount++; p++
        }
        if (plusCount == 0) return null
        if (p >= tokens.size || tokens[p] !is Token.Number) return null
        val num = (tokens[p] as Token.Number).value; p++
        if (p < tokens.size && tokens[p] is Token.Word) {
            val unit = (tokens[p] as Token.Word).value
            val dateUnit = timeUnit(unit) ?: return Pair(Interval(RecurrenceBase.FROM_DUE, num, DateTimeUnit.DAY), p)
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
