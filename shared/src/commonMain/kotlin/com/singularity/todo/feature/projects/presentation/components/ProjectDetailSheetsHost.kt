package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.runtime.Composable
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
                currentContent?.onUpdateColor?.invoke(color)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickIcon -> IconPickerSheet(
            currentIcon = currentContent?.icon,
            onPick = { icon ->
                currentContent?.onUpdateIcon?.invoke(icon)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickParent -> ParentPickerSheet(
            options = parentOptions,
            currentParentId = currentContent?.parentId,
            onPick = { parentId ->
                currentContent?.onUpdateParent?.invoke(parentId)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickDueDate -> DatePickerSheet(
            initialDate = currentContent?.dueDate,
            onDateSelected = { date ->
                currentContent?.onUpdateDueDate?.invoke(date)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.ShowChildren -> ChildProjectsSheet(
            children = currentContent?.childProjects
                ?: emptyList(),
            onShowChildren = { child -> currentContent?.onNavigateToChild?.invoke(child.id) },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.ConfirmDelete -> ConfirmDeleteSheet(
            projectName = currentContent?.name
                ?: "",
            onConfirm = {
                currentContent?.onDelete?.invoke()
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.ConfirmArchive -> ConfirmArchiveSheet(
            isArchived = currentContent?.isArchived
                ?: false,
            onConfirm = {
                currentContent?.onToggleArchive?.invoke()
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
 * Convenience data class holding current project content for sheet operations.
 * Avoids passing many nullable lambdas individually.
 */
data class CurrentProjectContent(
    val name: String,
    val color: Int,
    val icon: String?,
    val parentId: ProjectId?,
    val dueDate: kotlinx.datetime.LocalDate?,
    val isArchived: Boolean,
    val childProjects: List<Project>,
    val onUpdateColor: ((Int) -> Unit)?,
    val onUpdateIcon: ((String?) -> Unit)?,
    val onUpdateParent: ((ProjectId?) -> Unit)?,
    val onUpdateDueDate: ((kotlinx.datetime.LocalDate?) -> Unit)?,
    val onUpdateName: ((String) -> Unit)?,
    val onUpdateDescription: ((String?) -> Unit)?,
    val onDelete: (() -> Unit)?,
    val onToggleArchive: (() -> Unit)?,
    val onNavigateToChild: ((ProjectId) -> Unit)?,
)
