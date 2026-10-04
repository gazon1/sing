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
 * Bans `kotlinx.coroutines.delay(N)` above 500 ms and `Thread.sleep(N)` in test sources.
 *
 * Real delays block the test thread and prevent virtual-time testing. Use
 * `advanceUntilIdle()`, `advanceTimeBy()`, or `runCurrent()` from
 * `kotlinx.coroutines.test` instead of `delay()`. For Compose UI tests,
 * use `waitForIdle()` from `androidx.compose.ui.test`.
 *
 * For `Thread.sleep` in instrumented (emulator) tests, there is no equivalent
 * virtual-time substitute — the only option is to reduce the delay or restructure
 * the test. When `Thread.sleep` appears in an instrumented test it should be
 * suppressed with `@Suppress("ThreadSleepInTest")` and a TODO comment referencing this rule.
 *
 * Exemptions for `delay()`:
 * - `delay(0)` and `delay(1)` — effectively no-ops, no virtual-time needed
 * - `delay <= 500` — allowed as a practical workaround for VMs that use
 *   `stateIn(WhileSubscribed(5000))`: the 5-second subscription delay cannot
 *   be bypassed via `advanceUntilIdle()` because TestScheduler does not
 *   advance real time. These delays should be replaced with proper VM restructuring
 *   (moving the subscriber activation into the test setup) as a follow-up.
 *
 * Exemptions for `Thread.sleep`:
 * - `Thread.sleep(0)` — yield to the scheduler, not a real delay
 * - `@Suppress("DEPRECATION")` — intentional real-time waits in instrumented tests
 *   (no virtual-time alternative exists on an emulator)
 *
 * ## What this rule cannot see
 *
 * The rule works on PSI and does no type resolution, so it **cannot tell whether
 * the enclosing coroutine runs under `runTest`'s virtual clock**. A
 * `delay(1_000)` inside a `runTest { }` body advances virtual time and costs
 * nothing, yet is indistinguishable here from a real 1-second stall in a
 * production code path. Legitimate virtual-time sites — for example a test that
 * deliberately parks a coroutine so the diagnostics dump can observe it
 * suspended — must therefore carry `@Suppress("NoRealDelayInTest")` with a
 * one-line reason. That is the intended escape hatch: a human decides, and the
 * reason shows up in review.
 *
 * @see NoRealDelayInTestRuleProvider for registration.
 */
class NoRealDelayInTestRule(config: Config) : Rule(config, "", null) {

    private companion object {
        /**
         * Delays at or under this are tolerated: a short `delay` in a Robolectric or
         * retry path is not the real-time flakiness this rule exists to catch, and
         * banning every millisecond would make the rule unusable.
         */
        private const val BAN_LIST_REF =
            "See AGENTS.md ban list + singularity-todo-test-flaky-prevention skill."

        /**
         * Delays at or below this are tolerated: a short `delay` in a Robolectric or
         * retry path is not the real-time flakiness this rule exists to catch, and
         * banning every millisecond would make the rule unusable.
         *
         * Kept as a named constant because a bare `500` inline is exactly how this
         * rule came to look like it had a working threshold while never being tested
         * against one. (`MAX_TOLERATED_DELAY_MS` was the same value under a second
         * name on the other side of this merge; two constants of 500 in one class is
         * a threshold nobody can tell apart.)
         */
        private const val DELAY_THRESHOLD_MS = 500L
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        // `delay(...)` is an unqualified call, so the call visitor is the only
        // place it can be seen.
        checkDelayCall(expression)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val call = expression.selectorExpression as? KtCallExpression ?: return
        // `Thread.sleep(...)` is qualified, so only the dot-qualified visitor sees
        // it as such. Thread.sleep is deliberately NOT also handled in
        // visitCallExpression: the inner `sleep(...)` call is visited on its own,
        // and reporting from both paths double-counted every occurrence.
        val receiver = expression.receiverExpression as? KtNameReferenceExpression
        if (receiver?.text == "Thread") {
            checkThreadSleep(call)
        }
    }

    private fun checkDelayCall(expression: KtCallExpression) {
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "delay") return

        val argument = expression.valueArguments.firstOrNull() ?: return
        val valueText = argument.getArgumentExpression()?.text ?: return
        val value = parseLongLiteral(valueText) ?: return
        if (value <= DELAY_THRESHOLD_MS) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "delay($valueText) is a real-time block in tests. " +
                    "Use advanceUntilIdle(), advanceTimeBy($valueText), or runCurrent() " +
                    "from kotlinx.coroutines.test instead. If this is already under " +
                    "runTest's virtual clock, suppress with @Suppress(\"NoRealDelayInTest\") " +
                    "and a reason. $BAN_LIST_REF",
                references = emptyList(),
                suppressReasons = listOf("NoRealDelayInTest"),
            ),
        )
    }

    /**
     * Parses a Kotlin integer literal, tolerating the spellings that `toLongOrNull`
     * rejects: digit-group underscores (`1_000`) and the `L` suffix (`1000L`).
     *
     * This matters because the un-suffixed form is not how delays are usually
     * written — `delay(1_000)` and `delay(1000L)` both slipped past the old
     * `toLongOrNull()` call, which is a silent pass-through for exactly the sites
     * the rule exists to catch. Returns null for anything non-literal (a variable,
     * a computed value), which stays unreported rather than guessed at.
     */
    private fun parseLongLiteral(text: String): Long? {
        val cleaned = text.replace("_", "").removeSuffix("L").removeSuffix("l")
        if (cleaned.isEmpty() || !cleaned.all { it.isDigit() }) return null
        return cleaned.toLongOrNull()
    }

    private fun checkThreadSleep(expression: KtCallExpression) {
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.text != "sleep") return

        val argument = expression.valueArguments.firstOrNull() ?: return
        val valueText = argument.getArgumentExpression()?.text ?: return
        val value = parseLongLiteral(valueText) ?: return
        if (value == 0L) return

        // Thread.sleep in instrumented (emulator) tests has no virtual-time equivalent.
        // Allow it with @Suppress("ThreadSleepInTest") on the test function — detekt
        // will suppress this finding when the annotation is present.
        report(
            Finding(
                entity = Entity.from(expression),
                message = "Thread.sleep($valueText) is a real-time block in tests. " +
                    "In Compose UI tests use composeTestRule.waitForIdle(). " +
                    "In instrumented (emulator) tests where no alternative exists, " +
                    "suppress with @Suppress(\"ThreadSleepInTest\") on the test function. " +
                    "$BAN_LIST_REF",
                references = emptyList(),
                suppressReasons = listOf("ThreadSleepInTest"),
            ),
        )
    }
}

/**
 * Registers [NoRealDelayInTestRule] in the `no-real-delay-in-test` rule set.
 */
class NoRealDelayInTestRuleProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-real-delay-in-test")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoRealDelayInTest") to { cfg: Config -> NoRealDelayInTestRule(cfg) },
        ),
    )
}
