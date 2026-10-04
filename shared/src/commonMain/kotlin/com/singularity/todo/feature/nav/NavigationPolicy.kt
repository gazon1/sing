package com.singularity.todo.feature.nav

/**
 * The single decision every "open this screen" request resolves to (REQ-NAV-001).
 *
 * Consumers switch over this sealed hierarchy exhaustively — a new action variant is a
 * compile error at every call site, not a silent default.
 */
sealed interface OpenAction {
    /** Activate the target's top-level destination (bottom-bar tab or menu entry). */
    data object SwitchTab : OpenAction

    /**
     * Place the target on the current back stack without leaving the current feature —
     * same [ScreenFamily] as the source context.
     */
    data object Push : OpenAction

    /**
     * Open the target feature's graph on the current back stack — cross-feature.
     *
     * In this app both [Push] and [ExitAndOpen] execute the same stack mutation (the
     * origin stays underneath, REQ-NAV-001 "keeps the origin underneath"); the variants
     * record *which rule* fired so the policy table stays auditable.
     */
    data object ExitAndOpen : OpenAction
}

/**
 * The open-decision policy: one pure function instead of per-origin allow-lists
 * duplicated across the two platform entry providers.
 *
 * Rules, in order (ADR `2026-10-04-navigation-policy`):
 *
 * 1. a bare nested start route (or any other non-app-level key) addressed as an app-level
 *    target → descriptive error naming source and target; the stack is untouched because
 *    the failure happens before any mutation (REQ-NAV-003);
 * 2. top-level destination → [OpenAction.SwitchTab];
 * 3. same [ScreenFamily] → [OpenAction.Push];
 * 4. otherwise → [OpenAction.ExitAndOpen].
 *
 * Pure commonMain — no Compose, no state; platform parity by construction (REQ-NAV-007).
 */
object NavigationPolicy {

    /** The 13 top-level destinations: bottom-bar tabs + menu entries. */
    private val topLevelDestinations: Set<AppDestination> =
        DestinationKind.tabs.toSet() + DestinationKind.menuEntries.toSet()

    fun resolve(from: AppNavKey, to: AppNavKey): OpenAction = when {
        to !is AppDestination -> throw IllegalArgumentException(
            "NavigationPolicy: cannot open $to from $from — " +
                "a bare nested start route has no app-level entry. " +
                "Address it through its graph wrapper, e.g. TasksGraph($to).",
        )

        to in topLevelDestinations -> OpenAction.SwitchTab

        familyOf(from) == familyOf(to) -> OpenAction.Push

        else -> OpenAction.ExitAndOpen
    }
}
