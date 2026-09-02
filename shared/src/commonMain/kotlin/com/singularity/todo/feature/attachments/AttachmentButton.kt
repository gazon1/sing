package com.singularity.todo.feature.attachments

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AttachmentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    attachmentCount: Int = 0
) {
    IconButton(onClick = onClick, modifier = modifier) {
        if (attachmentCount > 0) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Attachments ($attachmentCount)",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Add attachment",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
