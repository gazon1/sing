package com.singularity.todo.feature.plans.domain.model

/**
 * The size of one timeline period, and the horizon that goes with it.
 *
 * The number of periods, not their length, is what the user reads as "how far
 * ahead this goes": thirty days, twelve weeks, twelve months.
 *
 * The set is open — a year level is a later addition — but these three are what
 * the screen offers.
 */
enum class PlansGranularity(val periodCount: Int) {
    /** One calendar day per section. */
    Day(30),

    /** One Monday-to-Sunday week per section. */
    Week(12),

    /** One calendar month per section. */
    Month(12),
    ;

    companion object {
        /** The granularity a profile that has never chosen one gets. */
        val Default: PlansGranularity = Day

        /**
         * Reads a stored value back, falling back to [Default] for anything that
         * is not one of the three cases.
         *
         * A stored preference outlives the code that wrote it: a value this
         * build does not know about must leave the screen with a grouping
         * rather than without one.
         */
        fun fromStorage(raw: String?): PlansGranularity =
            entries.firstOrNull { it.name == raw } ?: Default
    }
}
