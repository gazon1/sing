package com.singularity.todo.feature.nav

import co.touchlab.kermit.Logger

private const val TAG = "NavShell"

/**
 * The blank-tree diagnostic (REQ-DN-004), recorded where the shell discovers that it has
 * nothing to render.
 *
 * A debug modifier cannot report this: the tree it would draw into is the one that is
 * empty. The route alone cannot report it either — the defect this exists for is one back
 * stack resolving to no entry while another stack is intact — so the line carries the
 * active route, the tab a back press would return to, and every top-level stack's route
 * keys in order. An empty stack is drawn as `[]`, which is the evidence a capture needs.
 * Stacks are sorted by route, so two captures of the same state compare line for line
 * instead of depending on map iteration order.
 *
 * Called from [Nav3State.toDecoratedEntries] before its unresolvable-entry fallback, so
 * the line describes the state that produced the blankness and not the reseeded state.
 *
 * @param state the navigation state whose entries could not be resolved.
 */
internal fun blankTreeReport(state: Nav3State): String {
    val stacks =
        state.topLevelRoutes
            .sortedBy { it.toString() }
            .joinToString(prefix = "[", postfix = "]") { route ->
                val keys = state.backStackFor(route)?.toList().orEmpty()
                "$route -> $keys"
            }
    return "WARNING [blank-screen] tree empty: active=${state.topLevelRoute}, " +
        "previous=${state.previousTopLevelRoute}, stacks=$stacks"
}

/**
 * Records a [blankTreeReport] line, so the message and the sink it lands in have one
 * owner.
 *
 * The report is built by the caller and not here: at the moment the tree is empty the
 * fallback is about to reseed the stacks, and a function that re-read them here would
 * describe the reseeded state instead of the one that produced the blank screen.
 *
 * @param report the line [blankTreeReport] produced.
 * @param warn where the line goes. Production passes nothing and gets the app log; a test
 *   passes a recorder, so the message can be asserted without a log backend.
 */
internal fun logBlankTree(
    report: String,
    warn: (String) -> Unit = { message -> Logger.withTag(TAG).w { message } },
) {
    warn(report)
}
