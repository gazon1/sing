package com.singularity.todo.feature.tags.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.CreateTagInput
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.TagDomain
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import org.koin.compose.koinInject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/**
 * The ids the picker's own controls carry.
 *
 * They live beside the sheet rather than in `TestTags` because the picker is a
 * sheet: on desktop a sheet opens its own window, so these controls are
 * addressed from the JVM tier and not from a device flow.
 */
object TagsPickerTestTags {

    /** The action that opens the create dialog. */
    const val CREATE = "tags_picker_create"

    /** The rule stated next to the create action. */
    const val CREATE_HINT = "tags_picker_create_hint"

    /** The message the last create attempt produced. */
    const val CREATE_MESSAGE = "tags_picker_create_message"

    /** The button that hands the selection to the task. */
    const val DONE = "tags_picker_done"

    /** The button that closes the sheet without handing over the selection. */
    const val CANCEL = "tags_picker_cancel"

    /** The row of the tag called [name]. */
    fun row(name: String): String = "tags_picker_row:$name"
}

/**
 * The place where the tags of a task are chosen.
 *
 * A tag that does not exist yet is created from here: the sheet owns the
 * selection and reports it once, on "Done", so adding the new id to that
 * selection is what applies the tag to the task — the sheet host, the
 * coordinator and the intent path stay exactly as they are.
 *
 * The name is asked for in [AddTagDialog], the dialog the tags screen already
 * uses, so there is one place where a tag is named and coloured rather than
 * two that can drift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionSignature")
@Composable
fun TagsPickerSheet(
    selectedIds: ImmutableList<TagId>,
    onSelect: (ImmutableList<TagId>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tagsRepo: TagsRepository = koinInject()
    val createTag: CreateTagUseCase = koinInject()
    val currentUser: ProfileAwareCurrentUser = koinInject()
    val scope = rememberCoroutineScope()
    val allTags by tagsRepo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    val selected = remember(selectedIds) { mutableStateListOf<TagId>().apply { addAll(selectedIds) } }

    var showCreateDialog by remember { mutableStateOf(false) }
    var createMessage by remember { mutableStateOf<String?>(null) }
    var createFailed by remember { mutableStateOf(false) }

    fun select(id: TagId) {
        if (!selected.contains(id)) selected.add(id)
    }

    /**
     * Creates the tag the user named, or selects the one that already exists.
     *
     * The duplicate rule lives in [TagDomain.findByName] because nothing else
     * in the app enforces it: there is no unique index on `tags.name` and the
     * repository creates whatever it is handed. A name that differs from an
     * existing one only in case is the same label to a person, so the existing
     * tag is selected and the user is told which one was used.
     */
    fun submitCreate(name: String, color: Int) {
        scope.launch {
            when (val outcome = attemptCreate(createTag, currentUser, allTags, name, color)) {
                is CreateOutcome.Created -> {
                    select(outcome.id)
                    createMessage = null
                    createFailed = false
                }

                is CreateOutcome.Existing -> {
                    select(outcome.tag.id)
                    createMessage = "Tag \"${outcome.tag.name}\" already exists — the existing tag was selected."
                    createFailed = false
                }

                is CreateOutcome.Refused -> {
                    createMessage = outcome.message
                    createFailed = true
                }
            }
        }
    }

    TaskEditorSheetHost(
        title = "Tags",
        onClose = onDismiss,
    ) {
        TagChoices(
            allTags = remember(allTags) { allTags.toImmutableList() },
            selected = remember(selected.toList()) { selected.toImmutableList() },
            onToggle = { tag ->
                if (selected.contains(tag.id)) {
                    selected.remove(tag.id)
                } else {
                    selected.add(tag.id)
                }
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        CreateTagAction(
            onCreate = {
                createMessage = null
                showCreateDialog = true
            },
            message = createMessage,
            messageIsFailure = createFailed,
        )

        PickerButtons(
            onDone = { onSelect(selected.toImmutableList()) },
            onCancel = onDismiss,
        )
    }

    if (showCreateDialog) {
        AddTagDialog(
            onConfirm = { name, color ->
                showCreateDialog = false
                submitCreate(name, color)
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

/**
 * The wording used when a failure carried no message of its own.
 *
 * An empty banner tells the user that nothing happened; this says what did
 * not happen, in the same language as the rest of the sheet.
 */
private const val TAG_CREATE_FAILED = "Could not create tag"

/**
 * The wording used when the name was refused without a reason of its own.
 */
private const val TAG_NAME_INVALID = "Invalid tag name"

/**
 * What a create attempt produced.
 *
 * The three outcomes are kept apart because the user is told a different thing
 * about each: a tag was made, an existing tag was used instead, or the name was
 * refused — and the last one carries the reason.
 */
private sealed interface CreateOutcome {

    /** The tag was created. */
    data class Created(val id: TagId) : CreateOutcome

    /** A tag with that name already existed and was selected instead. */
    data class Existing(val tag: Tag) : CreateOutcome

    /** The name was refused; [message] says why. */
    data class Refused(val message: String) : CreateOutcome
}

/**
 * Creates the tag the user named — or reports why it was not created.
 *
 * The duplicate rule lives in [TagDomain.findByName] because nothing else in
 * the app enforces it: there is no unique index on `tags.name` and the
 * repository creates whatever it is handed. A name that differs from an
 * existing one only in case is the same label to a person, so the existing tag
 * is the one that is used.
 */
private suspend fun attemptCreate(
    createTag: CreateTagUseCase,
    currentUser: ProfileAwareCurrentUser,
    allTags: List<Tag>,
    name: String,
    color: Int,
): CreateOutcome {
    val trimmed = name.trim()
    TagDomain.validateName(trimmed)?.let { return CreateOutcome.Refused(it.message ?: TAG_NAME_INVALID) }
    TagDomain.findByName(allTags, trimmed)?.let { return CreateOutcome.Existing(it) }
    val input = CreateTagInput(
        name = trimmed,
        color = color,
        userId = currentUser.scopedUserId.value,
    )
    return createTag(input).fold(
        onSuccess = { CreateOutcome.Created(it) },
        // The domain's own wording wins: it is the reason the name was
        // refused, and a generic fallback would hide which rule it was.
        onFailure = { CreateOutcome.Refused(it.toAppError().message ?: TAG_CREATE_FAILED) },
    )
}

/**
 * The two ways out of the sheet.
 *
 * "Done" is what hands the selection to the task; "Cancel" leaves the task as
 * it was. They are the sheet's own buttons rather than the host's, because the
 * host's ✓ commits a *pending* choice and this sheet has none.
 */
@Composable
private fun PickerButtons(onDone: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier.testTag(TagsPickerTestTags.CANCEL),
        ) {
            Text("Cancel")
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
            onClick = onDone,
            modifier = Modifier.testTag(TagsPickerTestTags.DONE),
        ) {
            Text("Done")
        }
    }
}

/**
 * The tags on offer, or the statement that there are none.
 *
 * The empty state does not replace the list — it stands in front of it, so the
 * create action below stays reachable when there is nothing to choose yet.
 */
@Composable
private fun TagChoices(
    allTags: ImmutableList<Tag>,
    selected: ImmutableList<TagId>,
    onToggle: (Tag) -> Unit,
) {
    if (allTags.isEmpty()) {
        SelectableText(
            text = "No tags yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag(TestTags.TAGS_PICKER_EMPTY)
                .padding(16.dp),
        )
    } else {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(allTags, key = { it.id.value }) { tag ->
                TagItem(
                    tag = tag,
                    isSelected = selected.contains(tag.id),
                    onToggle = { onToggle(tag) },
                )
            }
        }
    }
}

/**
 * The create affordance and what it has to say.
 *
 * The rule is stated here rather than inside [AddTagDialog] because that
 * dialog refuses a blank name by disabling its confirm button — and a control
 * that is simply dead tells the user nothing about why. The wording is the
 * domain's own, so the sheet cannot state a rule the domain does not enforce.
 */
@Composable
private fun CreateTagAction(
    onCreate: () -> Unit,
    message: String?,
    messageIsFailure: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        TextButton(
            onClick = onCreate,
            modifier = Modifier.testTag(TagsPickerTestTags.CREATE),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("New tag")
        }

        Text(
            text = TagDomain.BLANK_NAME_MESSAGE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TagsPickerTestTags.CREATE_HINT),
        )

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (messageIsFailure) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .testTag(TagsPickerTestTags.CREATE_MESSAGE)
                    .padding(top = 4.dp),
            )
        }
    }
}

@Suppress("FunctionSignature")
@Composable
private fun TagItem(tag: Tag, isSelected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .testTag(TagsPickerTestTags.row(tag.name))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = tag.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (isSelected) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
