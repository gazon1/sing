package com.singularity.todo.feature.agenda.presentation.nav

import androidx.navigation3.runtime.deeplink.DeepLinkRequest
import androidx.navigation3.runtime.deeplink.DeepLinkUri
import androidx.navigation3.runtime.deeplink.UriDeepLinkMatcher
import com.singularity.todo.feature.agenda.AgendaLinkSpec
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.deeplink.AppDeepLink
import com.singularity.todo.feature.nav.deeplink.DeepLinkTarget
import kotlinx.serialization.Serializable

/**
 * Ключ, который матчер собирает из аргументов ссылки.
 *
 * Имя свойства обязано совпадать с плейсхолдером шаблона
 * ([AgendaLinkSpec.URI]) — официальный матчер подставляет значения по именам.
 */
@Serializable
private data class AgendaLinkKey(val viewId: String)

/**
 * Ссылка на сохранённое представление agenda: `singularity://view/{viewId}`.
 *
 * Открывает редактор представления на вкладке «Сегодня».
 */
class AgendaDeepLink : AppDeepLink {

    override val id: String = "view"

    private val matcher = UriDeepLinkMatcher(
        uriPattern = DeepLinkUri(AgendaLinkSpec.URI),
        serializer = AgendaLinkKey.serializer(),
    )

    override fun match(request: DeepLinkRequest): DeepLinkTarget? {
        val hit = matcher.match(request) ?: return null
        return DeepLinkTarget(
            tab = AppDestination.AgendaGraph(AgendaStartRoute.Today),
            screen = AppDestination.AgendaGraph(
                AgendaStartRoute.SavedAgendaEdit(SavedAgendaViewId.fromString(hit.key.viewId)),
            ),
        )
    }
}
