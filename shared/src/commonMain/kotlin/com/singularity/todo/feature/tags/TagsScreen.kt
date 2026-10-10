package com.singularity.todo.feature.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ContentStateMapper
import com.singularity.todo.core.ui.components.DeleteActionButton
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.core.ui.components.TaggedSnackbarHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tags.components.AddTagDialog
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase

/**
 * Tags list screen.
 *
 * Owns its own [Scaffold] with a [TaggedSnackbarHost] wired to [TagsViewModel]'s
 * undo-delete countdown, so the snackbar is owned by the screen that triggered
 * the delete rather than by a parent screen.
 *
 * @param viewModel The [TagsViewModel] scoped to this screen. Provides state,
 *   intent handling, and the undo-delete countdown progress.
 * @param modifier Standard Compose modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    viewModel: TagsViewModel,
    modifier: Modifier = Modifier,
    onOpenTag: (TagId) -> Unit = {},
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val countdownProgress by viewModel.countdownProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var tagBeingRenamed by remember { mutableStateOf<Tag?>(null) }

    // Show undo-delete snackbar and drive countdown via LaunchedEffect.
    LaunchedEffect(pendingDelete?.tagId) {
        val pending = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "\"${pending.title}\" deleted",
            actionLabel = "Undo",
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            viewModel.onIntent(TagsIntent.UndoDeleteTapped)
        }
    }

    Scaffold(
        snackbarHost = { TaggedSnackbarHost(snackbarHostState, countdownProgress = countdownProgress) },
        modifier = modifier,
    ) { paddingValues ->
        StatefulContent(
            state = state.toContentState(),
            emptyTitle = "No tags yet",
            modifier = Modifier.padding(paddingValues),
        ) { tags, contentModifier ->
            Box(modifier = contentModifier) {
                TagList(
                    tags = tags,
                    onDelete = { id -> viewModel.onIntent(TagsIntent.Delete(id)) },
                    onRename = { tag -> tagBeingRenamed = tag },
                    onOpen = onOpenTag,
                )
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .testTag(TestTags.TAGS_FAB)
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add tag")
                }
            }
        }
    }

    if (showAddDialog) {
        AddTagDialog(
            onConfirm = { name, color ->
                viewModel.onIntent(TagsIntent.Create(name, color))
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    tagBeingRenamed?.let { tag ->
        AddTagDialog(
            onConfirm = { name, color ->
                viewModel.onIntent(TagsIntent.Rename(tag.id, name, color))
                tagBeingRenamed = null
            },
            onDismiss = { tagBeingRenamed = null },
            title = "Rename Tag",
            initialName = tag.name,
            initialColor = tag.color,
            confirmLabel = "Save",
        )
    }
}

private fun TagsUiState.toContentState() = ContentStateMapper.tags(this)

@Composable
private fun TagList(
    tags: List<Tag>,
    onDelete: (TagId) -> Unit,
    onRename: (Tag) -> Unit,
    onOpen: (TagId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.testTag(TestTags.TAGS_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tags, key = { it.id.value }) { tag ->
            TagCard(
                tag = tag,
                onDelete = { onDelete(tag.id) },
                onRename = { onRename(tag) },
                onOpen = { onOpen(tag.id) },
            )
        }
    }
}

@Composable
fun TagCard(tag: Tag, onDelete: () -> Unit, onRename: () -> Unit, onOpen: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag(TestTags.tagOpen(tag.name)),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(tag.color)),
            )
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            IconButton(
                onClick = onRename,
                modifier = Modifier.testTag(TestTags.tagRename(tag.name)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Rename ${tag.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DeleteActionButton(onClick = onDelete)
        }
    }
}

// ===== Preview =====

@Preview
@Composable
private fun TagsScreenContentPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    val fakeTagsRepo = FakeTagsRepository()
    val fakeCreateTag = CreateTagUseCase(fakeTagsRepo, FakeClock())
    val fakeUpdateTag = UpdateTagUseCase(fakeTagsRepo, FakeClock())
    val vm = TagsViewModel(
        tagRepo = fakeTagsRepo,
        createTag = fakeCreateTag,
        updateTag = fakeUpdateTag,
        currentUser = FakeProfileAwareCurrentUser(),
    )
    TagsScreen(viewModel = vm)
}

@Preview
@Composable
private fun TagsScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    val fakeTagsRepo = FakeTagsRepository()
    val fakeCreateTag = CreateTagUseCase(fakeTagsRepo, FakeClock())
    val fakeUpdateTag = UpdateTagUseCase(fakeTagsRepo, FakeClock())
    val vm = TagsViewModel(
        tagRepo = fakeTagsRepo,
        createTag = fakeCreateTag,
        updateTag = fakeUpdateTag,
        currentUser = FakeProfileAwareCurrentUser(),
    )
    TagsScreen(viewModel = vm)
}

@Preview
@Composable
private fun TagsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    val fakeTagsRepo = FakeTagsRepository()
    val fakeCreateTag = CreateTagUseCase(fakeTagsRepo, FakeClock())
    val fakeUpdateTag = UpdateTagUseCase(fakeTagsRepo, FakeClock())
    val vm = TagsViewModel(
        tagRepo = fakeTagsRepo,
        createTag = fakeCreateTag,
        updateTag = fakeUpdateTag,
        currentUser = FakeProfileAwareCurrentUser(),
    )
    TagsScreen(viewModel = vm)
}
