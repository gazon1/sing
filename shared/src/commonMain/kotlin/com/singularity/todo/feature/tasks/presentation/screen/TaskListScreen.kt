package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.presentation.components.list.EmptyState
import com.singularity.todo.feature.tasks.presentation.components.list.SwipeableTaskRow
import com.singularity.todo.feature.tasks.presentation.components.list.TaskFilterChips
import com.singularity.todo.feature.tasks.presentation.components.list.TaskListHeader
import com.singularity.todo.feature.tasks.presentation.components.list.TaskRowFlat
import com.singularity.todo.feature.tasks.presentation.model.TaskListFilter
import com.singularity.todo.feature.tasks.presentation.model.TaskListStats
import com.singularity.todo.feature.tasks.presentation.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListShapes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Визуальный стиль строки задачи — переключаемый на уровне экрана, не строки. */

/**
 * Экран списка задач.
 *
 * Разбит на маленькие композаблы ([TaskListHeader], [TaskRowFlat],
 * [SwipeableTaskRow], [EmptyState], [TaskFilterChips]) — сам экран отвечает
 * только за оркестрацию: state, callbacks, выбор стиля, обработку empty/snackbar.
 *
 * Это соответствует принципу "экран — оркестратор, а не место для вёрстки одной
 * строки": вся визуальная логика изолирована в компонентах, экран не знает,
 * как именно они рисуются, только когда и какие.
 *
 * @param initialFilter какой фильтр активен при первом запуске
 * @param onAddTask колбэк нажатия на FAB
 * @param onTaskClick колбэк клика по строке (открытие деталей)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    initialFilter: TaskListFilter = TaskListFilter.ALL,
    onAddTask: () -> Unit = {},
    onTaskClick: (TaskUi) -> Unit = {},
) {
    val tasks = remember {
        mutableStateListOf(
            TaskUi(1, "Позвонить родителям в сб или вс", "Семья", "Сб, 05 сент 2026", isRecurring = true, isOverdue = true),
            TaskUi(2, "Написать пост в блог про ev framework", "Блог github pages", "Пн, 12 янв 2026", isRecurring = true, priority = TaskPriority.MEDIUM),
            TaskUi(3, "Написать заметки по статьям", null, "Пн, 10 нояб 2025", isCompleted = true),
            TaskUi(4, "Отправить заявку на баллы фитмост от гпб", "Финансы", "Пн, 18 мая 2026", isRecurring = true, priority = TaskPriority.HIGH),
            TaskUi(5, "Помыть туалет и пол там", "Квартира", "Ср, 22 июл 2026", isRecurring = true),
            TaskUi(6, "Заказать сок, еду для готовки. Регулярно", "Квартира", "Пн, 20 июл 2026", isRecurring = true),
            TaskUi(7, "Постирать постельное. Регулярно", "Квартира", "Пт, 04 сент 2026", isRecurring = true),
        )
    }

    var filter by remember { mutableStateOf(initialFilter) }
    var isRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()

    val stats by remember(tasks) { derivedStateOf { TaskListStats.from(tasks) } }

    val visibleTasks by remember(tasks, filter) {
        derivedStateOf {
            when (filter) {
                TaskListFilter.ALL -> tasks
                TaskListFilter.ACTIVE -> tasks.filter { !it.isCompleted }
                TaskListFilter.COMPLETED -> tasks.filter { it.isCompleted }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = TaskListColors.Background,
        contentColor = TaskListColors.TextPrimary,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddTask,
                containerColor = TaskListColors.Accent,
                contentColor = TaskListColors.OnAccent,
                shape = TaskListShapes.FabRadius,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Новая задача") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TaskListHeader(
                title = "Сегодня",
                taskCount = stats.active,
                onCalendarClick = {},
                onMoreClick = {},
                subtitle = "11 сентября 2026 · Чт",
            )

            TaskFilterChips(
                selected = filter,
                onSelect = { filter = it },
                counts = mapOf(
                    TaskListFilter.ALL to stats.total,
                    TaskListFilter.ACTIVE to stats.active,
                    TaskListFilter.COMPLETED to stats.completed,
                ),
            )

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    scope.launch {
                        isRefreshing = true
                        delay(1200.milliseconds)
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier.align(Alignment.TopCenter),
                        isRefreshing = isRefreshing,
                        state = pullToRefreshState,
                        containerColor = TaskListColors.Surface,
                        color = TaskListColors.Accent,
                    )
                },
            ) {
                if (visibleTasks.isEmpty()) {
                    val isFilterActive = filter != TaskListFilter.ALL
                    EmptyState(
                        title = if (isFilterActive) "Здесь пусто" else "Задач пока нет",
                        description = if (isFilterActive) {
                            "В выбранном фильтре задач нет. Попробуйте «Все»."
                        } else {
                            "Нажмите «Новая задача» внизу, чтобы добавить первую."
                        },
                        icon = if (isFilterActive) Icons.Default.CheckCircle else Icons.Default.Add,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal =  TaskListSpacing.None,
                            vertical = TaskListSpacing.Sm,
                        ),
                        verticalArrangement =
                            Arrangement.Top
                    ) {
                        items(visibleTasks, key = { it.id }) { task ->
                            SwipeableTaskRow(
                                onDelete = {
                                    val index = tasks.indexOf(task)
                                    if (index != -1) {
                                        tasks.removeAt(index)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Задача удалена",
                                                actionLabel = "Отменить",
                                                withDismissAction = true,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                tasks.add(index, task)
                                            }
                                        }
                                    }
                                },
                                backgroundShape =
                                    RoundedCornerShape(0.dp)
                            ) {
                                    TaskRowFlat(
                                        task = task,
                                        onToggleCompleted = { tasks.toggleCompleted(task) },
                                        onClick = { onTaskClick(task) },
                                        // последняя видимая строка — без разделителя,
                                        // он и так пойдёт за пределы списка
                                        showDivider = task != visibleTasks.last(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }


/** Точечно переключает isCompleted у задачи, сохраняя стабильность key для LazyColumn. */
private fun SnapshotStateList<TaskUi>.toggleCompleted(task: TaskUi) {
    val index = indexOf(task)
    if (index != -1) this[index] = task.copy(isCompleted = !task.isCompleted)
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E14, heightDp = 720)
@Composable
private fun TaskListScreenFlatPreview() {
    MaterialTheme {
        TaskListScreen()
    }
}



@Preview(showBackground = true, backgroundColor = 0xFF0B0E14, heightDp = 720)
@Composable
private fun TaskListScreenEmptyPreview() {
    MaterialTheme {
        TaskListScreen( initialFilter = TaskListFilter.COMPLETED)
    }
}
