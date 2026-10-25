package com.singularity.todo.detekt

/**
 * The composables that still take a raw collection, and why.
 *
 * This is a register of debt, not an allowance. Every entry names a
 * `@Composable` whose parameter is a raw `List` / `Set` / `Map`, so the Compose
 * compiler compares that parameter by identity and the composable recomposes
 * whenever its parent does.
 *
 * ## The register is empty
 *
 * The debt it named is paid. Every `@Composable` in the repository takes its
 * collections from the persistent kind (`ImmutableList` / `ImmutableSet` /
 * `ImmutableMap`), and the value is built at the boundary — the state that owns
 * it — rather than in the place that hands it over. The register stays as an
 * empty map because the rule reads it: an empty register is what makes every
 * *new* raw collection parameter a finding, and a rule with nothing to look up
 * is a rule that refuses everything.
 *
 * ## Why it was a register and not a rule from the start
 *
 * The alternative was to convert all of them at once. Each conversion is a
 * signature change plus every call site, and each one changes what the
 * compiler compares: a *stable* parameter is compared by `equals`, which over a
 * collection is a deep walk. That is the right trade for a leaf — one task's
 * tags — and the wrong one for a screen that holds a month of entries. So the
 * debt was named first, and paid afterwards, one area at a time, with a
 * measurement after each payment.
 *
 * ## How it shrinks
 *
 * An entry disappears when the composable's parameter becomes persistent
 * (`ImmutableList` / `ImmutableSet` / `ImmutableMap`) and the value is built at
 * the boundary. The measurement to check the result is the Compose compiler's
 * own report:
 *
 * ```
 * ./gw :shared:compileKotlinJvm -PcomposeStabilityReport=true
 * ```
 *
 * The register was measured on 2026-10-24, after the stability contract and
 * the conversion of the hot states; and again after the menu, the proposals and
 * the logbook took their collections from the persistent kind; and again after
 * each of the areas that followed — the project screens, the matched ranges,
 * the task editor and the task filters, the shared sheets and dialogs, the
 * notes and the tags, the calendar and the agenda, the chat and the
 * attachments, the calendar sync, the checklist, the pomodoro and the
 * profiles, the search, the settings and the statistics, and finally the
 * onboarding tour, the navigation and the test bridge.
 *
 * ## The key is the parameter, not the composable
 *
 * An entry names a composable and, under it, **every** raw collection
 * parameter that composable still declares, with the type each one declares.
 * The key is therefore the pair (parameter, type) under a qualified name —
 * not the qualified name alone.
 *
 * The difference is not cosmetic. A key that names only the composable exempts
 * the whole function: a *new* raw collection parameter added to a known
 * composable is then invisible, and the register cannot tell the difference
 * between "this site is known" and "this site is known, and this is the
 * parameter that is known". Keying by the parameter makes the second
 * question answerable, and it makes the register checkable: a parameter that
 * was retyped leaves an entry that names a type the code no longer declares,
 * and [NoUnstableCollectionParamRule] reports it as stale.
 *
 * A parameter that was **renamed** or **removed** is a different case, and the
 * rule does not see it: a parameter that is gone has no declared type to
 * compare, and a composable may have overloads, so "this parameter is missing
 * here" is not the same as "this parameter is gone". The gate
 * (`scripts/check-compose-stability-config.py`) reads the sources of the whole
 * repository and answers that question there.
 *
 * @see NoUnstableCollectionParamRule
 * @see 2026-10-22-compose-stability-contract
 */
internal object ComposeCollectionExemptions {

    /**
     * The known sites: a composable's qualified name, and under it the raw
     * collection parameters it still declares, with the type of each.
     *
     * The qualified name is the one the Compose compiler prints in its
     * stability report — the classes a function is nested in come first, the
     * function last — so a name can be copied from the report into this
     * register without a translation table.
     *
     * The map is empty: the debt is paid. It keeps its shape so that the next
     * raw collection parameter has somewhere to be written down — and so that
     * the rule, which reads this map, reports it.
     */
    val KNOWN: Map<String, Map<String, String>> = mapOf()

    /**
     * The raw collection parameters [qualifiedName] is known to carry, and the
     * type the register records for each.
     *
     * Empty when the composable is not a known site — which says nothing about
     * its parameters: a composable that is not in the register has no exempted
     * parameter at all.
     */
    fun parametersOf(qualifiedName: String): Map<String, String> =
        KNOWN[qualifiedName] ?: emptyMap()
}
