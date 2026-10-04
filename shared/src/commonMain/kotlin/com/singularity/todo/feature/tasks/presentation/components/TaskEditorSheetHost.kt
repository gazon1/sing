package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Reusable bottom sheet wrapper for the Task Editor.
 * Provides drag handle, centered title, optional close (×) and confirm (✓) buttons.
 *
 * @param title       Sheet title displayed at the top.
 * @param onClose    Called when the × button or sheet is dismissed.
 * @param onConfirm  Optional; when non-null a ✓ button appears on the right.
 *                   When null, only the × button is shown.
 * @param sheetState Optional; share a sheet state to control dismiss externally.
 * @param content     Sheet body content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheetHost(
    title: String,
    onClose: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // Header: close | title | confirm
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                if (onConfirm != null) {
                    IconButton(onClick = onConfirm) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Done",
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            content()
        }

        // Bottom safe area
        Spacer(Modifier.height(32.dp))
    }
}
