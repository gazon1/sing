package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.emoji.EmojiCatalog
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry
import com.vanniktech.emoji.EmojiCategory
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerSheet(
    currentIcon: String?,
    /**
     * The emoji the user picked before, most recent first, so they can be
     * picked again with one tap.
     *
     * The sheet draws what it is given: it neither reads the history nor
     * writes it, so it stays free of both storage and DI.
     */
    recentEmojis: ImmutableList<String>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // The sheet opens partially expanded on Android, and the search field sits
    // below the icon registry: without this the field is unreachable until the
    // user drags the sheet up. The grid inside keeps its own bounded height.
    Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
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
                            .clickable { onPick(key) }
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
            onPick = onPick,
        )

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { onPick(null) }) { Text("None") }

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

    val searchState = rememberTextFieldState()
    EmojiSearchField(searchState)
    Spacer(Modifier.height(8.dp))

    EmojiGrid(
        query = searchState.text.toString(),
        currentIcon = currentIcon,
        onPick = onPick,
    )

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

@Composable
private fun EmojiSearchField(searchState: TextFieldState) {
    OutlinedTextField(
        state = searchState,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.PROJECT_ICON_SEARCH),
        placeholder = { Text("Search emoji") },
        lineLimits = TextFieldLineLimits.SingleLine,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (searchState.text.isNotEmpty()) {
                IconButton(onClick = { searchState.clearText() }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
    )
}

/**
 * The catalog under the search field.
 *
 * With no query it is the provider's categories, in the provider's order; with a
 * query it is the library's shortcode search. The grid is bounded because a lazy
 * grid inside the sheet's unbounded column has to be told its height.
 */
@Composable
private fun EmojiGrid(
    query: String,
    currentIcon: String?,
    onPick: (String?) -> Unit,
) {
    if (query.isBlank()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(48.dp),
            modifier = Modifier.fillMaxWidth().height(emojiGridHeight),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EmojiCatalog.categories.forEach { category ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = category.displayName(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(category.emojis) { emoji ->
                    EmojiChip(
                        emoji = emoji.unicode,
                        selected = emoji.unicode == currentIcon,
                        onClick = { onPick(emoji.unicode) },
                    )
                }
            }
        }
        return
    }

    val results = remember(query) { EmojiCatalog.search(query) }
    if (results.isEmpty()) {
        Text(
            text = "No emoji match \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.PROJECT_ICON_NO_MATCHES),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(48.dp),
        modifier = Modifier.fillMaxWidth().height(emojiGridHeight),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(results) { emoji ->
            EmojiChip(
                emoji = emoji.unicode,
                selected = emoji.unicode == currentIcon,
                onClick = { onPick(emoji.unicode) },
            )
        }
    }
}

private val emojiGridHeight = 240.dp

/** The library's categories name themselves in the user's language, English first. */
private fun EmojiCategory.displayName(): String = categoryNames["en"] ?: categoryNames.values.firstOrNull() ?: "Emoji"

/**
 * One emoji in the picker.
 *
 * The glyph is drawn the way it always was: one text in one circle. A composed
 * sequence — a ZWJ family, a flag, a skin-tone modifier, a variation selector
 * — is one grapheme and is drawn as one.
 */
@Composable
private fun EmojiChip(
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = emoji,
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
