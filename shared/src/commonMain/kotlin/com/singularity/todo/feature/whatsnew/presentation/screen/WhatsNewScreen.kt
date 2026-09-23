package com.singularity.todo.feature.whatsnew.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.flow.Flow
import org.koin.compose.koinInject

/**
 * A bottom-sheet dialog that renders the whatsnew GenUI surface.
 *
 * Observes [RemoteConfigPort.observe]. When
 * [com.singularity.todo.core.config.RemoteConfigSnapshot.whatsNewPayload]
 * is non-null, parses it and renders the GenUI surface.
 *
 * The sheet is shown only when a payload is available.
 * Dismissing the sheet re-evaluates on the next config change.
 *
 * @param onDismiss Called when the user dismisses the sheet.
 *   The caller should store a "whatsnew shown" flag in DataStore so it
 *   doesn't re-appear on every startup (caller's responsibility).
 * @param modifier forwarded to the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remoteConfig: RemoteConfigPort = koinInject()

    var surfaceId by remember { mutableStateOf<SurfaceId?>(null) }
    val controller = remember { SurfaceController() }
    val parser = remember { A2uiParser() }
    val registry = remember { ComponentRegistry().also { Material3Catalog.install(it) } }

    val snapshot by remoteConfig.observe().collectAsStateWithLifecycle()

    LaunchedEffect(snapshot.whatsNewPayload) {
        val payload = snapshot.whatsNewPayload
        if (payload.isNullOrBlank()) {
            surfaceId = null
            controller.reset()
            return@LaunchedEffect
        }

        val id = SurfaceId("whatsnew")
        controller.reset()
        payload.lineSequence()
            .mapNotNull { parser.parseLine(it) }
            .forEach { controller.apply(it) }
        surfaceId = id
    }

    val currentSurfaceId = surfaceId ?: return

    val ctx = rememberDataContext(
        surfaceId = currentSurfaceId,
        controller = controller,
        registry = registry,
        onAction = { _, action, _ ->
            if (action == "dismiss" || action == "close") {
                onDismiss()
            }
        },
        onDataChange = { _, _, _ ->
            // WhatsNew surfaces are read-only.
        },
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            GenuiRenderer(
                surfaceId = currentSurfaceId,
                ctx = ctx,
            )
        }
    }
}
