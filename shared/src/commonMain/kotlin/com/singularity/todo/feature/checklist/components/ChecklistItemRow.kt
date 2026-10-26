package com.singularity.todo.feature.checklist.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.celebration.Celebration
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * The test tags of one row of [ChecklistItemRow].
 *
 * The row serves two kinds of item — a checklist item and a subtask — and each kind
 * lives in its own namespace of tags. Handing the row three separate tag parameters
 * would push its signature past the house limit for eight parameters, so the three
 * travel together in one value, and the caller names the kind it is rendering.
 */
data class ChecklistRowTags(
    /** The checkbox that toggles the item. */
    val checkbox: String,
    /** The title of the item. */
    val item: String,
    /** The button that deletes the item. */
    val delete: String,
) {
    companion object {
        /** The tags of a checklist item row. */
        fun forChecklistItem(id: String, title: String) = ChecklistRowTags(
            checkbox = TestTags.checklistItemCheckbox(id = id, title = title),
            item = TestTags.checklistItem(id = id, title = title),
            delete = TestTags.checklistItemDelete(id = id, title = title),
        )

        /** The tags of a subtask row. */
        fun forSubtask(title: String) = ChecklistRowTags(
            checkbox = TestTags.subtaskCheckbox(title),
            item = TestTags.subtaskItem(title),
            delete = TestTags.subtaskDelete(title),
        )
    }
}

/**
 * The one row widget for an item that is ticked — a checklist item or a subtask.
 *
 * The task screen shows two lists of parts, and they used to be two lists of text
 * with a tick character in front of them, drawn by two near-identical private rows.
 * The user asked for the two lists to look the same and to behave the same, so the
 * row is one widget: a Material3 checkbox, a `bodyLarge` title, and a line through
 * the title once the item is finished.
 *
 * The row is deliberately decoupled from any feature package — it takes primitives
 * (`text`, `checked`), so a checklist item and a subtask can both be rendered by it
 * without either feature knowing about the other.
 *
 * @param text      The title of the item.
 * @param checked   Whether the item is completed (determines the strike-through).
 * @param onToggle  Called when the checkbox is clicked.
 * @param onDelete  Optional delete action. When null the delete button is hidden.
 * @param onOpen    Optional action that opens the item as a task of its own. A
 *   subtask is a task and keeps its own screen; a checklist item is not a task and
 *   offers nothing to open. When null the title toggles the item instead, which is
 *   what a checklist item's title has always done.
 * @param tags      The test tags of this row.
 * @param modifier  Standard Compose modifier.
 */
@Composable
fun ChecklistItemRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    tags: ChecklistRowTags = ChecklistRowTags.forChecklistItem(id = text, title = text),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Celebration(
            // Note: checklist items have no stable ID in the current API, so `text`
            // is used as triggerKey. Items with identical text within one task will
            // share a celebration signal — acceptable for this primitive type.
            triggerKey = if (checked) text else "",
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag(tags.checkbox),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
            color = if (checked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .weight(1f)
                .clickable { (onOpen ?: onToggle)() }
                .testTag(tags.item),
        )

        if (onDelete != null) {
            // The delete is a direct button and not an overflow menu: the menu used
            // to hold a second action — "convert to task" — and a menu of one item
            // is one tap more than the action is worth.
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag(tags.delete),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ===== Preview =====

@Preview
@Composable
private fun ChecklistItemRowLightPreview() = PreviewThemed(darkTheme = false) {
    ChecklistItemRow(
        text = "Buy groceries",
        checked = false,
        onToggle = {},
        onDelete = {},
    )
}

@Preview
@Composable
private fun ChecklistItemRowDoneDarkPreview() = PreviewThemed(darkTheme = true) {
    ChecklistItemRow(
        text = "Read documentation",
        checked = true,
        onToggle = {},
        onDelete = {},
    )
}
