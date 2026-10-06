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
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans direct references to `Dispatchers.IO`, `Dispatchers.Default` and
 * `Dispatchers.Main` in **commonMain production code**.
 *
 * Dispatchers should be injected as `CoroutineDispatcher` parameters so call sites are
 * deterministic and testable. Direct dispatcher references in shared logic make tests
 * non-hermetic and couple business logic to a specific concurrency model.
 *
 * ## Scope: commonMain only, and why
 *
 * Platform source sets are deliberately out of scope. This project's KMP port convention
 * puts the interface in `commonMain` and the implementation in `androidMain`/`jvmMain`,
 * and choosing the dispatcher is the *job of the implementation*. Verified against the
 * tree on 2026-10-05:
 *
 * - `commonMain` has exactly **1** occurrence: `core/log/FileLogWriter.kt`, which is the
 *   whitelisted one below.
 * - `jvmMain` has 8, all inside port implementations (`FileRevealer.jvm`,
 *   `JvmSecureStorage`, `createBackgroundScope`).
 * - `androidMain` has 11, same story (`AndroidSecureStorage`, `AndroidCalendarProvider`,
 *   `createBackgroundScope`, …).
 *
 * Banning dispatchers in those files would ban the port implementations from
 * implementing their ports, so the rule stops at the commonMain boundary.
 *
 * ## Whitelist
 *
 * `core/log/FileLogWriter.kt` may use `Dispatchers.IO.limitedParallelism(1)`: a
 * single-threaded IO dispatcher is what guarantees ordered log writes. The whitelist is
 * by file path, not by pattern, to keep the exception narrow.
 *
 * `Dispatchers.IO.limitedParallelism(n)` is additionally allowed in any commonMain file,
 * since bounding parallelism is a safe wrapper rather than an unscheduled hop.
 *
 * ## A note on how this used to be broken
 *
 * This rule could not fire at all until 2026-10-05. It required the selector to be a
 * `KtCallExpression`, but in `Dispatchers.IO` the selector is a `KtNameReferenceExpression`
 * (`IO` is a property, not a call) — and in `Dispatchers.IO.limitedParallelism(1)` the
 * receiver is a `KtDotQualifiedExpression`, not a bare name. Both shapes hit an early
 * `return`, so `visitDotQualifiedExpression` always returned without reporting. It was
 * registered, packaged and referenced by a backlog entry, and it was a no-op. The tests
 * were correct; the rule was wrong.
 *
 * @see NoDirectDispatchersRuleProvider for registration.
 */
class NoDirectDispatchersRule(config: Config) : Rule(config, "", null) {

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        checkExpression(expression)
    }

    private fun checkExpression(expr: KtDotQualifiedExpression) {
        val receiver = expr.receiverExpression as? KtNameReferenceExpression
        val selector = expr.selectorExpression as? KtNameReferenceExpression
        val message = NoDirectDispatchersPolicy.violation(
            filePath = expr.containingKtFile.virtualFilePath,
            receiver = receiver?.text,
            selector = selector?.text,
            limitedWrapper = isLimitedParallelismWrapper(expr),
        ) ?: return
        report(
            Finding(
                entity = Entity.from(expr),
                message = message,
                references = emptyList(),
                suppressReasons = emptyList(),
            ),
        )
    }

    /**
     * True when this expression is the receiver of a `.limitedParallelism(n)` call, i.e.
     * the code reads `Dispatchers.IO.limitedParallelism(1)`. Bounding parallelism keeps
     * the work on an IO dispatcher but caps concurrency, so it is allowed anywhere.
     */
    private fun isLimitedParallelismWrapper(expr: KtDotQualifiedExpression): Boolean {
        val parent = expr.parent as? KtDotQualifiedExpression ?: return false
        if (parent.receiverExpression !== expr) return false
        val outerSelector = parent.selectorExpression as? KtCallExpression ?: return false
        val callee = outerSelector.calleeExpression as? KtNameReferenceExpression ?: return false
        return callee.text == "limitedParallelism" && outerSelector.valueArguments.isNotEmpty()
    }
}

