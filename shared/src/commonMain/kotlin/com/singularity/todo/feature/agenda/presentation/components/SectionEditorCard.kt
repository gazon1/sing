package com.singularity.todo.feature.agenda.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TouchTarget
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
 * ## The size of the three state buttons
 *
 * Each of the three buttons is a 48 dp square that carries the click, with a 24 dp
 * glyph centred inside it. Material's `IconButton` cannot be laid out that way: the
 * size it paints at (40 dp) sits *inside* its clickable, so the clickable's own node
 * is 40 dp and no modifier widens it. A control that has to be 48 dp wide is laid out
 * at 48 dp itself, which is what [SectionActionButton] does.
 *
 * What the old 40 dp node cost was the button's *bounds*: the rect the semantics tree
 * reports, and the one an accessibility service treats as the control's target. The
 * pointer area was never 40 dp — Compose expands the touch bounds of a `clickable`
 * smaller than the platform's minimum touch target to that minimum, centred — so the
 * band around the old buttons was not dead. The guarantee was emergent, held by a
 * framework behaviour rather than by the layout, and the announced bounds were still
 * 40 dp. Now the layout holds it.
 *
 * The row reserves 48 dp per button, as it always has. The reservation used to come
 * from `minimumInteractiveComponentSize()` around a 40 dp button; it now comes from the
 * size of the button, so the row's layout does not change and the name column keeps
 * the width it had.
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
                SectionActionButton(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move section up",
                    enabled = canMoveUp,
                    onClick = onMoveUp,
                )
                SectionActionButton(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move section down",
                    enabled = canMoveDown,
                    onClick = onMoveDown,
                )
                SectionActionButton(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete section",
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = onDelete,
                )
            },
        )
    }
}

/**
 * The size of the glyph inside a [SectionActionButton].
 *
 * The size Material's icon button paints its glyph at, named here so the layout reads
 * as what it is: a 48 dp target with a 24 dp glyph inside it.
 */
private val SectionActionGlyphSize = 24.dp

/**
 * An icon action whose target is the full [TouchTarget.MinSize].
 *
 * Material's `IconButton` cannot be laid out that way: the 40 dp it paints at sits
 * inside its clickable, so the clickable's own node is 40 dp and no modifier widens
 * it. This lays the clickable out at [TouchTarget.MinSize] and centres a smaller
 * glyph inside it, so the control's own bounds — the rect the semantics tree reports
 * and the one an accessibility service treats as the target — are the 48 dp the
 * control is meant to have.
 *
 * The clickable merges the glyph's semantics, so the control is announced once, with
 * the glyph's description — the same contract `IconButton` offers.
 *
 * @param imageVector The glyph to draw.
 * @param contentDescription What the control is announced as.
 * @param onClick Called when the control is activated.
 * @param modifier Compose modifier.
 * @param enabled False disables the control: it is still drawn, but it does not
 *        answer a pointer and is announced as disabled.
 * @param colors The colours of the glyph. The enabled colour is the content colour of
 *        the surface the control sits on; the disabled one is that colour at
 *        Material's disabled opacity.
 */
@Composable
private fun SectionActionButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
) {
    val contentColor = if (enabled) colors.contentColor else colors.disabledContentColor
    Box(
        modifier =
            modifier
                .size(TouchTarget.MinSize)
                .clip(IconButtonDefaults.standardShape)
                .clickable(
                    onClick = onClick,
                    enabled = enabled,
                    role = Role.Button,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(SectionActionGlyphSize),
        )
    }
}
