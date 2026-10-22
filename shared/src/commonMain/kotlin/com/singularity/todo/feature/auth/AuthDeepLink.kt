package com.singularity.todo.feature.auth

import androidx.navigation3.runtime.deeplink.DeepLinkRequest
import androidx.navigation3.runtime.deeplink.DeepLinkUri
import androidx.navigation3.runtime.deeplink.UriDeepLinkMatcher
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.deeplink.AppDeepLink
import com.singularity.todo.feature.nav.deeplink.DeepLinkTarget
import kotlinx.serialization.Serializable

/**
 * Ключ, который матчер собирает из query-параметров ссылки.
 *
 * Имена свойств обязаны совпадать с плейсхолдерами шаблона
 * ([AuthLinkSpec.URI]): официальный матчер подставляет извлечённые значения по
 * именам.
 */
@Serializable
private data class AuthLinkKey(val email: String, val token: String)

/**
 * Magic-link: `singularity://verify?email={email}&token={token}`.
 *
 * Открывает экран подтверждения адреса на вкладке «Сегодня». Токен
 * авто-отправляется на экране — [com.singularity.todo.feature.auth.presentation.MagicLinkConfirmScreen].
 */
class AuthDeepLink : AppDeepLink {

    override val id: String = "verify"

    private val matcher = UriDeepLinkMatcher(
        uriPattern = DeepLinkUri(AuthLinkSpec.URI),
        serializer = AuthLinkKey.serializer(),
    )

    override fun match(request: DeepLinkRequest): DeepLinkTarget? {
        val hit = matcher.match(request) ?: return null
        return DeepLinkTarget(
            tab = AppDestination.AgendaGraph(AgendaStartRoute.Today),
            screen = AppDestination.MagicLinkConfirm(
                email = hit.key.email,
                token = hit.key.token,
            ),
        )
    }
}
