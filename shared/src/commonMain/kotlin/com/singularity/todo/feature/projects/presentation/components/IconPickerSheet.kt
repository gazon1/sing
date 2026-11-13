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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.projects.data.RecentEmojiStore
import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val COMMON_EMOJIS =
    listOf(
        "🚀",
        "🎯",
        "💡",
        "🔥",
        "⭐",
        "❤️",
        "✅",
        "📌",
        "🏠",
        "💼",
        "📚",
        "🎵",
        "🐾",
        "✈️",
        "🏥",
        "🛒",
        "💻",
        "🔧",
        "🎨",
        "📊",
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerSheet(
    currentIcon: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    /**
     * The emoji the user picked before, so they can be picked again with one tap.
     *
     * Resolved from the graph by default; a test hands in a store of its own.
     */
    recentEmojiStore: RecentEmojiStore = koinInject(),
) {
    val recentEmojis by recentEmojiStore.recent.collectAsStateWithLifecycle(persistentListOf())
    val scope = rememberCoroutineScope()

    // An emoji is recorded when it is picked, so the next picker opens with it
    // at the front. A registry key is an icon, not an emoji, and is not
    // recorded.
    //
    // The record is awaited before the pick is applied: the sheet is still
    // composed at that point, and a write cancelled by the sheet's own
    // dismissal would lose the emoji the user just chose.
    val pick: (String?) -> Unit = { value ->
        if (value != null && resolveProjectIcon(value) is ResolvedProjectIcon.Emoji) {
            scope.launch {
                recentEmojiStore.record(value)
                onPick(value)
            }
        } else {
            onPick(value)
        }
    }

    Column(modifier = Modifier.padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The sheet is modal and picking is the only way out of it otherwise,
            // so "cancel without choosing" needs a control of its own. It is the
            // same close affordance the editor sheets use, so a dismiss looks the
            // same wherever it appears.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TestTags.PROJECT_PICKER_DISMISS),
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
            }
            Spacer(Modifier.width(8.dp))
            Text("Icon", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectIconRegistry.all.forEach { (key, icon) ->
                Icon(
                    icon,
                    contentDescription = key,
                    tint =
                        if (key == currentIcon) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier =
                        Modifier
                            .touchTarget()
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { pick(key) }
                            .background(
                                if (key == currentIcon) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                                CircleShape,
                            ).padding(8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        EmojiSection(
            currentIcon = currentIcon,
            recentEmojis = recentEmojis,
            onPick = pick,
        )

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { pick(null) }) { Text("None") }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiSection(
    currentIcon: String?,
    recentEmojis: ImmutableList<String>,
    onPick: (String?) -> Unit,
) {
    // No history, no section: an empty "Recent" heading above an empty row is
    // a blank the user has to read past on every opening of the sheet.
    if (recentEmojis.isNotEmpty()) {
        Text(
            text = "Recent",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag(TestTags.PROJECT_ICON_RECENT_SECTION),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recentEmojis.forEach { emoji ->
                EmojiChip(
                    emoji = emoji,
                    selected = emoji == currentIcon,
                    onClick = { onPick(emoji) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Text("Emoji", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        COMMON_EMOJIS.forEach { emoji ->
            EmojiChip(
                emoji = emoji,
                selected = emoji == currentIcon,
                onClick = { onPick(emoji) },
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

/**
 * One emoji in the picker.
 *
 * The glyph is drawn as one grapheme: a composed sequence — a ZWJ family, a
 * flag, a skin-tone modifier, a variation selector — is one value and must not
 * be split across lines.
 */
@Composable
private fun EmojiChip(
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = emoji,
        maxLines = 1,
        softWrap = false,
        modifier =
            Modifier
                .touchTarget()
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    CircleShape,
                ).padding(8.dp),
    )
}
