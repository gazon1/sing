package com.singularity.todo.feature.nav.deeplink

import androidx.navigation3.runtime.deeplink.DeepLinkRequest

/**
 * Одна распознаваемая ссылка, объявленная фичей-владельцем.
 *
 * Фича владеет и шаблоном URI, и маппингом ссылки в экран: добавить ссылку —
 * значит добавить реализацию здесь и регистрацию в DI-модуле фичи. Реестр в
 * [com.singularity.todo.feature.nav.deeplink.DeepLinkRegistry] ничего не знает
 * о конкретных фичах — он только перебирает список.
 */
interface AppDeepLink {

    /** Стабильный идентификатор для логов и тестов (например, `task`). */
    val id: String

    /**
     * Возвращает цель, если запрос соответствует шаблону этой ссылки.
     *
     * Не бросает: битая ссылка — это `null`, а не исключение. Приложение открывает
     * ссылку из внешнего источника, и падение на чужом вводе — это падение.
     */
    fun match(request: DeepLinkRequest): DeepLinkTarget?
}
