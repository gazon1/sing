package com.singularity.todo.feature.proposals.domain.model

/**
 * What a proposal is about — the thing its changes apply to.
 *
 * ## Why this is a type and not a string
 *
 * The value used to be a bare string that every caller typed by hand: thirteen places
 * wrote `"TASK"`, `"NOTE"`, `"PROJECT"` or `"TAG"`, next to a change that already
 * said what it was about. Two declarations of one fact, and nothing kept them
 * together — a caller that wrote the wrong one wrote a proposal the confirmation
 * screen would never find, and the mistake was invisible until the user looked for
 * a change that was not there.
 *
 * So the change says what it is about (see [ProposalTarget]) and this type is the
 * word for it. The row is addressed by this value, and the value is derived from
 * the change rather than typed beside it.
 *
 * ## The values
 *
 * The storage values are the ones the row already carries, so a row written before
 * this type existed is read as what it says it is. A value this build does not
 * know is [Unknown] — and it keeps the name it was found with, the way the status
 * of a change does: a row this build cannot read must not become a row this build
 * rewrites into something else.
 */
sealed interface ProposalTargetKind {

    /**
     * The value the row carries.
     *
     * The same four values the row has always carried, so a row written before this
     * type existed reads as what it says it is.
     */
    val storageValue: String

    /** The change touches a task. */
    data object Task : ProposalTargetKind {
        override val storageValue: String = "TASK"
    }

    /** The change touches a note. */
    data object Note : ProposalTargetKind {
        override val storageValue: String = "NOTE"
    }

    /** The change touches a project. */
    data object Project : ProposalTargetKind {
        override val storageValue: String = "PROJECT"
    }

    /** The change touches a tag. */
    data object Tag : ProposalTargetKind {
        override val storageValue: String = "TAG"
    }

    /**
     * The kind of target this build does not know.
     *
     * A row written by a newer build carries a kind this one has no word for. The
     * proposal stays where the review screen can reach it, and it does not claim to
     * be about a task or a note — because it is not known to be about either.
     *
     * @property raw The kind as it was stored, kept so the row can be written back
     *   unchanged — a row this build cannot read must not become a row this build
     *   rewrites into something else.
     */
    data class Unknown(val raw: String) : ProposalTargetKind {
        override val storageValue: String get() = raw
    }

    companion object {

        /** The kinds this build knows. */
        private val known: List<ProposalTargetKind> = listOf(Task, Note, Project, Tag)

        /**
         * Reads a stored kind of target.
         *
         * A value this build does not know is [Unknown], not one of the four it
         * does: a proposal about something this build cannot name must not be
         * misaddressed to a task.
         */
        fun read(raw: String): ProposalTargetKind = known.firstOrNull { it.storageValue == raw } ?: Unknown(raw)

        /**
         * The kind of target a set of changes is about.
         *
         * A proposal holds changes about one thing, and each change says what it
         * is about. So the kind is read from the changes — and a batch that mixes
         * kinds is refused rather than addressed to whichever change happened to
         * come first.
         */
        fun of(changes: List<ProposalItemKind>): ProposalTargetKind {
            val kinds = changes.map { it.target.targetKind }.distinct()
            require(kinds.isNotEmpty()) { "A proposal holds at least one change." }
            require(kinds.size == 1) {
                "A proposal holds changes about one thing, but these are about " +
                    "${kinds.joinToString { it.storageValue }}."
            }
            return kinds.first()
        }
    }
}
