package com.singularity.todo.feature.whatsnew.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.GenuiRenderer
import com.singularity.todo.feature.genui.render.material3.Material3Catalog
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
fun WhatsNewScreen(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val remoteConfig: RemoteConfigPort = koinInject()
    val prefs: WhatsNewPrefs = koinInject()
    val scope = rememberCoroutineScope()

    var surfaceId by remember { mutableStateOf<SurfaceId?>(null) }
    var droppedLines by remember { mutableStateOf(0) }
    val controller = remember { SurfaceController() }
    val parser = remember { A2uiParser() }
    val registry = remember { ComponentRegistry().also { Material3Catalog.install(it) } }

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

        val lines = payload.lineSequence().filter { it.isNotBlank() }.toList()
        val parsed = lines.mapNotNull { parser.parseLine(it) }
        droppedLines = lines.size - parsed.size

        val id = SurfaceId("whatsnew")
        controller.reset()
        parsed.forEach { controller.apply(it) }
        surfaceId = id
    }

    val currentSurfaceId = surfaceId ?: return

    val handleDismiss: () -> Unit = {
        scope.launch {
            snapshot.whatsNewPayload?.let { prefs.markShown(it) }
        }
        onDismiss()
    }

    val ctx = rememberDataContext(
        surfaceId = currentSurfaceId,
        controller = controller,
        registry = registry,
        onAction = { _, action, _ ->
            if (action == "dismiss" || action == "close") handleDismiss()
        },
        onDataChange = { _, _, _ ->
            // WhatsNew surfaces are read-only.
        },
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = handleDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            GenuiRenderer(
                surfaceId = currentSurfaceId,
                ctx = ctx,
            )
            if (droppedLines > 0) {
                Text(
                    text = "$droppedLines release note line(s) couldn't be rendered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
