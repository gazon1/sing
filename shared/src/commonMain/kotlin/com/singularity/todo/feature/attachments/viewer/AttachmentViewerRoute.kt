package com.singularity.todo.feature.attachments.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.files.FileOpener
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.files.FileSystem
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/**
 * Whether the id has resolved yet, and to what.
 *
 * Separate from "resolved to null" so a deleted attachment does not spin forever.
 */
private sealed interface ViewerEntry {
    /** Before the first emission: there is nothing to say yet, so nothing is said. */
    data object Loading : ViewerEntry

    /** The flow emitted. [attachment] is null when it no longer exists. */
    data class Resolved(val attachment: Attachment?) : ViewerEntry
}

/**
 * The navigation entry for one attachment.
 *
 * Its whole job is to resolve the id to an attachment and hand it to
 * [AttachmentViewerHost]. Routing belongs to `AttachmentViewerRoute` and rendering to
 * the host; keeping them apart is why neither has to know about navigation.
 *
 * The attachment is observed rather than fetched once: it can be deleted on another
 * screen, or its local copy can arrive after a sync, and a viewer holding a stale copy
 * would show a file that is no longer there. That is why "resolved to null" is its own
 * state below rather than being folded into loading — a deleted attachment must not
 * spin forever, and a spinner that outlives the file it was waiting for is worse than
 * an explanation.
 */
@Composable
fun AttachmentViewerRoute(
    attachmentId: AttachmentId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository: AttachmentRepository = koinInject()
    val fileSystem: FileSystem = koinInject()
    val fileOpener: FileOpener = koinInject()
    val fileSharePort: FileSharePort = koinInject()

    val entry by remember(attachmentId) {
        repository.observe(attachmentId).map { ViewerEntry.Resolved(it) }
    }.collectAsState(initial = ViewerEntry.Loading)

    when (val e = entry) {
        ViewerEntry.Loading -> CenteredSpinner(modifier)

        is ViewerEntry.Resolved -> {
            val attachment = e.attachment
            if (attachment == null) {
                MissingAttachmentScreen(onBack, modifier)
            } else {
                AttachmentViewerHost(
                    attachment = attachment,
                    fileSystem = fileSystem,
                    fileOpener = fileOpener,
                    fileSharePort = fileSharePort,
                    onBack = onBack,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun MissingAttachmentScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "This attachment no longer exists.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "It may have been deleted from the task.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun CenteredSpinner(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator() }
}
