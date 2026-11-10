package com.singularity.todo.core.ui.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A meta-information chip used in detail screens for date, priority, project, and tag metadata.
 *
 * Replaces `MetaLabel` (NotePreview) and `FilterChip` usages in `ProjectMetaChipsRow`.
 *
 * @param label The display text of the chip.
 * @param selected Whether the chip is in a selected/active state.
 * @param onClick Called when the user taps the chip. Pass `null` for a read-only label.
 * @param modifier Modifier for the chip.
 * @param maxLines How many lines the label may occupy. Unlimited by default, which is
 *   the old behaviour. A finite value truncates with an ellipsis instead of growing the
 *   chip — a chip taller than its neighbours drags the whole row down, and a chip wider
 *   than the screen pushes the next control off it.
 */
@Composable
fun DetailMetaChip(
    label: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    // `onClick ?: {}` is deliberate, and the API is built around it: `onClick` is
    // nullable precisely so a caller can render a read-only label, and the chip is
    // disabled when it is null (`enabled = onClick != null`, below). The elvis
    // supplies FilterChip's non-null parameter, it does not hide a missing handler.
    @Suppress("NoEmptyOnClickLambda")
    FilterChip(
        selected = selected,
        onClick = onClick ?: {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        },
        enabled = onClick != null,
        modifier = modifier,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    )
}
