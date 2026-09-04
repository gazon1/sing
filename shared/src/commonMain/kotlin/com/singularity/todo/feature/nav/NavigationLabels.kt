package com.singularity.todo.feature.nav

/** Pure mapping helpers — no Compose runtime, no Android, no JVM. */

fun NavDestination.topBarTitle(): String = title

fun NavGroup.label(): String = when (this) {
    NavGroup.Work -> "Work"
    NavGroup.Knowledge -> "Knowledge"
    NavGroup.Insights -> "Insights"
}
