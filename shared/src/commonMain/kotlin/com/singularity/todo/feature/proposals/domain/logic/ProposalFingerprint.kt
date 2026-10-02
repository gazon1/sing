package com.singularity.todo.feature.proposals.domain.logic

import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.fingerprintTarget

/**
 * Builds the stable identity of a proposed change.
 *
 * ## Why this exists
 *
 * The "recently rejected" list we put in the model's prompt is a *hint*, not a
 * guarantee — a model can and does ignore it. The fingerprint is the guarantee: a
 * change whose fingerprint the user has already rejected cannot be re-proposed,
 * because the validation step refuses to assemble it. Two independent defenses,
 * because prompt-level steering alone is not a security property.
 *
 * ## Stability requirements
 *
 * The same logical change must hash identically across processes and app versions,
 * because fingerprints are persisted and compared after a restart. So the digest is
 * built from an explicit, order-independent string — never from `hashCode()`, whose
 * value for strings is not contractual across JVMs, and never from the serialized
 * JSON, whose field order is an implementation detail.
 */
object ProposalFingerprint {

    /** Unit separator — cannot appear in a tag name, title, or tag id. */
    private const val SEP = "\u001F"

    /**
     * The fingerprint of "apply [kind] to [targetId]".
     *
     * Order-independent: two items that list the same tags in a different order are
     * the same proposal and must not both survive a rejection.
     */
    fun of(kind: ProposalItemKind, targetId: String): String {
        val canonical = listOf(
            kind::class.simpleName ?: "Unknown",
            targetId,
            kind.fingerprintTarget,
        ).joinToString(SEP)
        return digest(canonical)
    }

    /**
     * A 64-bit FNV-1a digest rendered as 16 lowercase hex chars.
     *
     * FNV-1a is chosen over a crypto hash because this is a collision-avoidance key
     * for a set of a few dozen entries, not a security boundary — and because it has
     * no platform dependency, so the JVM and Android targets agree byte for byte.
     */
    private fun digest(input: String): String {
        var hash = FNV_OFFSET_BASIS
        for (byte in input.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        // Mask before formatting: Long.MIN_VALUE has no positive counterpart, so
        // absoluteValue on it would still be negative.
        return (hash and Long.MAX_VALUE).toString(16).padStart(16, '0')
    }

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
}
