package com.singularity.todo.feature.projects.presentation.components

import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.serialization.Serializable

/**
 * All bottom-sheet overlays for [ProjectDetailContent].
 * Defined at package level so [ProjectDetailSheetsHost] can reference it.
 *
 * `@Serializable` because the open sheet is saved across a rotation and process
 * death by [com.singularity.todo.core.ui.components.rememberOverlayState].
 */
@Serializable
sealed interface ActiveSheet {
    @Serializable
    data object PickColor : ActiveSheet

    @Serializable
    data object PickIcon : ActiveSheet

    @Serializable
    data class PickParent(val current: ProjectId?) : ActiveSheet

    @Serializable
    data object ConfirmDelete : ActiveSheet

    @Serializable
    data object ConfirmArchive : ActiveSheet

    @Serializable
    data object ConfirmClose : ActiveSheet

    @Serializable
    data object PickReminder : ActiveSheet

    @Serializable
    data object AddAttachment : ActiveSheet

    @Serializable
    data object PickDueDate : ActiveSheet

    @Serializable
    data object ShowChildren : ActiveSheet

    @Serializable
    data object PickInheritedTagGroups : ActiveSheet
}
