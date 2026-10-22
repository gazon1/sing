package com.singularity.todo.feature.agenda

/**
 * Шаблон и разбор ссылки на сохранённое представление agenda —
 * единственный источник правды о формате.
 *
 * Ссылка приходит из уведомления о напоминании (см.
 * [com.singularity.todo.core.notifications.AndroidNotifier]) и открывает
 * редактор представления.
 */
object AgendaLinkSpec {

    /** Схема всех ссылок приложения. */
    const val SCHEME = "singularity"

    /** Authority ссылки на сохранённое представление. */
    const val HOST = "view"

    /** URI-шаблон: единственный path-сегмент — идентификатор представления. */
    const val URI = "$SCHEME://$HOST/{viewId}"

    /** Начало ссылки — маркер, по которому она ищется в свободном тексте. */
    const val MARKER = "$SCHEME://$HOST/"

    /**
     * Ссылка на сохранённое представление.
     *
     * @param viewId идентификатор представления.
     */
    fun uri(viewId: SavedAgendaViewId): String = "$MARKER${viewId.raw}"

    /**
     * Обратный разбор ссылки из произвольного текста.
     *
     * @param text текст, в котором может быть ссылка.
     * @return идентификатор представления или `null`, если ссылки в тексте нет.
     */
    fun viewIdFromUri(text: String): SavedAgendaViewId? {
        val start = text.indexOf(MARKER)
        if (start < 0) return null
        val id = text.substring(start + MARKER.length)
            .takeWhile { it.isLetterOrDigit() || it == '-' }
        return id.takeIf { it.isNotBlank() }?.let { SavedAgendaViewId.fromString(it) }
    }
}
