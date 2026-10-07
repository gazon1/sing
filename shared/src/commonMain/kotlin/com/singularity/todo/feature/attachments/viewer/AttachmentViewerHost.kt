package com.singularity.todo.feature.attachments.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentViewerRoute
import com.singularity.todo.core.attachments.ViewerTarget
import com.singularity.todo.core.files.FileOpener
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.OpenOutcome
import com.singularity.todo.feature.attachments.annotation.AttachmentAnnotationPanel
import kotlinx.coroutines.launch

/**
 * The single place an attachment is turned into a screen.
 *
 * Every caller goes through [AttachmentViewerRoute] and lands here, so the application
 * has one switch on [ViewerTarget] rather than one per call site — and it is a `when`
 * without an `else`, so adding a target later fails the build here instead of quietly
 * defaulting to the wrong screen.
 *
 * The external branch is where the design earns its keep. [OpenOutcome.NoHandler] is an
 * ordinary situation on a device with no app for the format, so the host keeps its own
 * state for it and shows a screen with a share action. Silently returning to the list
 * would look like the tap missed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentViewerHost(
    attachment: Attachment,
    fileSystem: FileSystem,
    fileOpener: FileOpener,
    fileSharePort: FileSharePort,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = AttachmentViewerRoute.routeFor(
        fileName = attachment.displayTitle,
        mimeType = attachment.mimeType,
    )
    val scope = rememberCoroutineScope()
    val localPath = attachment.localPath
    var noHandler by remember { mutableStateOf(false) }

    // Not a lambda-valued callback: the outcome has to come back here so the screen can
    // react to it. `onOpenExternally: suspend () -> Unit` would throw away the one bit
    // of information the caller needs.
    fun requestExternalOpen() {
        val path = localPath ?: return
        scope.launch {
            if (fileOpener.open(path, route.mimeType) == OpenOutcome.NoHandler) {
                noHandler = true
            }
        }
    }

    when {
        noHandler -> NoHandlerScreen(
            title = attachment.displayTitle,
            mimeType = route.mimeType,
            onBack = onBack,
            modifier = modifier,
            onShare = {
                localPath?.let { fileSharePort.shareFile(it, route.mimeType) }
            },
        )

        route.target == ViewerTarget.InAppImage -> ImageViewerScreen(
            title = attachment.displayTitle,
            imageModel = localPath ?: attachment.remoteUrl,
            onBack = onBack,
            modifier = modifier,
        )

        route.target == ViewerTarget.InAppText -> TextViewerScreen(
            title = attachment.displayTitle,
            path = localPath.orEmpty(),
            fileSystem = fileSystem,
            onBack = onBack,
            modifier = modifier,
            onOpenExternally = if (localPath != null) ::requestExternalOpen else null,
            // Annotations are reachable from the text viewer and nowhere else: they are a
            // span of this file's content, and the content only exists here.
            overlay = { documentText ->
                AttachmentAnnotationPanel(attachment.id, documentText)
            },
        )

        route.target == ViewerTarget.External -> ExternalOpenScreen(
            title = attachment.displayTitle,
            mimeType = route.mimeType,
            onBack = onBack,
            modifier = modifier,
            onOpenExternally = if (localPath != null) ::requestExternalOpen else null,
            onShare = {
                localPath?.let { fileSharePort.shareFile(it, route.mimeType) }
            },
        )
    }
}

/**
 * Shown when the platform has nothing that can open the file.
 *
 * The share sheet is the alternative the user actually has, so it is offered here
 * rather than only being available from a menu elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoHandlerScreen(
    title: String,
    mimeType: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        ViewerBody(innerPadding) {
            Text(
                text = "No installed app can open $mimeType.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "You can pass it to another app instead.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onShare?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Text("Share")
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

/** The screen for a format the app cannot render but the platform may still be able to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExternalOpenScreen(
    title: String,
    mimeType: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenExternally: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        ViewerBody(innerPadding) {
            Text(
                text = "Opening $mimeType in another app.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            onOpenExternally?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Text("Open again")
                }
            }
            onShare?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Text("Share")
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun ViewerBody(
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        content = content,
    )
}
