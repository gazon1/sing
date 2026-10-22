package com.singularity.todo.feature.nav.deeplink

import androidx.navigation3.runtime.deeplink.DeepLinkRequest

/**
 * Разрешает входящий запрос в цель. Порядок списка — порядок проверки: первое
 * совпадение выигрывает.
 *
 * Порядок задан явно, а не получен из Koin'овского `getAll()`, потому что
 * «лучшее совпадение» здесь не определено: nav3 сравнивает два `UriMatchResult`
 * по числу аргументов и типу пути, но два матчера с разными authority
 * (`task` и `view`) никогда не совпадут оба — а два с одинаковым authority
 * совпасть могут, и тогда порядок решает. Список делает это решение видимым
 * в коде, а не оставляет его на волю порядка регистрации модулей.
 *
 * Экземпляр — stateless-singleton: читает список, ничего не мутирует, потокобезопасен.
 *
 * @param links упорядоченный список ссылок, которые реестр проверяет по очереди.
 */
class DeepLinkRegistry(private val links: List<AppDeepLink>) {

    /**
     * Возвращает цель для [request] или `null`, если ни одна ссылка не подошла.
     *
     * Неизвестная ссылка — тихий no-op: приложение остаётся на стартовой вкладке.
     */
    fun resolve(request: DeepLinkRequest): DeepLinkTarget? =
        links.firstNotNullOfOrNull { it.match(request) }
}
