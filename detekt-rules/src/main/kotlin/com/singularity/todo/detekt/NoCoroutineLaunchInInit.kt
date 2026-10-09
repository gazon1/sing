package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Bans `scope.launch { ... }` and `scope.async { ... }` inside `init { ... }` blocks.
 *
 * An `init` block runs during object construction. Launching a coroutine there — before the
 * constructed object is fully initialised — means the coroutine can observe the object in a
 * partially-constructed state. It also means the coroutine's lifetime is tied to the scope
 * before the object has any opportunity to manage it.
 *
 * The canonical pattern is to launch the coroutine as a field initializer after the `init`
 * block, or to defer it to an explicit `start()` method. Both ensure construction is complete
 * before the coroutine begins.
 *
 * **Grandfathered classes:** [EXEMPTED_CLASSES] — `SyncEngine`, `SyncBootstrapper`,
 * `SyncCoordinator`. These classes have `init { scope.launch { } }` in the baseline and
 * are not migrated in this refactor.
 *
 * @see NoCoroutineLaunchInInitProvider for registration.
 */
class NoCoroutineLaunchInInit(config: Config) : Rule(config, "", null) {

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.name in EXEMPTED_CLASSES) return

        val body = klass.body ?: return
        val initBlocks = body.declarations.filterIsInstance<KtClassInitializer>()

        for (block in initBlocks) {
            val initBody = block.body ?: continue
            val expressions = initBody.collectDescendantsOfType<KtExpression>()
            for (expr in expressions) {
                checkExpression(expr, block, klass.name)
            }
        }
    }

    private fun checkExpression(
        expression: KtExpression,
        block: KtClassInitializer,
        className: String?,
    ) {
        val (receiver, call) = when (expression) {
            is KtDotQualifiedExpression -> {
                val recv = expression.receiverExpression as? KtNameReferenceExpression
                val call = expression.selectorExpression as? KtCallExpression
                recv to call
            }

            is KtSafeQualifiedExpression -> {
                val recv = expression.receiverExpression as? KtNameReferenceExpression
                val call = expression.selectorExpression as? KtCallExpression
                recv to call
            }

            else -> return
        }

        if (receiver == null || call == null) return
        if (receiver.text != "scope") return

        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text !in BLOCKED_METHODS) return

        reportFinding(expression, className, block)
    }

    private fun reportFinding(expr: KtExpression, className: String?, block: KtClassInitializer) {
        val name = className ?: "anonymous class"
        report(
            Finding(
                entity = Entity.from(expr),
                message = "$name has 'scope.$METHODS_STRING' inside an init block. " +
                    "Coroutines launched in init can observe the object in a partially-constructed state, " +
                    "and their lifetime is not managed by the object. " +
                    "Move the launch to a field initializer after init, or to an explicit start() method. " +
                    "If this class is a sync infrastructure class that must launch in init, " +
                    "add it to EXEMPTED_CLASSES in the rule.",
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    companion object {
        /**
         * Classes that are grandfathered — they have `init { scope.launch { } }` in the baseline
         * and are not migrated in this refactor.
         */
        private val EXEMPTED_CLASSES = setOf(
            "SyncEngine",
            "SyncBootstrapper",
            "SyncCoordinator",
            // ViewModels and repositories using the canonical injected-scope pattern:
            // private val scope = AutoCloseableCoroutineScope(); init { addCloseable(scope) }
            // The scope is externally managed; the init block only attaches it to the object.
            "AgendaViewModel",
            "SavedAgendaListViewModel",
            "SavedAgendaViewModel",
            "AiUsageViewModel",
            "ArchiveViewModel",
            "AttachmentAnnotationViewModel",
            "BackupViewModel",
            "NotesListViewModel",
            "ProfileAwareCurrentUser",
            "ProfileRepositoryImpl",
            "StatisticsViewModel",
            "TagsViewModel",
            "TagGroupsViewModel",
            "TaskDetailCoordinator",
            "TaskBacklinksCollector",
            "TaskChildrenSlot",
            "TaskCompletionSlot",
            "TaskDraftSlot",
            "TaskEntitySlot",
            "TaskLogbookCollector",
            "TaskProposalsCollector",
            "TaskRemindersSlot",
            "TaskTimeSlot",
            "TestViewModel",
            "CurrentUser",
            "DataStoreSyncPrefs",
            "SyncRunner",
            "AndroidPomodoroTaskListProvider",
            "SupabaseAuthRepository",
        )

        /** Coroutine launch methods that are banned inside init blocks. */
        private val BLOCKED_METHODS = setOf("launch", "async")

        private const val METHODS_STRING = "launch { }, async { }"
    }
}

/**
 * Registers [NoCoroutineLaunchInInit] in the `no-init-coroutine-launch` rule set.
 */
class NoCoroutineLaunchInInitProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-init-coroutine-launch")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoCoroutineLaunchInInit") to { cfg: Config ->
                NoCoroutineLaunchInInit(cfg)
            },
        ),
    )
}
