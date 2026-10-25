package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.runtime.Composable
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.ui.components.sheet.DatePickerSheet
import com.singularity.todo.feature.projects.presentation.model.ParentOption
import com.singularity.todo.feature.projects.presentation.model.TagGroupOption
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf

/**
 * All sheets for [ProjectDetailContent].
 * Wrapped in [BottomSheetHost] by the caller.
 *
 * @param activeSheet The currently active sheet, or null if none.
 * @param currentContent Used to derive current values for sheets.
 * @param parentOptions Passed to [ParentPickerSheet].
 * @param tagGroups Passed to [InheritedTagGroupsSheet].
 * @param inheritedTagGroupIds Passed to [InheritedTagGroupsSheet].
 * @param onSheetDismiss Called when any sheet is dismissed.
 */
@Suppress("LongMethod") // Composable pattern: large when is intentional; alpha.6 changed counting model
@Composable
fun ProjectDetailSheetsHost(
    activeSheet: ActiveSheet?,
    currentContent: CurrentProjectContent?,
    parentOptions: ImmutableList<ParentOption>,
    tagGroups: ImmutableList<TagGroupOption>,
    inheritedTagGroupIds: ImmutableSet<TagGroupId>,
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
                ?: persistentListOf(),
            onShowChildren = { child -> currentContent?.actions?.onNavigateToChild(child.id) },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickInheritedTagGroups -> InheritedTagGroupsSheet(
            options = tagGroups,
            currentIds = inheritedTagGroupIds,
            onPick = { ids ->
                currentContent?.actions?.onUpdateInheritedTagGroups(ids)
                onSheetDismiss()
            },
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

        is ActiveSheet.ConfirmClose -> ConfirmCloseSheet(
            openTaskCount = currentContent?.openTaskCount
                ?: 0,
            onConfirm = {
                currentContent?.actions?.onCloseProject()
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.PickReminder -> ReminderPickerSheet(
            currentOffset = currentContent?.reminderOffset,
            defaultOffset = currentContent?.defaultReminderOffset ?: ReminderOffset.AT_DUE,
            onSelect = { offset ->
                currentContent?.actions?.onSetReminder(offset.minutes)
                onSheetDismiss()
            },
            onDismiss = onSheetDismiss,
        )

        is ActiveSheet.AddAttachment -> AttachmentPlaceholderSheet(
            onDismiss = onSheetDismiss,
        )
    }
}
