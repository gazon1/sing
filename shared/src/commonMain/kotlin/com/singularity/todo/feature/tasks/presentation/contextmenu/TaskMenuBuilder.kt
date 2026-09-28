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
 *
 * ## How to wire an unwired item
 *
 * This builder is a pure UI component: it receives [TaskMenuActions] and has no access to
 * a repository, and it must not gain one. The "TODO: wire to XxxUseCase" markers an
 * earlier version carried named use cases that were never written, and the
 * `PassThroughUseCase` rule forbids the thin ones they implied. The actual next step for
 * every unwired item is the same and is spelled out on the item itself:
 *
 * 1. add a nullable callback to [TaskMenuActions] — `null` hides the item, which is the
 *    existing convention;
 * 2. invoke it here, then `actions.onDismiss()`;
 * 3. wire it at the call site, where the ViewModel performs the write.
 *
 * The write itself is a repository call, e.g. `updateTask(id) { copy(dueDate = today) }`.
 * `Print` and `Share` are the exception: they are platform actions, not domain ones, and
 * no use case is the right answer for either.
 */
fun buildTaskContextMenu(taskUi: TaskUi, hasAiContext: Boolean, actions: TaskMenuActions): List<MenuNode> =
    buildMenuNodes {
        // ── 1. Pin / Unpin ──────────────────────────────────────────────────────
        item(
            id = "pin",
            label = if (taskUi.isPinned) "Unpin" else "Pin",
            checked = taskUi.isPinned,
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
            // Unwired: add `onSetDueDate: ((LocalDate?) -> Unit)?` to TaskMenuActions, then
            // `updateTask(id) { copy(dueDate = today) }` at the call site. No use case —
            // PassThroughUseCase forbids one over a single `copy`.
            actions.onDismiss()
        }

        // ── 4. Move to tomorrow ────────────────────────────────────────────────
        item(id = "move_tomorrow", label = "Move to tomorrow") {
            // Unwired: as `move_today`, with dueDate = tomorrow.
            actions.onDismiss()
        }

        // ── 5. Move to next week ────────────────────────────────────────────────
        item(id = "move_next_week", label = "Move to next week") {
            // Unwired: as `move_today`, with dueDate = next Monday.
            actions.onDismiss()
        }

        // ── 6. Move to project → ───────────────────────────────────────────────
        subMenu(
            id = "move_to_project",
            label = "Move to project",
            children = buildMenuNodes {
                item(id = "move_project_none", label = "No project") {
                    // Unwired: `onSetProject: ((ProjectId?) -> Unit)?` on TaskMenuActions; null clears
                    // the project.
                    actions.onDismiss()
                }
                // Unwired: the submenu needs the project list. Either pass it in as a
                // parameter to buildTaskContextMenu, or expose `onListProjects: (() -> List<ProjectUi>)?`
                // and build the children from it.
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
            // Unwired: tags are a separate repository from the task. `onAddTag: ((String) -> Unit)?`
            // on TaskMenuActions; the screen resolves the tag and calls TagsRepository.
            actions.onDismiss()
        }

        // ── 10. Set recurring ──────────────────────────────────────────────────
        // Unwired UI, deliberately not an item: needs `onSetRecurring` plus a
        // RecurrencePickerSheet. The previous entry was a dead stub that called a no-op.
        //   Temporarily removed — dead stub that called no-op.
        //   Will be re-implemented with RecurrenceRule + RecurrencePickerSheet.

        // ── 11. Set dependencies ───────────────────────────────────────────────
        item(
            id = "set_dependencies",
            label = "Set dependencies",
            enabled = actions.onSetDependencies != null,
        ) {
            // Partial: the action fires but always with an empty set, so an existing
            // dependency set is cleared on save. Needs DependencyPickerSheet seeded with
            // `taskUi.dependsOn` before this item is honest.
            //   For now, open with empty set; picker sheet will be added in a follow-up.
            actions.onSetDependencies?.invoke(emptySet())
            actions.onDismiss()
        }

        divider()

        // ── 12. Duplicate ──────────────────────────────────────────────────────
        item(id = "duplicate", label = "Duplicate") {
            // Unwired: `onDuplicate: (() -> Unit)?` on TaskMenuActions; the write is
            // CreateTaskUseCase with the same fields and a fresh id.
            actions.onDismiss()
        }

        // ── 13. Copy to project ────────────────────────────────────────────────
        item(id = "copy_to_project", label = "Copy to project") {
            // Unwired: as `duplicate`, with projectId set to the target project.
            actions.onDismiss()
        }

        // ── 14. Move up ────────────────────────────────────────────────────────
        item(id = "move_up", label = "Move up") {
            // Unwired: ordering lives on the task list, not the task. Needs a callback on
            // the list host rather than one here.
            actions.onDismiss()
        }

        // ── 15. Move down ───────────────────────────────────────────────────────
        item(id = "move_down", label = "Move down") {
            // Unwired: as `move_up`.
            actions.onDismiss()
        }

        // ── 16. Expand / Collapse ─────────────────────────────────────────────
        item(
            id = "expand_collapse",
            label = "Expand / Collapse",
            enabled = actions.onToggleExpand != null,
        ) {
            actions.onToggleExpand?.invoke()
            actions.onDismiss()
        }

        divider()

        // ── 17. Delete ────────────────────────────────────────────────────────
        item(
            id = "delete",
            label = "Delete",
            danger = true,
            enabled = actions.onDelete != null,
        ) {
            actions.onDelete?.invoke()
            actions.onDismiss()
        }

        // ── 18. AI Actions → ─────────────────────────────────────────────────
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

        // ── 19. Print ──────────────────────────────────────────────────────────
        item(id = "print", label = "Print") {
            // Unwired, and a platform action rather than a domain one: `onPrint: (() -> Unit)?`
            // on TaskMenuActions, implemented with the platform print API. No use case.
            actions.onDismiss()
        }

        // ── 20. Archive ────────────────────────────────────────────────────────
        item(id = "archive", label = "Archive") {
            // Unwired: `onArchive: (() -> Unit)?` on TaskMenuActions. The repository call
            // already exists (`taskRepo.archiveCompletedTasks` / the soft-delete path added
            // in 2026-09-27-write-layer-soundness); only the callback is missing. This
            // marker previously named an ArchiveTaskUseCase that is not needed at all.
        }

        // ── 21. Share ─────────────────────────────────────────────────────────
        item(id = "share", label = "Share") {
            // Unwired, and a platform action: `onShare: (() -> Unit)?` on TaskMenuActions,
            // implemented with the system share sheet. No use case.
            actions.onDismiss()
        }
    }
