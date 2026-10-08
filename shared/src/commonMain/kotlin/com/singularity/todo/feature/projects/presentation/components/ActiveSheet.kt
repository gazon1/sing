package com.singularity.todo.feature.projects.presentation.components

import com.singularity.todo.feature.projects.domain.model.ProjectId

/**
 * All bottom-sheet overlays for [ProjectDetailContent].
 * Defined at package level so [ProjectDetailSheetsHost] can reference it.
 */
sealed interface ActiveSheet {
    data object PickColor : ActiveSheet
    data object PickIcon : ActiveSheet
    data class PickParent(val current: ProjectId?) : ActiveSheet
    data object ConfirmDelete : ActiveSheet
    data object ConfirmArchive : ActiveSheet
    data object PickReminder : ActiveSheet
    data object AddAttachment : ActiveSheet
    data object PickDueDate : ActiveSheet
    data object ShowChildren : ActiveSheet
    data object PickInheritedTagGroups : ActiveSheet
}
