package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds

/** One row in a [MultiSelectSheet]. */
data class MultiSelectItem<T>(
    val key: T,
    val label: String,
    val selected: Boolean = false,
    val testTag: String? = null,
)

/**
 * A bottom sheet that lets the user choose **several** values, then confirm.
 *
 * ## Why this exists and is not a [ListPickerSheet]
 *
 * `ListPickerSheet` is single-select by contract: its row handler calls
 * `onDismiss()` immediately after `onItemSelected`, because picking the one row
 * you wanted is the end of the interaction. A configurator that needs a *set* —
 * "this section is any of these four tags" — cannot use it, because the sheet
 * closes on the first click and the selection can never grow past one.
 *
 * The first implementation of the agenda selector configurator did try to reuse
 * it, with a confirm button in the footer. It looked right and behaved like a
 * single-select picker: choosing a value dismissed the sheet, the footer was
 * never reachable, and the test failed on a button that was correct in the source
 * and absent on screen. Hence this component, which keeps the sheet open until
 * the user is done.
 *
 * ## Selection state
 *
 * `selected` is passed in rather than held here. The sheet is a renderer; the
 * caller owns the selection, because the caller is the one that has to decide
 * what an empty or invalid selection means (for the agenda configurator: refuse
 * to add a section, since `Selector.Tags(emptySet())` matches no task).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> MultiSelectSheet(
    title: String,
    items: List<MultiSelectItem<T>>,
    selectedKeys: Set<T>,
    onToggle: (T) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Confirm",
    confirmTestTag: String? = null,
    emptyMessage: String = "Nothing to choose from yet",
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BottomSheetHost(onDismiss = onDismiss) {
        SheetScaffold {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )

            if (items.isEmpty()) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items, key = { it.key.toString() }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // A modal sheet is its own window, so the
                                // app-root testTagsAsResourceId does not reach
                                // its rows.
                                .mapTestTagsAsResourceIds()
                                .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
                                .clickable { onToggle(item.key) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(
                                checked = item.selected || item.key in selectedKeys,
                                // The row owns the click: a checkbox that
                                // toggles on its own and again via the row
                                // would flip twice.
                                onCheckedChange = null,
                            )
                            Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            footer?.invoke(this)

            androidx.compose.material3.Button(
                onClick = onConfirm,
                enabled = selectedKeys.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = confirmLabel }
                    .then(confirmTestTag?.let { Modifier.testTag(it) } ?: Modifier),
            ) {
                Text(confirmLabel)
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
