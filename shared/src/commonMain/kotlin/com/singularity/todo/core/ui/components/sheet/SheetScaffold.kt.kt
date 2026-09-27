package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            .padding(bottom = 24.dp),  // insets уже даёт ModalBottomSheet
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
        actions?.let { SheetActionsRow(it) }
    }
}

data class SheetActions(
    val onClear: (() -> Unit)? = null,
    val onCancel: () -> Unit,
    val onConfirm: () -> Unit,
    val confirmLabel: String = "OK",
    val cancelLabel: String = "Cancel",
    val clearLabel: String = "Clear",
)

@Composable
private fun SheetActionsRow(actions: SheetActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (actions.onClear != null) {
            TextButton(onClick = actions.onClear) { Text(actions.clearLabel) }
        } else {
            Spacer(Modifier)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.onCancel) { Text(actions.cancelLabel) }
            TextButton(onClick = actions.onConfirm) { Text(actions.confirmLabel) }
        }
    }
}

@Composable
fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    TODO("Not yet implemented")
}
