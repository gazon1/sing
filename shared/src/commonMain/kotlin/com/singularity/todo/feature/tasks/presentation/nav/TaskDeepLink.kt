package com.singularity.todo.feature.tasks.presentation.nav

import androidx.navigation3.runtime.deeplink.DeepLinkRequest
import androidx.navigation3.runtime.deeplink.DeepLinkUri
import androidx.navigation3.runtime.deeplink.UriDeepLinkMatcher
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.deeplink.AppDeepLink
import com.singularity.todo.feature.nav.deeplink.DeepLinkTarget
import com.singularity.todo.feature.tasks.domain.TaskLinkSpec
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.serialization.Serializable

/**
 * Ключ, который матчер собирает из аргументов ссылки.
 *
 * Имена свойств обязаны совпадать с плейсхолдерами шаблона
 * ([TaskLinkSpec.URI]): официальный матчер подставляет извлечённые значения по
 * именам. Поле — `String`, а не [TaskId]: типизированный идентификатор
 * создаётся на границе, в [TaskDeepLink].
 */
@Serializable
private data class TaskLinkKey(val taskId: String)

/**
 * Ссылка на задачу: `singularity://task/{taskId}`.
 *
 * Ссылка открывает карточку задачи на вкладке «Сегодня»: app-level ключи
 * приложения всегда «граф-обёртки», поэтому вкладка задаётся явно, а «назад»
 * возвращает на Today.
 */
class TaskDeepLink : AppDeepLink {

    override val id: String = "task"

    private val matcher = UriDeepLinkMatcher(
        uriPattern = DeepLinkUri(TaskLinkSpec.URI),
        serializer = TaskLinkKey.serializer(),
    )

    override fun match(request: DeepLinkRequest): DeepLinkTarget? {
        val hit = matcher.match(request) ?: return null
        return DeepLinkTarget(
            tab = AppDestination.AgendaGraph(AgendaStartRoute.Today),
            screen = AppDestination.TasksGraph(
                AppDestination.TasksStartRoute.Detail(TaskId(hit.key.taskId)),
            ),
        )
    }
}
