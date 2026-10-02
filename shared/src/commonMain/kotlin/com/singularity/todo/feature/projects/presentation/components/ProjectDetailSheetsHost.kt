package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.ui.components.sheet.DatePickerSheet
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.model.ParentOption

/**
 * All sheets for [ProjectDetailContent].
 * Wrapped in [BottomSheetHost] by the caller.
 *
 * @param activeSheet The currently active sheet, or null if none.
 * @param currentContent Used to derive current values for sheets.
 * @param parentOptions Passed to [ParentPickerSheet].
 * @param onSheetDismiss Called when any sheet is dismissed.
 */
@Composable
fun ProjectDetailSheetsHost(
    activeSheet: ActiveSheet?,
    currentContent: CurrentProjectContent?,
    parentOptions: List<ParentOption>,
    onSheetDismiss: () -> Unit,
) {
    when (activeSheet) {
        null -> {
            /* no sheet */
        }

        is ActiveSheet.PickColor -> ColorPickerSheet(
            currentColor = currentContent?.color
                ?: com.singularity.todo.feature.projects.presentation.theme.ProjectColorPalette.default,
            onPick = { color ->
                currentContent?.actions?.onUpdateColor(color)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickIcon -> IconPickerSheet(
            currentIcon = currentContent?.icon,
            onPick = { icon ->
                currentContent?.actions?.onUpdateIcon(icon)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickParent -> ParentPickerSheet(
            options = parentOptions,
            currentParentId = currentContent?.parentId,
            onPick = { parentId ->
                currentContent?.actions?.onUpdateParent(parentId)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickDueDate -> DatePickerSheet(
            initialDate = currentContent?.dueDate,
            onDateSelected = { date ->
                currentContent?.actions?.onUpdateDueDate(date)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.ShowChildren -> ChildProjectsSheet(
            children = currentContent?.childProjects
                ?: emptyList(),
            onShowChildren = { child -> currentContent?.actions?.onNavigateToChild(child.id) },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.ConfirmDelete -> ConfirmDeleteSheet(
            projectName = currentContent?.name
                ?: "",
            onConfirm = {
                currentContent?.actions?.onDelete()
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.ConfirmArchive -> ConfirmArchiveSheet(
            isArchived = currentContent?.isArchived
                ?: false,
            onConfirm = {
                currentContent?.actions?.onToggleArchive()
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickReminder -> ReminderPickerSheet(
            currentOffset = currentContent?.reminderOffset,
            onSelect = { offset ->
                currentContent?.actions?.onSetReminder(offset?.minutes)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.AddAttachment -> AttachmentPlaceholderSheet(
            onDismiss = onSheetDismiss,
        )
    }
}

/**
 * Current project values a sheet needs to render itself, plus the [ProjectDetailActions]
 * dispatcher its `onPick`/`onConfirm` handlers call into.
 *
 * **Not a `data class`.** The previous shape held ten nullable callback fields; a generated
 * `equals` compares lambdas by identity, so two structurally identical instances could
 * never be equal and the class was only usable as an opaque token. Carrying the single
 * [actions] value class instead keeps the type honest — every sheet dispatches through the
 * same named helpers the rest of the screen uses, and no callback is optional.
 *
 * @see ProjectDetailActions
 */
@Immutable
class CurrentProjectContent(
    val name: String,
    val color: Int,
    val icon: String?,
    val parentId: ProjectId?,
    val dueDate: kotlinx.datetime.LocalDate?,
    val isArchived: Boolean,
    val childProjects: List<Project>,
    val reminderOffset: ReminderOffset?,
    val actions: ProjectDetailActions,
)
