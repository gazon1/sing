package com.singularity.todo.feature.tasks

/**
 * Which "tab" the Tasks screen was opened from. Drives the default filter
 * and the suggested initial due date for new tasks.
 *
 * Lives in `feature/tasks` because [TasksScreen] is the only consumer;
 * [com.singularity.todo.shell.AppNavHost] uses it as a small data type.
 */
sealed interface TasksScreenEntry {
    data object FromToday : TasksScreenEntry
    data object FromInbox : TasksScreenEntry
}
