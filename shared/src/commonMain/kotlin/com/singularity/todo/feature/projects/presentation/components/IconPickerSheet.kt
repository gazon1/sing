package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry

private val COMMON_EMOJIS = listOf(
    "🚀", "🎯", "💡", "🔥", "⭐", "❤️", "✅", "📌", "🏠", "💼",
    "📚", "🎵", "🐾", "✈️", "🏥", "🛒", "💻", "🔧", "🎨", "📊",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerSheet(currentIcon: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Icon", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectIconRegistry.all.forEach { (key, icon) ->
                Icon(
                    icon,
                    contentDescription = key,
                    tint = if (key == currentIcon) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .clickable { onPick(key) }
                        .background(
                            if (key == currentIcon) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            CircleShape,
                        )
                        .padding(8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        EmojiSection(currentIcon = currentIcon, onPick = onPick)

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { onPick(null) }) { Text("None") }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiSection(currentIcon: String?, onPick: (String?) -> Unit) {
    Text("Emoji", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        COMMON_EMOJIS.forEach { emoji ->
            Text(
                text = emoji,
                modifier = Modifier
                    .size(40.dp)
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable { onPick(emoji) }
                    .background(
                        if (emoji == currentIcon) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        CircleShape,
                    )
                    .padding(8.dp),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    val custom = rememberTextFieldState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            state = custom,
            label = { Text("Custom emoji") },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            val v = custom.text.trim().toString()
            if (v.isNotEmpty()) {
                onPick(v)
            }
        }) { Text("Add") }
    }
}
