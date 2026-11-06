package com.singularity.todo.feature.agenda.domain.model

/**
 * The date buckets a selector holds.
 *
 * A section picks tasks with a rule, and the buckets are the date-shaped part of that
 * rule — the part a user names when they say "this week" or "no date". So the buckets
 * a preset holds are the buckets its sections hold, and the question a preset answers
 * is the question of those buckets. That is what lets the vocabulary say what a name
 * answers in the app's own words, instead of in a hand-written summary that can drift
 * away from the code it describes.
 *
 * The rules that take buckets away hold none of their own: a `Not` inside an `AllOf`
 * — as the Upcoming tab does with its "This Week" section, which leaves out the days
 * the Today tab owns — narrows a bucket without adding one. So the buckets a section
 * holds are the ones it selects, and the narrowing is a fact about the section rather
 * than another name.
 */
fun Selector.heldBuckets(): Set<RelativeBucket> =
    when (this) {
        is Selector.DateBucket -> setOf(bucket)
        is Selector.AllOf -> children.flatMapTo(mutableSetOf()) { it.heldBuckets() }
        is Selector.AnyOf -> children.flatMapTo(mutableSetOf()) { it.heldBuckets() }
        else -> emptySet()
    }
