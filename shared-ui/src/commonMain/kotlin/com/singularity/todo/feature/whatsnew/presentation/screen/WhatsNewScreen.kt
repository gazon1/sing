package com.singularity.todo.feature.whatsnew.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.core.A2uiSeverity
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.GenuiSurface
import com.singularity.todo.feature.genui.render.LocalGenuiOnAction
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.rememberDataContext
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import com.singularity.todo.feature.whatsnew.presentation.WhatsNewPrefs
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * A bottom-sheet dialog that renders the whatsnew GenUI surface.
 *
 * Observes [RemoteConfigPort.observe]. When
 * [com.singularity.todo.core.config.RemoteConfigSnapshot.whatsNewPayload]
 * is non-null and differs from the last payload the user dismissed
 * (tracked by [WhatsNewPrefs]), parses it and renders the GenUI surface.
 *
 * Dismissing calls [onDismiss] and persists the payload hash so the same
 * payload does not re-appear on the next startup.
 *
 * @param onDismiss Called when the user dismisses the sheet.
 *   The caller typically does not need to do anything extra — persistence
 *   is handled internally via [WhatsNewPrefs].
 * @param modifier forwarded to the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remoteConfig: RemoteConfigPort = koinInject()
    val prefs: WhatsNewPrefs = koinInject()
    val scope = rememberCoroutineScope()

    var surfaceId by remember { mutableStateOf<SurfaceId?>(null) }
    var droppedLines by remember { mutableStateOf(0) }
    // Resolved rather than built here. This screen used to construct its own parser, controller and
    // registry, which meant the layer's own instances were the ones nothing used — and that two
    // surfaces in the app would not have shared a data model even if they had been related.
    val controller: SurfaceController = koinInject()
    val registry: ComponentRegistry = koinInject()
    val parser: A2uiParser = koinInject()
    val processor: A2uiMessageProcessor = koinInject()

    val snapshot by remoteConfig.observe().collectAsStateWithLifecycle()

    LaunchedEffect(snapshot.whatsNewPayload) {
        val payload = snapshot.whatsNewPayload
        if (payload.isNullOrBlank()) {
            surfaceId = null
            droppedLines = 0
            controller.reset()
            return@LaunchedEffect
        }

        if (!prefs.shouldShow(payload)) {
            surfaceId = null
            droppedLines = 0
            controller.reset()
            return@LaunchedEffect
        }

        val id = SurfaceId("whatsnew")
        droppedLines = playReleaseNotes(payload, id, parser, processor, controller)
        surfaceId = id
    }

    val currentSurfaceId = surfaceId ?: return

    val handleDismiss: () -> Unit = {
        scope.launch {
            snapshot.whatsNewPayload?.let { prefs.markShown(it) }
        }
        // Clearing the surface is what actually removes the sheet. `onDismiss` is the caller's
        // hook and both callers pass a no-op, so without this a payload's own "close" action
        // would record the dismissal and leave the sheet on screen — a dismissal that does not
        // dismiss. The `LaunchedEffect` above does not re-arm it: it re-runs on the payload, and
        // the payload has not changed.
        surfaceId = null
        droppedLines = 0
        onDismiss()
    }

    val ctx =
        rememberDataContext(
            surfaceId = currentSurfaceId,
            controller = controller,
            registry = registry,
            // WhatsNew surfaces are read-only, so there is no one to notify.
            onDataChange = null,
            clock = koinInject(),
        )

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    ModalBottomSheet(
        onDismissRequest = handleDismiss,
        sheetState = sheetState,
        // The sheet's own node, because a Surface merges its descendants and a tag placed below
        // it lives only in the unmerged tree — where neither accessibility nor Maestro looks.
        // The id is the catalogue's existing `genUi("whatsnew")` entry, which was declared and
        // never rendered: the surface had no tag, so a flow had nothing to wait for.
        //
        // The tag alone, with no content description. This node merges everything below it, and
        // a description on a merging container replaces the descriptions of its descendants:
        // the note and the button would leave the accessibility tree — and stop being the text
        // a flow selects them by.
        modifier = modifier.genuiTag("whatsnew"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            CompositionLocalProvider(
                LocalGenuiOnAction provides { _, action, _ ->
                    if (action == "dismiss" || action == "close") handleDismiss()
                },
            ) {
                GenuiSurface(ctx = ctx)
            }
            if (droppedLines > 0) {
                SelectableText(
                    text = "$droppedLines release note line(s) couldn't be rendered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Applies a release-note payload to a controller, and reports how many lines it refused.
 *
 * Retargeted onto [surfaceId] exactly as the chat session does. Without that the surface is filed
 * under whatever the payload happens to name, and this screen points at a surface that may not be
 * there — the same defect per-answer ownership fixed everywhere else.
 */
private fun playReleaseNotes(
    payload: String,
    surfaceId: SurfaceId,
    parser: A2uiParser,
    processor: A2uiMessageProcessor,
    controller: SurfaceController,
): Int {
    val outcomes: List<A2uiParseOutcome> =
        payload
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line: String -> parser.parseLine(line) }
            .toList()
    controller.reset()
    outcomes.forEach { outcome: A2uiParseOutcome ->
        if (outcome is A2uiParseOutcome.Parsed) {
            processor.apply(outcome.event.retargeted(surfaceId), outcome.errors)
        }
    }
    return outcomes.count { outcome: A2uiParseOutcome ->
        outcome is A2uiParseOutcome.Failed ||
            (outcome is A2uiParseOutcome.Parsed && outcome.errors.any { it.severity != A2uiSeverity.ADVISORY })
    }
}
