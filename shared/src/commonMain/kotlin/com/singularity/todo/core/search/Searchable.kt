package com.singularity.todo.core.search

/**
 * A record that can be searched, and the surfaces of it that a term may
 * match.
 *
 * ## Why this exists
 *
 * F3 says what a record is searched by: a task by its title and its
 * description, a note by its title, a project and a tag by their name. That
 * map was written at every call site, and the call sites disagreed — the
 * contains branch of one search read the title while the word branch of
 * another read the title and the description, and the difference between them
 * was a record silently lost.
 *
 * The map belongs to the record, not to the caller: a task knows what it is
 * searched by, and a caller that has to remember it is a caller that will
 * eventually remember it wrong.
 *
 * ## What it is not
 *
 * It is not the matcher. The matcher is [TextMatch] and [SearchMode]; this is
 * the question *which text* of the record the matcher is asked about. The
 * protocol lives in `core` because the matcher lives there, and the models
 * that implement it live in the features — the port is in the core, the
 * policy is in the feature, the way the repository keeps it everywhere else.
 */
interface Searchable {

    /**
     * The surfaces of this record that a term may match.
     *
     * A `null` surface matches nothing — a task without a description is
     * searched by its title alone.
     */
    val searchableSurfaces: List<String?>
}
