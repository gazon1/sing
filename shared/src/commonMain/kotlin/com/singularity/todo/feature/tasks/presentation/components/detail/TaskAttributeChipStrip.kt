package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.detail.DetailMetaChip
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * PROTOTYPE (#2736): the four primary attributes as compact, wrapping chips.
 *
 * This is a presentation variant, not a second editing path: the values and the
 * callbacks that open the sheets are the same ones the full rows take, and the chips
 * carry the same test tags. The tag collection is one control that opens the existing
 * tag picker; there are no per-tag remove buttons here.
 *
 * It is opt-in through `TaskEditorContent(compactAttributes = true)` and is compared
 * against the full rows in `TaskAttributeStripPrototypeTest`. The comparison decides
 * whether it replaces them; until then the editor's default stays full rows.
 *
 * @param dueDate The due date, or null when unset.
 * @param dueTime The due time, or null.
 * @param dueDates Several dates when the work spans more than one; empty means one.
 * @param showDueDate False hides the due-date chip, mirroring the row's visibility.
 * @param project The project id, or null for "no project".
 * @param projectName The project's display name, or null when unavailable.
 * @param tagNames The tag names, in the same order as the ids the rows show.
 * @param tagCount The number of tags. Used for the overflow summary when [tagNames] is
 *   shorter than the collection — a draft may know the count but not the names.
 * @param onProjectClick Null hides the project chip, mirroring the project row.
 * @param onTagsClick Null hides the tags chip, mirroring the tags row.
 */
@Suppress("LongParameterList") // mirrors the four rows it replaces, one value per field
@Composable
internal fun TaskAttributeChipStrip(
    priority: TaskPriority,
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    dueDates: ImmutableList<LocalDate> = persistentListOf(),
    showDueDate: Boolean = true,
    project: ProjectId? = null,
    projectName: String? = null,
    tagNames: ImmutableList<String> = persistentListOf(),
    tagCount: Int = 0,
    onPriorityClick: () -> Unit,
    onDueDateClick: () -> Unit,
    onProjectClick: (() -> Unit)? = null,
    onTagsClick: (() -> Unit)? = null,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showDueDate) {
            Chip(
                label = dueDateRowLabel(dueDate, dueTime, dueDates),
                selected = dueDate != null,
                onClick = onDueDateClick,
                testTag = TestTags.TASK_EDITOR_DUE_ROW,
            )
        }
        Chip(
            label = priority.label,
            selected = priority != TaskPriority.None,
            onClick = onPriorityClick,
            testTag = TestTags.TASK_EDITOR_PRIORITY_ROW,
        )
        if (onProjectClick != null) {
            Chip(
                label = projectChipLabel(project, projectName),
                selected = project != null,
                onClick = onProjectClick,
                testTag = TestTags.TASK_EDITOR_PROJECT_ROW,
            )
        }
        if (onTagsClick != null) {
            Chip(
                label = tagChipSummary(tagNames, tagCount),
                selected = tagCount > 0,
                onClick = onTagsClick,
                testTag = TestTags.TASK_EDITOR_TAGS_ROW,
            )
        }
    }
}

/**
 * One chip. The width cap keeps a long name on the strip instead of letting it run off
 * the screen; the label truncates with an ellipsis and the full value stays reachable
 * through the picker the chip opens.
 */
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    DetailMetaChip(
        label = label,
        selected = selected,
        onClick = onClick,
        // `touchTarget()` before the width cap: the chip's own surface stays 32 dp and
        // the box that can be pressed is the app's 48 dp. FilterChip does not reserve
        // that box itself — measured at 32 dp — and a compact strip is exactly where a
        // thumb-sized control must not shrink.
        modifier =
            Modifier
                .testTag(testTag)
                .touchTarget()
                .widthIn(max = CHIP_MAX_WIDTH),
        maxLines = 1,
    )
}

internal fun projectChipLabel(
    project: ProjectId?,
    projectName: String?,
): String =
    when {
        project == null -> "No project"
        !projectName.isNullOrBlank() -> projectName
        else -> "Project unavailable"
    }

/**
 * The tags chip's label: the count, then up to two names; one tag is named outright.
 *
 * The count leads because the label is truncated, not wrapped, and a long name would
 * ellipsize a trailing summary away. A fixed two names rather than "as many as fit":
 * more on a wider screen would be nicer, and would make the label depend on measurement
 * — a chip whose text changes with the window is a chip no test can name. The picker
 * behind the chip lists the whole collection.
 */
internal fun tagChipSummary(
    names: ImmutableList<String>,
    count: Int,
): String {
    if (count <= 0) return "Add tags"
    val shown = names.take(TAG_NAMES_SHOWN)
    if (count == 1) return shown.firstOrNull() ?: "1 tag"
    val namesText = shown.joinToString(", ")
    // The count leads once there is more than one tag. A trailing "+N" is the nicer
    // shape, but a long first name ellipsizes the tail away — and with it the only
    // signal that another tag exists. The count is the part that must survive.
    return if (shown.isEmpty()) "$count tags" else "$count tags · $namesText"
}

private val CHIP_MAX_WIDTH = 240.dp

private const val TAG_NAMES_SHOWN = 2
