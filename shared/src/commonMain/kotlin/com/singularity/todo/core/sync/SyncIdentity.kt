package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError

/**
 * A local identifier that names both the account and the profile it belongs to.
 *
 * ## Why the pair is encoded rather than carried
 *
 * Two profiles of the same account can hold a task with the same id — the user
 * imported it into both. Every store the sync path writes to is keyed on
 * `(owner_id, profile_id, …)`: the shadow, the outbox's coalescing key, the
 * server's six document tables. Something has to turn a scope into that pair and
 * back, and the honest place for it is one named type rather than a string
 * concatenated at four call sites, each of which would then have to decide for
 * itself what a missing half means.
 *
 * ## Why a value class and not two fields on the entity
 *
 * A `SyncScope` already exists and is the right thing to *hold*. This exists to be
 * the flat form that fits in a primary key and travels through a queue, and it is
 * deliberately not interchangeable with a scope: constructing one validates both
 * halves, so a half-built identity cannot be represented.
 */
@JvmInline
value class SyncIdentity(val encoded: String) {

    init {
        require(encoded.isNotBlank()) { "sync identity must not be blank" }
        require(encoded.count { it == SEPARATOR } == 1) {
            "a sync identity is exactly one '$SEPARATOR'-separated pair, got '$encoded'"
        }
        val (owner, profile) = encoded.split(SEPARATOR)
        require(owner.isNotBlank() && profile.isNotBlank()) {
            "neither half of a sync identity may be blank, got '$encoded'"
        }
    }

    val ownerId: String get() = encoded.substringBefore(SEPARATOR)

    val profileId: String get() = encoded.substringAfter(SEPARATOR)

    fun toScope(): SyncScope = SyncScope(ownerId = ownerId, profileId = profileId)

    override fun toString(): String = encoded

    companion object {
        const val SEPARATOR = '/'

        fun of(scope: SyncScope): SyncIdentity = SyncIdentity("${scope.ownerId}$SEPARATOR${scope.profileId}")
    }
}

/**
 * Turns a stored identity back into a scope, at the network boundary.
 *
 * ## Why this refuses rather than defaulting
 *
 * The obvious fallback for an unparseable value is `"default"` for the profile,
 * because that is what a fresh install calls its first profile. Taking it would
 * mean a row written under the wrong scope — data attributed to an account or a
 * profile the user has never had, and read back to a place that has no such row.
 * A refusal is loud, happens once, and names the value that was wrong; a silent
 * default happens on every read and is not attributable to anything.
 *
 * The mapper is a class rather than a set of top-level functions so the boundary
 * has a name, and so a caller cannot accidentally use the encoder and skip the
 * checks on the way back.
 */
class SyncIdentityMapper {

    /**
     * Parses [encoded] into a scope.
     *
     * @throws AppError.Validation when the value is not a well-formed identity. A
     *   stored identity is either one this build wrote or it is corruption; there
     *   is no third reading, and neither is worth a default.
     */
    fun toScope(encoded: String): SyncScope = try {
        SyncIdentity(encoded).toScope()
    } catch (e: IllegalArgumentException) {
        throw AppError.Validation(
            "Stored sync identity '$encoded' is not an 'owner/profile' pair. Refusing to " +
                "guess a scope: a wrong one attributes data to an account or profile that does " +
                "not exist, and reads back to a row that was never written.",
            code = "sync.malformed_identity",
            cause = e,
        )
    }

    /** Encodes [scope] for storage. */
    fun encode(scope: SyncScope): SyncIdentity = SyncIdentity.of(scope)
}
