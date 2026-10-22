package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Шаблон и разбор ссылки на задачу — единственный источник правды о формате.
 *
 * И календарная синхронизация (производитель), и матчер (потребитель) берут его
 * отсюда. Формат жил в трёх местах: `CalendarSyncEvent.deepLink`,
 * `GoogleEventJson.DEEP_LINK_PREFIX` и `AndroidCalendarProvider.extractTaskId` —
 * два из них были мёртвыми копиями, а третий разбирал строку вручную. Правка
 * одного из трёх оставляла два других в старом формате, и ссылка, записанная
 * старым производителем, переставала открываться новым матчером.
 */
object TaskLinkSpec {

    /** Схема всех ссылок приложения. */
    const val SCHEME = "singularity"

    /** Authority ссылки на задачу. */
    const val HOST = "task"

    /** URI-шаблон: единственный path-сегмент — идентификатор задачи. */
    const val URI = "$SCHEME://$HOST/{taskId}"

    /** Начало ссылки — маркер, по которому она ищется в свободном тексте. */
    const val MARKER = "$SCHEME://$HOST/"

    /**
     * Ссылка на задачу — то, что пишется в описание календарного события.
     *
     * @param taskId идентификатор задачи.
     */
    fun uri(taskId: TaskId): String = "$MARKER${taskId.value}"

    /**
     * Обратный разбор ссылки из произвольного текста.
     *
     * Текст, а не URI: описание календарного события — это свободный текст, в
     * котором ссылка стоит последним абзацем. Ссылка ищется по [MARKER], а
     * идентификатор читается до первого символа, который не может входить в id.
     *
     * @param text текст, в котором может быть ссылка.
     * @return идентификатор задачи или `null`, если ссылки в тексте нет.
     */
    fun taskIdFromUri(text: String): TaskId? {
        val start = text.indexOf(MARKER)
        if (start < 0) return null
        val id = text.substring(start + MARKER.length)
            .takeWhile { it.isLetterOrDigit() || it == '-' }
        return id.takeIf { it.isNotBlank() }?.let { TaskId(it) }
    }
}