/**
 * The decision [NoDirectDispatchersRule] makes, as a pure function of its inputs.
 *
 * Split out because the rule's decisions are all path-based, and
 * `compileContentForTest(content, Path)` **discards the directory component** of the
 * path you pass it — `virtualFilePath` comes back as `/X.kt` regardless. So a path-scoped
 * rule cannot be unit-tested through the PSI layer alone. `NoDirectClockSystemRule` has
 * the same problem, and its `FileLogWriter` whitelist has no test for exactly this
 * reason. Testing the policy directly is the only way to cover those branches.
 */
internal object NoDirectDispatchersPolicy {

    private val BANNED_DISPATCHERS = setOf("IO", "Default", "Main")

    /**
     * Narrow whitelist: only the two files that *are* the composition points may use a bare
     * `Dispatchers` reference.
     *
     * `core/log/FileLogWriter.kt` was already here. `core/coroutines/BackgroundScope.kt`
     * joined it on 2026-10-05, and the reason is a move rather than a new exemption: that
     * file used to declare `expect fun createBackgroundScope()` with `Dispatchers.Default`
     * mentioned only in prose, and the two actuals that really used it lived in `jvmMain` and
     * `androidMain` — which this rule exempts wholesale, as it should. Collapsing the
     * expect/actual into one commonMain function moved a deliberate, already-documented
     * decision into the source set the ban applies to.
     *
     * The rule's own KDoc has always listed `createBackgroundScope` among the operations that
     * intentionally hardcode the dispatcher. Exempting the file is the narrow way to make the
     * code match the documentation; the broad way — allowing `Dispatchers.Default` anywhere in
     * `core/coroutines` — would re-open the ban for every future file in that package.
     */
    val ALLOWED_FILES = setOf(
        "core/log/FileLogWriter.kt",
        "core/coroutines/BackgroundScope.kt",
    )

    /** Kept as a named constant for the single-file case in existing callers and messages. */
    const val ALLOWED_FILE = "core/log/FileLogWriter.kt"

    private val PLATFORM_SOURCE_SETS = listOf("jvmMain", "androidMain", "iosMain", "jsMain", "nativeMain")
    private val TEST_SOURCE_SETS = listOf("commonTest", "jvmTest", "androidTest", "iosTest", "jsTest")

    /**
     * Returns the finding message, or null when the expression is allowed.
     *
     * An unrecognised path is treated as commonMain rather than as exempt. Failing
     * closed matters here: a path shape we do not recognise should cost an explicit
     * whitelist entry, not silently disable the ban.
     */
    fun violation(
        filePath: String,
        receiver: String?,
        selector: String?,
        limitedWrapper: Boolean,
    ): String? {
        val path = filePath.replace('\\', '/')

        if (PLATFORM_SOURCE_SETS.any { path.contains("/$it/") || path.endsWith("/$it") }) return null
        if (TEST_SOURCE_SETS.any { path.contains("/$it/") || path.endsWith("/$it") }) return null
        if (path.contains("/test/") || path.contains("/preview/")) return null

        if (receiver != "Dispatchers") return null
        if (selector == null || selector !in BANNED_DISPATCHERS) return null
        if (ALLOWED_FILES.any { path.contains(it) }) return null
        if (limitedWrapper) return null

        return "Direct `Dispatchers.$selector` is banned in commonMain. " +
            "Inject `CoroutineDispatcher` as a constructor parameter instead. " +
            "Platform source sets are out of scope — choosing the dispatcher is the port's job."
    }
}

/**
 * Registers [NoDirectDispatchersRule] in the `no-direct-dispatchers` rule set.
 */
class NoDirectDispatchersRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-direct-dispatchers")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoDirectDispatchers") to { cfg: Config ->
                NoDirectDispatchersRule(cfg)
            },
        ),
    )
}
