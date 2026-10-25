package com.singularity.todo.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * A `@Composable` must not materialise a collection in its own body.
 *
 * ## Why
 *
 * The companion rule ([NoUnstableCollectionParamRule]) looks at the *type* of a
 * composable's parameter: a raw `List` is a finding, a persistent
 * `ImmutableList` is not. That is the right question to ask about the
 * parameter — and it says nothing about where the value came from.
 *
 * A value that is materialised in the composable's own body is rebuilt on
 * every recomposition of that composable:
 *
 * ```
 * TasksBarChart(buckets = snapshot.tasksPerDay.toImmutableList())
 * ```
 *
 * The parameter is typed `ImmutableList`, so the type-level rule is satisfied
 * and the child is skippable — but the list was rebuilt to hand it over. The
 * promise the contract makes ("the collection is rebuilt on every read and
 * never mutated afterwards") holds for the *value*, and says nothing about how
 * often a fresh one is allocated. This rule is about that second half: the
 * collection is allocated again and again where it should have been allocated
 * once.
 *
 * The place where a collection is built is the **state** that owns it, not the
 * place that hands it over. A collection rebuilt in the call of the composable
 * is rebuilt on every recomposition, and the promise of stability never starts.
 *
 * ## What it reports, and what it does not
 *
 * It reports a `toImmutableList()` / `toImmutableSet()` / `toImmutableMap()`
 * call inside a `@Composable` — unless the call sits in a lambda that runs on
 * its own schedule rather than on every recomposition:
 *
 * - `remember { … }`, `derivedStateOf { … }`, `LaunchedEffect { … }` and the
 *   other computation blocks run when their keys change, not on every
 *   recomposition. The value is allocated once, which is what the rule asks
 *   for;
 * - an event handler (`onClick = { … }`) runs on the event.
 *
 * A **content** lambda — `Scaffold { … }`, `Column { … }`, `items { … }` — is
 * *not* one of those. It runs inside the child's composition, and the child
 * recomposes whenever the parent does, so a collection built there is rebuilt
 * on every recomposition of the parent just like one built in the parent's own
 * body. The rule reports those, because they are where the rebuild hides.
 *
 * The rule reads the *name* of the argument a lambda is passed as, and the
 * *name* of the call the lambda belongs to; it does not resolve types. An
 * event handler is recognised by the `on…` convention this repository uses for
 * every callback it declares, and a computation block by the name of the
 * function it is passed to.
 *
 * A `@Preview` function is not reported either. A preview composes once; it
 * has no recomposition to waste.
 *
 * ## The way out
 *
 * Build the collection where it belongs:
 *
 * - in the **state** that owns it, when the state already holds the value (the
 *   boundary rule: the state holds a persistent collection, and the composable
 *   hands that instance over unchanged);
 * - in `remember { … }`, when the collection is derived from values the
 *   composable already has;
 * - in a top-level `val`, when the collection does not depend on anything.
 *
 * @see NoUnstableCollectionParamRule — the type-level half of the same promise.
 * @see 2026-10-22-compose-stability-contract
 */
class NoCollectionMaterializationInComposableRule(config: Config) : Rule(config, "", null) {

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isComposable()) return
        if (function.isPreview()) return

        val body = function.bodyExpression ?: return
        for (call in body.callsInTree()) {
            val factory = call.materialisedCollectionFactory()
            if (factory != null && !call.runsOnItsOwnSchedule()) {
                report(
                    Finding(
                        entity = Entity.from(call),
                        message = "A @Composable materialises a collection in its own body ($factory). " +
                            "The collection is rebuilt on every recomposition of this composable, so " +
                            "the promise of stability never starts. Build it where it belongs: in the " +
                            "state that owns it, in remember { }, or in a top-level val — and hand " +
                            "that instance over unchanged.",
                        references = emptyList(),
                        suppressReasons = emptyList(),
                    ),
                )
            }
        }
    }

    /**
     * Every call expression in the subtree, in source order.
     *
     * The walk is explicit rather than `collectDescendantsOfType`, and the
     * reason is the one the repository already knows: that helper is an inline
     * function, and the synthetic class the compiler generates for it fails to
     * load inside detekt's plugin classloader. The failure is silent — the
     * helper returns the calls of the *direct* subtree and misses the ones
     * inside a content lambda, so a rule written with it looks like a rule
     * that fires only where the value is built in the composable's own body.
     */
    private fun PsiElement.callsInTree(): List<KtCallExpression> {
        val found = mutableListOf<KtCallExpression>()
        collectCalls(found)
        return found
    }

    /** The explicit walk behind [callsInTree]. */
    private fun PsiElement.collectCalls(into: MutableList<KtCallExpression>) {
        for (child in children) {
            if (child is KtCallExpression) into.add(child)
            child.collectCalls(into)
        }
    }

    /**
     * The name of the collection factory the call invokes, or null when the call
     * is something else.
     */
    private fun KtCallExpression.materialisedCollectionFactory(): String? {
        val name = calleeExpression?.text ?: return null
        return if (name in FACTORIES) name else null
    }

    /**
     * True when the value is built on a schedule of its own — a computation
     * block or an event handler — rather than on every recomposition.
     *
     * Every enclosing lambda is asked: a `remember { … }` inside a content
     * lambda still runs once per key, and an event handler inside a content
     * lambda still runs on the event. The value is built when the *outermost*
     * schedule says so, so one exempt lambda is enough.
     */
    private fun PsiElement.runsOnItsOwnSchedule(): Boolean {
        var current: PsiElement? = parent
        while (current != null && current !is KtNamedFunction) {
            if (current is KtLambdaExpression && current.runsOnItsOwnSchedule()) return true
            current = current.parent
        }
        return false
    }

    /**
     * True when the lambda runs on a schedule of its own — a computation block
     * or an event handler.
     */
    private fun KtLambdaExpression.runsOnItsOwnSchedule(): Boolean {
        // A trailing lambda is the call's own lambda argument, and its parent is
        // the call itself; a lambda in the value list is wrapped in a
        // KtValueArgument, whose parent is the argument list.
        val argument = parent as? KtValueArgument
        val argumentName = argument?.getArgumentName()?.asName?.asString()
        if (argumentName != null && argumentName.startsWith(EVENT_PREFIX)) return true
        val call = when (val parent = this.parent) {
            is KtCallExpression -> parent

            // A trailing lambda sits in the call's lambda argument list, and
            // the argument list entry is the lambda's parent.
            is KtLambdaArgument -> parent.parent as? KtCallExpression

            // A lambda written in the value list is wrapped in a value
            // argument, whose parent is the argument list.
            is KtValueArgument -> parent.parent?.parent as? KtCallExpression

            else -> null
        } ?: return false
        val callee = call.calleeExpression?.text ?: return false
        return callee in COMPUTATION_CALLS
    }

    /** True when the function carries the Compose `@Composable` annotation. */
    private fun KtNamedFunction.isComposable(): Boolean =
        annotationEntries.any { entry ->
            entry.typeReference?.text?.substringAfterLast('.') == COMPOSABLE
        }

    /**
     * True when the function is a Compose `@Preview`.
     *
     * A preview composes once and never recomposes, so a collection built in
     * one is built once. The annotation is spelled fully qualified in most
     * files (`@androidx.compose.ui.tooling.preview.Preview`) and imported in
     * others, so the check reads the last segment of the name.
     */
    private fun KtNamedFunction.isPreview(): Boolean =
        annotationEntries.any { entry ->
            entry.typeReference?.text?.substringAfterLast('.') == PREVIEW
        }

    private companion object {
        const val COMPOSABLE = "Composable"
        const val PREVIEW = "Preview"

        /**
         * The factories that build a persistent collection.
         *
         * They are the three ways the persistent kinds are built in this
         * codebase; `persistentListOf()` and friends are excluded because they
         * are usually written as the *default value* of a state property, which
         * is the boundary, not a materialisation.
         */
        val FACTORIES = setOf("toImmutableList", "toImmutableSet", "toImmutableMap")

        /**
         * The prefix every callback parameter of this repository carries.
         *
         * A lambda passed to such a parameter is an event handler: it runs
         * when the event happens, not on every recomposition.
         */
        const val EVENT_PREFIX = "on"

        /**
         * The calls whose lambda runs on its own schedule.
         *
         * `remember` runs its block once per key; `derivedStateOf` recomputes
         * when the state it reads changes; the effect blocks run when their
         * keys change. A collection built there is not rebuilt on every
         * recomposition, and the rule has nothing to say about it.
         */
        val COMPUTATION_CALLS = setOf(
            "remember",
            "rememberSaveable",
            "derivedStateOf",
            "produceState",
            "snapshotFlow",
            "LaunchedEffect",
            "DisposableEffect",
            "SideEffect",
        )
    }
}

/**
 * Registers [NoCollectionMaterializationInComposableRule] in the
 * `no-collection-materialization-in-composable` rule set.
 */
class NoCollectionMaterializationInComposableProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("no-collection-materialization-in-composable")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        mapOf<RuleName, (Config) -> Rule>(
            RuleName("NoCollectionMaterializationInComposable") to { cfg: Config ->
                NoCollectionMaterializationInComposableRule(cfg)
            },
        ),
    )
}
