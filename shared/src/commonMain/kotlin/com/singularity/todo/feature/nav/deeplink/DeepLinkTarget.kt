package com.singularity.todo.feature.nav.deeplink

import com.singularity.todo.feature.nav.AppNavKey

/**
 * Куда приводит распознанная ссылка: экран [screen] открывается на вкладке [tab].
 *
 * Модель намеренно двухпольная: app-level ключи приложения — всегда «граф-обёртки»,
 * поэтому синтетический back stack — ровно [tab, screen]. Список ввёл бы невалидные
 * состояния (стек без корня, корень ≠ вкладка) без новых возможностей.
 *
 * @param tab верхнеуровневый маршрут — вкладка, на которой открывается [screen].
 * @param screen маршрут, который открывается по ссылке.
 */
data class DeepLinkTarget(val tab: AppNavKey, val screen: AppNavKey)
