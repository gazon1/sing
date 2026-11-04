package com.singularity.todo.feature.agenda.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.DragHandleIcon
import com.singularity.todo.core.ui.components.DragHandleRow
import com.singularity.todo.core.ui.components.HandleSide
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.selector.typeDescription

/**
 * A section editor card shown in the "edit agenda" screen.
 *
 * Displays the section name and selector description, with move up / move down
 * buttons for reordering and a delete button.
 *
 * ## Why buttons and not a drag handle
 *
 * This previously rendered a [DragHandleIcon] with no drag behaviour attached, while
 * its KDoc claimed it was "for reordering". Reordering was in fact unreachable even
 * though [SavedAgendaIntent.SectionsReordered] and the whole reorder pipeline already
 * existed. Explicit buttons fix that *and* are operable by screen readers and from a
 * keyboard, which a long-press drag handle is not. The drag-based
 * `ReorderableSectionList` was the better desktop gesture but nested its own
 * `LazyColumn` and so could not be used inside this screen's; it was removed with
 * the other dead UI components. See ADR 2026-09-30-section-reorder-via-buttons.
 *
 * ## The state layer is drawn 40 dp
 *
 * Three 40 dp state layers in one row already crowd a phone's width, so each button
 * keeps the 40 dp painted size it had — but the *modifier order* is what decides
 * whether the button is still a thumb-sized target. `Modifier.minimumInteractiveComponentSize()`
 * comes first and `size(40.dp)` second: the minimum sits outside the size, so it can
 * reserve Material's 48 dp while the size still constrains what is drawn, and the row
 * therefore gives each button 48 dp and leaves the rest to the name column. Reversed,
 * the size would cap the minimum back to 40 dp — the arrangement this screen shipped
 * with, where the button was drawn 40 dp and its target was 40 dp too.
 *
 * `minimumInteractiveComponentSize()` is Material's own mechanism for exactly this,
 * so this screen no longer leans on the project's `Modifier.touchTarget()` helper
 * (#2173 questions whether that helper's extra layout space becomes hit area; the
 * Material modifier is the sanctioned form of the same idea).
 *
 * @param section The section to display.
 * @param index The section's position in the list.
 * @param canMoveUp False for the first row. The button is then disabled rather than
 *        hidden, so the controls keep a stable position as the user works down the
 *        list and the row heights never jump.
 * @param canMoveDown False for the last row.
 * @param onMoveUp Called to move this section one position earlier.
 * @param onMoveDown Called to move this section one position later.
 * @param onDelete Called when the user taps the delete button.
 * @param modifier Compose modifier.
 */
@Composable
fun SectionEditorCard(
    section: Section,
    index: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
    ) {
        DragHandleRow(
            text = section.name,
            subtitle = section.selector.typeDescription,
            handleSide = HandleSide.Leading,
            padding =
                androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 4.dp,
                    top = 4.dp,
                    bottom = 4.dp,
                ),
            trailing = {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.minimumInteractiveComponentSize().size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move section up",
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.minimumInteractiveComponentSize().size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move section down",
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize().size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Delete section",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}
