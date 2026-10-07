package com.singularity.todo.feature.attachments.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.files.FileCategory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.MimeTypes
import kotlinx.coroutines.CancellationException
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.mikepenz.markdown.m3.Markdown

/**
 * The largest file the in-app text viewer will read: 4 MB.
 *
 * The check happens *before* any read, and that order is the whole point.
 * [FileSystem] returns whole files and nothing else — there is no streaming read on
 * this port — so a "read, then check the length" implementation has already put the
 * file on the heap by the time it decides the file was too large. The failure being
 * prevented is not a slow screen; it is an out-of-memory kill with nothing to show the
 * user.
 *
 * A file over the limit is not an error state. It goes out to the platform, which is
 * the correct answer for a large document, just not from inside this screen.
 */
const val MAX_IN_APP_TEXT_BYTES: Long = 4L * 1024 * 1024

/**
 * Extensions rendered as markdown rather than shown as source.
 *
 * Only these two. Every other text type the viewer accepts — `.json`, `.csv`, `.sql`,
 * `.kt` — is source code or data whose structure *is* its meaning, and formatting it as
 * markdown would silently destroy exactly what the user opened the file to see.
 */
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")

/** What the text viewer is doing right now. */
sealed interface TextViewerState {
    data object Loading : TextViewerState

    data class Loaded(val text: String) : TextViewerState

    /** No local copy: a remote-only attachment, or a path that no longer exists. */
    data object Unavailable : TextViewerState

    /** Over [MAX_IN_APP_TEXT_BYTES]. Nothing was read, and the caller may open externally. */
    data class TooLarge(val sizeBytes: Long) : TextViewerState

    data class Failed(val reason: String) : TextViewerState
}

/**
 * Loads a text attachment for the in-app viewer, enforcing the size limit before reading.
 *
 * Deliberately not a `@Composable`: the order — limit, then read — is the part worth
 * testing, and a pure suspend function is testable without a Compose runtime. Reversing
 * those two steps is the defect this file exists to prevent.
 */
suspend fun loadTextForViewer(fileSystem: FileSystem, path: String): TextViewerState {
    val stat = try {
        fileSystem.stat(path)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    // Two rejections share one return: a path that does not exist and a path that is a
    // directory are the same situation to a user — there is no file to read here.
    if (stat == null || stat.isDirectory) return TextViewerState.Unavailable
    if (stat.sizeBytes > MAX_IN_APP_TEXT_BYTES) {
        return TextViewerState.TooLarge(stat.sizeBytes)
    }
    val text = try {
        fileSystem.readBytes(path).decodeToString()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return TextViewerState.Failed("Could not read the file")
    }
    return TextViewerState.Loaded(text)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerScreen(
    title: String,
    path: String,
    fileSystem: FileSystem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenExternally: (() -> Unit)? = null,
    /**
     * Rendered over the loaded text — the annotation panel today.
     *
     * A slot rather than a dependency so the viewer stays a viewer: it reads a file and
     * shows it, and whatever the caller wants to hang off that text is the caller's
     * decision. `null` renders nothing, which is what a target without annotations wants.
     */
    overlay: (@Composable (documentText: String) -> Unit)? = null,
) {
    var state by remember(path) { mutableStateOf<TextViewerState>(TextViewerState.Loading) }
    // Derived from the file name, not the loaded state: it decides which renderer to
    // use the moment there is something to render.
    val isMarkdown = remember(path) {
        MimeTypes.classifyExtension(MimeTypes.extensionOf(path).orEmpty()) == FileCategory.Text &&
            MimeTypes.extensionOf(path) in MARKDOWN_EXTENSIONS
    }

    LaunchedEffect(path) { state = loadTextForViewer(fileSystem, path) }

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
        floatingActionButton = {
            // Only once there is text: an annotation is a span *of* the document, so with
            // nothing loaded there is nothing to span.
            val loaded = (state as? TextViewerState.Loaded)?.text
            if (loaded != null) overlay?.invoke(loaded)
        },
        modifier = modifier,
    ) { innerPadding ->
        BoxedMessage(
            state = state,
            innerPadding = innerPadding,
            isMarkdown = isMarkdown,
            onBack = onBack,
            onOpenExternally = onOpenExternally,
        )
    }
}

@Composable
private fun BoxedMessage(
    state: TextViewerState,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    isMarkdown: Boolean,
    onBack: () -> Unit,
    onOpenExternally: (() -> Unit)?,
) {
    when (val s = state) {
        TextViewerState.Loading -> Centered { CircularProgressIndicator() }

        is TextViewerState.Loaded -> SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                if (isMarkdown) {
                    // `.md` is rendered, not shown as source. A markdown file opened
                    // from a task attachment is a document the user intends to read.
                    Markdown(s.text)
                } else {
                    Text(text = s.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        TextViewerState.Unavailable -> Centered {
            ViewerMessage(
                title = "File unavailable",
                body = "This attachment has no local copy. It may not have been " +
                    "downloaded on this device.",
                actions = listOf(ViewerAction("Back", onBack)),
            )
        }

        is TextViewerState.TooLarge -> Centered {
            ViewerMessage(
                title = "Too large to show here",
                body = "This file is ${formatBytes(s.sizeBytes)}. The in-app viewer reads " +
                    "up to ${formatBytes(MAX_IN_APP_TEXT_BYTES)}, and nothing was read from it.",
                actions = buildList {
                    add(ViewerAction("Open in another app", onOpenExternally))
                    add(ViewerAction("Back", onBack))
                },
            )
        }

        is TextViewerState.Failed -> Centered {
            ViewerMessage(
                title = "Could not open",
                body = s.reason,
                actions = listOf(ViewerAction("Back", onBack)),
            )
        }
    }
}

private data class ViewerAction(val label: String, val onClick: (() -> Unit)?)

@Composable
private fun ViewerMessage(title: String, body: String, actions: List<ViewerAction>) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (action in actions) {
            // A null callback means "not available here" — the button is not rendered
            // rather than rendered dead.
            action.onClick?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Text(action.label)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TextViewerUnavailablePreview() = PreviewThemed {
    Centered {
        ViewerMessage(
            title = "File unavailable",
            body = "This attachment has no local copy.",
            actions = listOf(ViewerAction("Back", {})),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TextViewerTooLargePreview() = PreviewThemed(darkTheme = true) {
    Centered {
        ViewerMessage(
            title = "Too large to show here",
            body = "This file is 9 MB. The in-app viewer reads up to 4 MB, and nothing " +
                "was read from it.",
            actions = listOf(ViewerAction("Open in another app", {}), ViewerAction("Back", {})),
        )
    }
}
