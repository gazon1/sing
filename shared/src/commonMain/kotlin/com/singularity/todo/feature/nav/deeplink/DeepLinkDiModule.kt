package com.singularity.todo.feature.nav.deeplink

import com.singularity.todo.feature.agenda.presentation.nav.AgendaDeepLink
import com.singularity.todo.feature.auth.AuthDeepLink
import com.singularity.todo.feature.tasks.presentation.nav.TaskDeepLink
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * DI-привязки реестра deep-link'ов.
 *
 * Реестр собирается **явным упорядоченным списком**, а не через `getAll()`:
 * порядок проверки матчеров должен быть виден в коде, а не зависеть от порядка
 * регистрации модулей. Один `single` вместо multi-binding — потому что
 * `koin-compiler-plugin` проверяет граф на компиляции и ругается на
 * неоднозначные multi-binding'и.
 *
 * Сами ссылки зарегистрированы в DI-модулях своих фич:
 * [TaskDeepLink] — в `tasksModule()`, [AgendaDeepLink] — в `agendaModule()`,
 * [AuthDeepLink] — рядом с остальными auth-привязками.
 */
fun deepLinkModule(): Module = module {
    single {
        DeepLinkRegistry(
            listOf(
                get<TaskDeepLink>(),
                get<AgendaDeepLink>(),
                get<AuthDeepLink>(),
            ),
        )
    }
}
