package com.singularity.todo.feature.agenda.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.DragHandleRow
import com.singularity.todo.core.ui.components.HandleSide
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.selector.typeDescription
import kotlin.math.roundToInt

/**
 * A reorderable list of [Section] items.
 *
 * Uses a custom `pointerInput`-based implementation for true drag-and-drop
 * across both Android and JVM (no platform-specific library required).
 *
 * ## Haptic behavior
 *
 * - [HapticFeedbackType.TextHandleMove] fires on drag start.
 * - [HapticFeedbackType.TextHandleMove] fires each time the dragged item
 *   crosses a section boundary (changes the predicted target index).
 *
 * ## Scroll behavior
 *
 * When the target index moves beyond the visible range, [LazyColumn]
 * scrolls to keep the dragged or target item visible.
 *
 * @param sections The sections to display.
 * @param onSectionsReordered Called when the user has reordered sections
 *        with the new list as argument. The caller is responsible for updating
 *        the VM's draft state via [SavedAgendaIntent.SectionsReordered].
 * @param modifier Compose modifier.
 */
@Composable
fun ReorderableSectionList(
    sections: List<Section>,
    onSectionsReordered: (List<Section>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // Drag state
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    var isDragging by remember { mutableStateOf(false) }

    // Scroll to keep the dragged item visible during drag.
    // Uses animateScrollToItem so the list jumps to show the target section
    // whenever the dragged item would move off-screen.
    LaunchedEffect(targetIndex, isDragging) {
        if (targetIndex >= 0 && isDragging) {
            listState.animateScrollToItem(targetIndex.coerceIn(0, sections.lastIndex))
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = sections,
            key = { _, section -> "${section.name}#${section.order}" },
        ) { index, section ->
            val isDragged = index == draggingIndex

            val elevation by animateDpAsState(
                targetValue = if (isDragged) 12.dp else 0.dp,
                label = "dragElevation",
            )
            val scale by animateFloatAsState(
                targetValue = if (isDragged) 1.02f else 1f,
                label = "dragScale",
            )

            // Placeholder at original position (faded) while dragging
            AnimatedVisibility(visible = !isDragged) {
                SectionRow(
                    section = section,
                    isDragging = false,
                    elevation = elevation,
                    modifier = Modifier
                        .graphicsLayer { if (isDragged) alpha = 0.3f }
                        .pointerInput(index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    if (draggingIndex == -1) {
                                        draggingIndex = index
                                        targetIndex = index
                                        isDragging = true
                                        dragOffsetY = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDrag = { _, dragAmount ->
                                    dragOffsetY += dragAmount.y

                                    val visibleItems = listState.layoutInfo.visibleItemsInfo
                                    if (visibleItems.isNotEmpty()) {
                                        val firstVisible = visibleItems.first()
                                        val itemHeight = firstVisible.size.toFloat()
                                        val draggedPos = firstVisible.offset + (index * itemHeight) + dragOffsetY
                                        val newTarget = (draggedPos / itemHeight)
                                            .roundToInt()
                                            .coerceIn(0, sections.lastIndex)

                                        if (newTarget != targetIndex && newTarget != draggingIndex) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            targetIndex = newTarget
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (draggingIndex != -1 && targetIndex != draggingIndex) {
                                        val reordered = sections.toMutableList().apply {
                                            add(targetIndex, removeAt(draggingIndex))
                                        }
                                        onSectionsReordered(reordered)
                                    }
                                    isDragging = false
                                    draggingIndex = -1
                                    targetIndex = -1
                                    dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    isDragging = false
                                    draggingIndex = -1
                                    targetIndex = -1
                                    dragOffsetY = 0f
                                },
                            )
                        },
                )
            }

            // Floating preview while dragging
            if (isDragged) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                        .graphicsLayer {
                            scaleX = scale;
                            scaleY = scale
                        },
                ) {
                    SectionRow(
                        section = section,
                        isDragging = true,
                        elevation = 12.dp,
                    )
                }
            }
        }
    }
}

/**
 * A single section row in the reorderable list.
 * Shows section name, type badge, and a drag handle.
 */
@Composable
private fun SectionRow(
    section: Section,
    isDragging: Boolean,
    elevation: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        DragHandleRow(
            text = section.name,
            subtitle = section.selector.typeDescription,
            handleSide = HandleSide.Trailing,
            padding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
        )
    }
}
