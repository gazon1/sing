package com.singularity.todo.core.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Whether the tour should be on screen, and which step it is on.
 *
 * @property stepIndex null while the tour is not running.
 * @property bounds    root-space bounds of the current target, or null while it is not
 *                     laid out. The overlay draws nothing for a null, which is how
 *                     "wait for measurement" is expressed without a second flag.
 */
data class SpotlightTourState(val stepIndex: Int? = null, val bounds: Rect? = null) {
    val isRunning: Boolean get() = stepIndex != null
}

/**
 * Handle for the spotlight tour: advance it, dismiss it, or start it again.
 */
class SpotlightTourController internal constructor(
    val state: SpotlightTourState,
    /**
     * The registry the highlighted elements register into. Callers pass it to the
     * screen that hosts the targets; giving either side its own would leave the tour
     * waiting forever for a target registering somewhere else.
     */
    val registry: SpotlightAnchorRegistry,
    private val onAdvance: () -> Unit,
    private val onFinish: () -> Unit,
    private val onReplay: () -> Unit,
) {
    /** Next step, or finish when this was the last one. */
    fun next() = onAdvance()

    /** Dismiss the tour and record it as seen. */
    fun skip() = onFinish()

    /** Start it again on demand, from settings. */
    fun replay() = onReplay()
}

/**
 * Wires [SpotlightTourStateMachine] to composition and to the stored "have I seen this".
 *
 * The decisions are in the state machine; this feeds it observations and republishes its
 * answer after every change. [registry] must be the same instance the highlighted
 * elements register into, which is why it is created here by default rather than passed
 * in by every call site.
 *
 * @param settingsStore Injected with a default so the production graph supplies the
 *                      DataStore-backed one and a test can supply a fake.
 */
@Composable
fun rememberSpotlightTour(
    steps: List<SpotlightStep> = SpotlightContent.STEPS,
    contentVersion: Int = SpotlightContent.VERSION,
    registry: SpotlightAnchorRegistry = remember { SpotlightAnchorRegistry() },
    settingsStore: OnboardingSettingsRepository = koinInject(),
): SpotlightTourController {
    val machine = remember(steps) { SpotlightTourStateMachine(steps) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SpotlightTourState()) }

    // Republish from the machine whenever either the step or the target's bounds move.
    // Recomputing only on machine changes would freeze the hole in place while the
    // window resized, which is the one case where a stale overlay is most visible.
    LaunchedEffect(steps, machine) {
        snapshotFlow {
            Triple(machine.index, machine.ready, steps.map { registry[it.anchor] })
        }.collect { (_, _, _) ->
            val index = machine.index
            state = SpotlightTourState(
                stepIndex = index,
                bounds = index?.let { registry[steps[it].anchor] },
            )
        }
    }

    // The eligibility question depends on layout that has not happened on the first
    // frame, so it is answered by observation rather than by a one-shot read.
    LaunchedEffect(steps, contentVersion, registry) {
        val alreadySeen = settingsStore.seenSpotlightVersion.first() >= contentVersion
        if (alreadySeen) {
            // Nothing to show; do not leave a collector parked on the registry forever.
            machine.startIfEligible(alreadySeen = true, force = false, anchors = emptyMap())
            return@LaunchedEffect
        }
        snapshotFlow { steps.associate { it.anchor to registry[it.anchor] } }
            .collect { anchors ->
                machine.startIfEligible(alreadySeen = false, force = false, anchors = anchors)
            }
    }

    return SpotlightTourController(
        state = state,
        registry = registry,
        onAdvance = {
            scope.launch { machine.advance(steps.associate { it.anchor to registry[it.anchor] }) }
        },
        onFinish = {
            scope.launch {
                machine.finish()
                settingsStore.markSpotlightSeen(contentVersion)
            }
        },
        onReplay = {
            scope.launch {
                machine.startIfEligible(
                    alreadySeen = true,
                    force = true,
                    anchors = steps.associate { it.anchor to registry[it.anchor] },
                )
            }
        },
    )
}
