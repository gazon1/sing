package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Flags any reference to `ProfileAwareCurrentUser` via its static companion object
 * (`ProfileAwareCurrentUser.scopedUserId`, `.current`, `.instance`, or any
 * `ProfileAwareCurrentUser.Companion.*` call).
 *
 * After PR12b, `ProfileAwareCurrentUser` is a pure DI class — all access must be
 * through constructor injection. The static companion was removed.
 *
 * Legitimate (not flagged):
 * - Direct class instantiation: `ProfileAwareCurrentUser(get(), get(), scope)`
 * - Type references in declarations
 * - Any access to other classes
 *
 * @see NoStaticProfileAwareCurrentUserProvider for how this rule is registered.
 */
class NoStaticProfileAwareCurrentUserRule(config: Config) : Rule(config, "", null) {

    private val targetClass = "com.singularity.todo.feature.profile.ProfileAwareCurrentUser"
    private val companionMembers = setOf("scopedUserId", "current", "instance", "setInstance")

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)

        // Check for: ProfileAwareCurrentUser.scopedUserId / .current / .instance / .setInstance()
        val receiver = expression.receiverExpression as? KtNameReferenceExpression ?: return
        val receiverText = receiver.getReferencedName()

        when (receiverText) {
            targetClass -> {
                // ProfileAwareCurrentUser.scopedUserId / .current / .instance
                val selector = expression.selectorExpression as? KtNameReferenceExpression ?: return
                if (selector.getReferencedName() in companionMembers) {
                    report(expression, selector)
                }
            }
            "${targetClass}.Companion" -> {
                // ProfileAwareCurrentUser.Companion.* (explicit companion reference)
                val selector = expression.selectorExpression as? KtNameReferenceExpression ?: return
                if (selector.getReferencedName() in companionMembers) {
                    report(expression, selector)
                }
            }
        }
    }

    private fun report(element: KtDotQualifiedExpression, namePart: KtNameReferenceExpression) {
        val memberName = namePart.getReferencedName()
        report(
            Finding(
                entity = Entity.from(element),
                message = buildString {
                    append("ProfileAwareCurrentUser companion accessor '$memberName' is forbidden.")
                    append(" Access ProfileAwareCurrentUser via constructor injection.")
                    append(" The static companion was removed in PR12b.")
                },
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}
