package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.touchTarget

/**
 * The header row of a collapsible section.
 *
 * One header for every section that lists something, so that the title, the count
 * and the affordance that opens and closes the section are the same everywhere.
 * The row is the only target that toggles the section — the content below it is
 * not a target, and neither is the chevron on its own.
 *
 * @param label The section's title, with its count when it has one — see
 *        [sectionTitle].
 * @param expanded Whether the section is open. The chevron says so.
 * @param onToggle Called when the row is tapped. `null` means the section cannot
 *        be collapsed and the row is not a target.
 * @param trailing Content drawn at the end of the row — the section's own
 *        action, such as the logbook's add-note button.
 * @param testTag The tag of the row. The row is the section's only target, so
 *        the tag belongs to it and not to the card around it.
 */
@Composable
fun SectionHeader(
    icon: @Composable () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    testTag: String? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .touchTarget()
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        if (onToggle != null) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The title of a section, with the count of what it holds.
 *
 * One owner for the rule, so that no section can report a count over nothing and
 * no two sections can spell the same title differently.
 *
 * @param base The name of the section.
 * @param total How many things the section holds. Zero means the section is
 *        empty and its title carries no count.
 * @param done How many of them are done. `null` means the section counts things
 *        rather than progress, and the title carries a plain count.
 */
fun sectionTitle(
    base: String,
    total: Int,
    done: Int? = null,
): String =
    when {
        total <= 0 -> base
        done != null -> "$base ($done/$total)"
        else -> "$base ($total)"
    }
