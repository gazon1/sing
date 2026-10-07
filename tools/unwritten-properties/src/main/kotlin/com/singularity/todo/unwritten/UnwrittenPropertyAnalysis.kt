package com.singularity.todo.unwritten

/**
 * The analysis, with no dependency on KSP.
 *
 * Split out deliberately. The question — "is this property read anywhere and written
 * nowhere?" — is a question about a *set*, not about syntax: a declaration in one file
 * is decided by references in another. That is why the first attempt at this check was
 * a detekt rule, and it could not work: a detekt rule visits one file at a time, so a
 * property written in `SomeViewModel.kt` is indistinguishable from one written nowhere
 * when the declaration is inspected in `SomeUiState.kt`. The rule would have reported
 * most of the healthy state classes as broken.
 *
 * KSP is what makes the question answerable, because a processor sees every file in the
 * module in one pass. Keeping the decision here, over plain data, means the rules below
 * are testable without a compiler in the loop — and the set of rules is where the
 * false positives live, so that is the part worth testing.
 *
 * Every function is pure. Given the same [Declaration]s and [Reference]s, the same
 * findings come out.
 */

/** A property that could be reported: a `val` on a state-shaped class. */
data class Declaration(
    /** Fully qualified owner, e.g. `com.singularity.todo.feature.x.LoginUiState`. */
    val owner: String,
    val name: String,
    /** KSP declaration kind — a class member, or a constructor parameter. */
    val isConstructorParameter: Boolean,
)

/**
 * One resolved use of a property.
 *
 * [isWrite] is the whole difficulty. `state.copy(isLoading = true)` *reads* the state
 * object and *writes* the property; `state.isLoading` only reads. Conflating them is what
 * made the regex prototype report 63 candidates whose first entry was healthy code.
 */
data class Reference(
    val owner: String,
    val name: String,
    val isWrite: Boolean,
)

/** One reported property, with the reason it survived every exemption. */
data class Finding(
    val owner: String,
    val name: String,
    val explanation: String,
)

/**
 * Properties whose owner name marks them as UI state.
 *
 * Not a heuristic for speed — it is the scoping decision that keeps the population
 * reviewable. The defect was found twice in `feature/calendar_sync` on state classes
 * and nowhere else, and a check whose every finding must be read by hand is only
 * usable if that hand-reading is finite.
 */
private val STATE_OWNER_SUFFIXES = listOf("UiState", "State")

/** Owners whose properties are written by a framework rather than by Kotlin code. */
private val FRAMEWORK_WRITTEN_OWNERS = listOf(
    // Room generates the DAO, the entity's constructor and its column mappings.
    "Dao",
    "Entity",
    // kotlinx.serialization writes the backing fields reflectively from the constructor.
    "Serializable",
)

fun isStateOwner(owner: String): Boolean {
    val simpleName = owner.substringAfterLast('.')
    return STATE_OWNER_SUFFIXES.any { simpleName.endsWith(it) }
}

/**
 * The properties to report: read somewhere, written nowhere, and not exempt.
 *
 * [writeCount] rather than a set of write references, because a property written once
 * and a property written from three places are both "written" — and the count is what
 * lets a caller report *how* suspicious something is without this function deciding.
 */
fun findNeverWritten(
    declarations: List<Declaration>,
    references: List<Reference>,
): List<Finding> {
    val reads = mutableSetOf<Pair<String, String>>()
    val writeCounts = mutableMapOf<Pair<String, String>, Int>()

    for (reference in references) {
        val key = reference.owner to reference.name
        if (reference.isWrite) {
            writeCounts[key] = (writeCounts[key] ?: 0) + 1
        } else {
            reads += key
        }
    }

    val findings = mutableListOf<Finding>()

    for (declaration in declarations) {
        if (!isStateOwner(declaration.owner)) continue

        val simpleOwner = declaration.owner.substringAfterLast('.')
        if (FRAMEWORK_WRITTEN_OWNERS.any { simpleOwner.endsWith(it) }) continue

        val key = declaration.owner to declaration.name

        // Written at least once: healthy, whatever else is true of it.
        if ((writeCounts[key] ?: 0) > 0) continue

        // Never read: not this rule's business. A private, unread property is a
        // different smell with a different fix, and reporting it here would mix two
        // defects into one gate.
        if (key !in reads) continue

        findings += Finding(
            owner = declaration.owner,
            name = declaration.name,
            explanation = if (declaration.isConstructorParameter) {
                "'${declaration.name}' is a state property that this module reads and never " +
                    "assigns. It has a default value, so the screen renders a constant and " +
                    "believes it is showing something the user or a pass can change."
            } else {
                "'${declaration.name}' is a state property that this module reads and never " +
                    "assigns. No code path can change it, so whatever the screen renders for " +
                    "it is fixed at construction."
            },
        )
    }

    // Stable order: a gate that reshuffles its findings between runs cannot be
    // diffed, and a baseline nobody can read is a baseline nobody maintains.
    return findings.sortedWith(compareBy({ it.owner }, { it.name }))
}