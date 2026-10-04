package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Bans `userId` and `scopedUserId` parameters in observe methods of repository interfaces.
 *
 * [GenericUserScopedRepository][com.singularity.todo.core.repository.GenericUserScopedRepository]
 * contract requires that observations are self-scoped via
 * [ProfileAwareCurrentUser][com.singularity.todo.feature.profile.ProfileAwareCurrentUser],
 * not passed as an explicit parameter. An observe method that takes `userId` or
 * `scopedUserId` forces every caller to snapshot the value eagerly at call site,
 * creating a class of bug where profile switches are not observed until the VM
 * is recreated.
 *
 * ```
 * // VIOLATION — userId should come from ambient ProfileAwareCurrentUser
 * fun watchProposalsForTask(taskId: TaskId, userId: UserId): Flow<List<AiProposal>>
 *
 * // CORRECT — self-scoped, uses flatMapLatest internally
 * fun watchProposalsForTask(taskId: TaskId): Flow<List<AiProposal>>
 * ```
 *
 * This rule also flags implementations that declare such parameters, ensuring
 * the contract cannot be re-introduced at the concrete level. (This was claimed
 * here while the guard was `endsWith("Repository")`, which can never match
 * `…RepositoryImpl`; see [isRepositoryLike].)
 */
class ProhibitUserIdInObserveRule(config: Config) : Rule(config, "", null) {

    override fun visitKtFile(root: KtFile) {
        super.visitKtFile(root)
        // Use a visitor that traverses the entire PSI tree, including nested
        // KtScript wrappers that compileContentForTest wraps around test code.
        root.accept(object : KtTreeVisitorVoid() {
            override fun visitClass(klass: KtClass) {
                val className = klass.name
                if (className != null && isRepositoryLike(className)) {
                    val body = klass.body
                    if (body != null) {
                        for (declaration in body.declarations) {
                            if (declaration is KtNamedFunction) {
                                checkFunction(declaration, className)
                            }
                        }
                    }
                }
                super.visitClass(klass)
            }
        })
    }

    /**
     * True for a repository *interface* (`…Repository`) or a concrete
     * implementation (`…RepositoryImpl`).
     *
     * The old guard was `endsWith("Repository")`, which can never match
     * `TaskRepositoryImpl` — so despite the KDoc promising it, implementations
     * were never inspected. An interface bans the parameter and its implementation
     * has to honour the same signature, so both ends are checked.
     */
    private fun isRepositoryLike(className: String): Boolean =
        className.endsWith("Repository") || className.endsWith("RepositoryImpl")

    private fun checkFunction(fn: KtNamedFunction, className: String) {
        val name = fn.name ?: return
        if (!name.startsWith("watch") && !name.startsWith("observe")) return
        val returnType = fn.typeReference?.text ?: return
        if (!returnType.startsWith("Flow") &&
            !returnType.startsWith("SharedFlow") &&
            !returnType.startsWith("StateFlow")
        ) {
            return
        }

        val userIdParam = fn.valueParameters.find {
            it.name == "userId" || it.name == "scopedUserId"
        } ?: return

        report(
            Finding(
                entity = Entity.from(userIdParam),
                message = "Repository '$className' observe method '$name' must NOT " +
                    "take 'userId' or 'scopedUserId' as a parameter. " +
                    "Observations are self-scoped via 'ProfileAwareCurrentUser.scopedUserId'. " +
                    "Use 'flatMapLatest(currentUser.scopedUserId) { ... }' inside the implementation.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }
}

/**
 * Registers [ProhibitUserIdInObserveRule] in the `user-scoped-repository` rule set.
 */
class UserScopedRepositoryRulesProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("user-scoped-repository")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("ProhibitUserIdInObserve") to { cfg: Config -> ProhibitUserIdInObserveRule(cfg) },
        ),
    )
}
