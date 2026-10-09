package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import androidx.compose.ui.unit.dp

/**
 * Scaffold для picker-sheet-ов с actions-строкой.
 * Не знает про SheetState — visibility управляется родителем через [com.singularity.todo.core.ui.components.BottomSheetHost].
 */
@Composable
fun SheetScaffold(
    modifier: Modifier = Modifier,
    actions: SheetActions? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp), // insets уже даёт ModalBottomSheet
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
        actions?.let { SheetActionsRow(it) }
    }
}

/**
 * @param testTagConfirm Optional testTag for the confirm button. When null, no testTag is applied.
 * @param testTagCancel Optional testTag for the cancel button.
 * @param testTagClear Optional testTag for the clear button.
 */
data class SheetActions(
    val onClear: (() -> Unit)? = null,
    val onCancel: () -> Unit,
    val onConfirm: () -> Unit,
    val confirmLabel: String = "OK",
    val cancelLabel: String = "Cancel",
    val clearLabel: String = "Clear",
    val testTagConfirm: String? = null,
    val testTagCancel: String? = null,
    val testTagClear: String? = null,
)

@Composable
private fun SheetActionsRow(actions: SheetActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (actions.onClear != null) {
            TextButton(
                onClick = actions.onClear,
                modifier = actions.testTagClear?.let { Modifier.testTag(it).mapTestTagsAsResourceIds() } ?: Modifier,
            ) { Text(actions.clearLabel) }
        } else {
            Spacer(Modifier)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = actions.onCancel,
                modifier = actions.testTagCancel?.let { Modifier.testTag(it).mapTestTagsAsResourceIds() } ?: Modifier,
            ) { Text(actions.cancelLabel) }
            TextButton(
                onClick = actions.onConfirm,
                modifier = actions.testTagConfirm?.let { Modifier.testTag(it).mapTestTagsAsResourceIds() } ?: Modifier,
            ) { Text(actions.confirmLabel) }
        }
    }
}
