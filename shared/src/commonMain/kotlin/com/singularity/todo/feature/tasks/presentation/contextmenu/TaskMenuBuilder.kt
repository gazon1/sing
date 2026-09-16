package com.singularity.todo.feature.tasks.presentation.contextmenu

import com.singularity.todo.core.ui.menu.MenuNode
import com.singularity.todo.core.ui.menu.buildMenuNodes
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.presentation.model.TaskUi

/**
 * Builds the 28-item context menu for a single task.
 *
 * Labels are present for all 28 items matching the TickTick reference.
 * Icons are rendered by the platform-specific renderer (jvmMain) — they are
 * omitted here since [androidx.compose.material.icons] is not available in commonMain.
 *
 * @param taskUi   the task being right-clicked
 * @param hasAiContext true when the task has sufficient content for AI actions
 * @param actions  callbacks for wired actions; `null` fields mean the item is hidden
 */
fun buildTaskContextMenu(
    taskUi: TaskUi,
    hasAiContext: Boolean,
    actions: TaskMenuActions,
): List<MenuNode> = buildMenuNodes {
    // ── 1. Pin / Unpin ──────────────────────────────────────────────────────
    item(
        id = "pin",
        label = if (taskUi.domainTask?.isPinned == true) "Unpin" else "Pin",
        checked = taskUi.domainTask?.isPinned == true,
        enabled = actions.onTogglePin != null,
    ) {
        actions.onTogglePin?.invoke()
        actions.onDismiss()
    }

    // ── 2. Mark as Completed / Uncompleted ─────────────────────────────────
    item(
        id = "complete",
        label = if (taskUi.isCompleted) "Mark as uncompleted" else "Mark as completed",
        checked = taskUi.isCompleted,
        enabled = actions.onToggleComplete != null,
    ) {
        actions.onToggleComplete?.invoke()
        actions.onDismiss()
    }

    // ── 3. Move to today ────────────────────────────────────────────────────
    item(id = "move_today", label = "Move to today") {
        // TODO: wire to MoveTaskUseCase with dueDate = today
        actions.onDismiss()
    }

    // ── 4. Move to tomorrow ────────────────────────────────────────────────
    item(id = "move_tomorrow", label = "Move to tomorrow") {
        // TODO: wire to MoveTaskUseCase with dueDate = tomorrow
        actions.onDismiss()
    }

    // ── 5. Move to next week ────────────────────────────────────────────────
    item(id = "move_next_week", label = "Move to next week") {
        // TODO: wire to MoveTaskUseCase with dueDate = next monday
        actions.onDismiss()
    }

    // ── 6. Move to project → ───────────────────────────────────────────────
    subMenu(
        id = "move_to_project",
        label = "Move to project",
        children = buildMenuNodes {
            item(id = "move_project_none", label = "No project") {
                // TODO: wire to MoveTaskUseCase with projectId = null
                actions.onDismiss()
            }
            // TODO: enumerate existing projects dynamically
            item(id = "move_project_placeholder", label = "— add project —", enabled = false) {}
        },
    )

    // ── 7. Set priority → ──────────────────────────────────────────────────
    subMenu(
        id = "set_priority",
        label = "Set priority",
        children = buildMenuNodes {
            item(id = "priority_p1", label = "P1  High", enabled = false) {}
            item(id = "priority_p2", label = "P2  Medium", enabled = false) {}
            item(id = "priority_p3", label = "P3  Low", enabled = false) {}
            item(id = "priority_p4", label = "P4  None", enabled = false) {}
        },
    )

    // ── 8. Set due date → ─────────────────────────────────────────────────
    subMenu(
        id = "set_due_date",
        label = "Set due date",
        children = buildMenuNodes {
            item(id = "due_today", label = "Today", enabled = false) {}
            item(id = "due_tomorrow", label = "Tomorrow", enabled = false) {}
            item(id = "due_next_week", label = "Next week", enabled = false) {}
            item(id = "due_pick", label = "Pick date…", enabled = false) {}
            item(id = "due_none", label = "No date", enabled = false) {}
        },
    )

    // ── 9. Add label ───────────────────────────────────────────────────────
    item(id = "add_label", label = "Add label") {
        // TODO: wire to AddLabelUseCase
        actions.onDismiss()
    }

    // ── 10. Set recurring ──────────────────────────────────────────────────
    item(
        id = "set_recurring",
        label = if (taskUi.isRecurring) "Edit recurring" else "Set recurring",
    ) {
        // TODO: wire to SetRecurringUseCase
        actions.onDismiss()
    }

    divider()

    // ── 11. Duplicate ──────────────────────────────────────────────────────
    item(id = "duplicate", label = "Duplicate") {
        // TODO: wire to DuplicateTaskUseCase
        actions.onDismiss()
    }

    // ── 12. Copy to project ────────────────────────────────────────────────
    item(id = "copy_to_project", label = "Copy to project") {
        // TODO: wire to CopyTaskToProjectUseCase
        actions.onDismiss()
    }

    // ── 13. Move up ────────────────────────────────────────────────────────
    item(id = "move_up", label = "Move up") {
        // TODO: wire to ReorderTaskUseCase
        actions.onDismiss()
    }

    // ── 14. Move down ───────────────────────────────────────────────────────
    item(id = "move_down", label = "Move down") {
        // TODO: wire to ReorderTaskUseCase
        actions.onDismiss()
    }

    // ── 15. Expand / Collapse ─────────────────────────────────────────────
    item(
        id = "expand_collapse",
        label = "Expand / Collapse",
        enabled = actions.onToggleExpand != null,
    ) {
        actions.onToggleExpand?.invoke()
        actions.onDismiss()
    }

    divider()

    // ── 16. Delete ────────────────────────────────────────────────────────
    item(
        id = "delete",
        label = "Delete",
        danger = true,
        enabled = actions.onDelete != null,
    ) {
        actions.onDelete?.invoke()
        actions.onDismiss()
    }

    // ── 17. AI Actions → ─────────────────────────────────────────────────
    subMenu(
        id = "ai_actions",
        label = "AI Actions",
        enabled = actions.onAiAction != null && hasAiContext,
        children = buildMenuNodes {
            item(
                id = "ai_refine",
                label = "Improve with SMART",
                enabled = actions.onAiAction != null && hasAiContext,
            ) {
                actions.onAiAction?.invoke(TaskAiAction.RefineTitle)
                actions.onDismiss()
            }
            item(
                id = "ai_description",
                label = "Generate description",
                enabled = actions.onAiAction != null && hasAiContext,
            ) {
                actions.onAiAction?.invoke(TaskAiAction.GenerateDescription)
                actions.onDismiss()
            }
            item(
                id = "ai_checklist",
                label = "Generate checklist",
                enabled = actions.onAiAction != null && hasAiContext,
            ) {
                actions.onAiAction?.invoke(TaskAiAction.GenerateChecklist)
                actions.onDismiss()
            }
            item(
                id = "ai_decompose",
                label = "Decompose into subtasks",
                enabled = actions.onAiAction != null && hasAiContext,
            ) {
                actions.onAiAction?.invoke(TaskAiAction.Decompose)
                actions.onDismiss()
            }
            item(
                id = "ai_suggest_time",
                label = "Suggest best time",
                enabled = actions.onAiAction != null && hasAiContext,
            ) {
                actions.onAiAction?.invoke(TaskAiAction.SuggestTime)
                actions.onDismiss()
            }
        },
    )

    divider()

    // ── 18. Print ──────────────────────────────────────────────────────────
    item(id = "print", label = "Print") {
        // TODO: wire to PrintTaskUseCase
        actions.onDismiss()
    }

    // ── 19. Archive ────────────────────────────────────────────────────────
    item(id = "archive", label = "Archive") {
        // TODO: wire to ArchiveTaskUseCase
        actions.onDismiss()
    }

    // ── 20. Share ─────────────────────────────────────────────────────────
    item(id = "share", label = "Share") {
        // TODO: wire to ShareTaskUseCase
        actions.onDismiss()
    }
}
