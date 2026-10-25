package com.singularity.todo.core.search

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * A record the index found, and where in it the word was found.
 *
 * ## Why the record comes with its position
 *
 * The index knows two things about a match: that the row carries the word, and
 * where in the row it carries it. The second fact is not decoration — it is
 * the index's own answer, and it is the answer that is right: the index knows
 * the tokenizer, and it knows which column of the record matched.
 *
 * The alternative is to ask the rule in Kotlin where the word is. The rule
 * knows the term and the text, and it can find the word — but it is a second
 * answer to a question the index has already answered, and for a multi-word
 * term the two answers can differ in position. One question, one answer: the
 * index's.
 *
 * @param record the record the index found.
 * @param ranges where the word was found, in the record's searchable text.
 *   Empty when the record was not read through the index — a broad scan reads
 *   the rows and has no positions to report.
 */
data class IndexedRecord<T>(val record: T, val ranges: ImmutableList<TextMatchRange> = persistentListOf())
