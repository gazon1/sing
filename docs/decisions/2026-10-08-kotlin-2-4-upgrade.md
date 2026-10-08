---
title: "Kotlin 2.4 upgrade: 2.3.21 → 2.4.10, KSP 2.3.12, detekt 2.0.0-alpha.6"
date: 2026-10-08
tags: [kotlin, build, koin, detekt]
---

## Context

Проект сидел на Kotlin 2.3.21. Три ограничения накопились:

1. **Burst 2.13.0** требует Kotlin 2.4.0 (`docs/decisions/2026-09-25-test-helper-stack.md:37`).
2. **KMPAuth (alpha)** собран на Kotlin 2.4.0; компиляция «назад» работает, но риск растёт.
3. **koin-compiler-plugin 1.2.1** печатал предупреждение «Kotlin 2.3.21 не поддержан, идём на адаптере 2.3.20» (`2026-10-02-koin-compiler-plugin-dsl-validation.md:118`).

Обновление кажется механическим, но обнаружило системный дрейф: версия Kotlin зашита в 4 местах независимо (каталог, gradle.properties, 4 модуля с жёстким пинном Koin compiler), и ни один гейт этот дрейф не ловит.

## Idea

Три варианта:

- **A. Только Kotlin 2.4.20.** Идеальная цель, но detekt alpha.6 скомпилирован с Kotlin 2.4.10 и требует точного совпадения встроенного компилятора. Ошибка: `detekt was compiled with Kotlin 2.4.10 but is currently running with 2.4.20`.
- **B. Kotlin 2.4.0.** Ровно совпадает с `strictly 2.4.0` на buildscript classpath. Но это минорный апгрейд; Burst и KMPAuth разблокируются, Koin warning снимается. KSP 2.3.12 поддерживает 2.4.0+.
- **C. Kotlin 2.4.10.** Компромисс: Burst разблокирован (2.4.0+), KMPAuth риск снижен, Koin warning снят. KSP 2.3.12 поддерживает KGP 2.4.x. Detekt alpha.6 совпадает точно.

## Decision

**Kotlin 2.4.10, KSP 2.3.12, detekt 2.0.0-alpha.6.** Выбор пал на 2.4.10, а не 2.4.20, потому что detekt alpha.6 скомпилирован именно с 2.4.10 и несовместим с 2.4.20. Это не частичный откат — 2.4.10 всё ещё значительный апгрейд с 2.3.21, полностью снимающий все три ограничения из Context.

Одновременно устранён класс дрейфа версий:

- **B1:** пин `io.insert-koin.compiler.plugin" version "1.2.1"` заменён на `alias(libs.plugins.koin.compiler)` в 4 модулях. Алиас каталога `koin-compiler` уже существовал; комментарий «catalog accessor fails for hyphenated plugin IDs» был фактически неверен — идентификатор содержит точки, но не дефисы.
- **B2:** `scripts/build-version-catalog-gate.py` расширен двумя проверками: (a) `gradle.properties version.*` ↔ каталог, (b) plugin-литералы в `build.gradle.kts plugins {}` ↔ каталог.
- **B3:** `detekt-api`, `detekt-test`, `detekt-test-utils` перенесены в каталог с `version.ref = "detekt"`; `detekt-rules/build.gradle.kts` использует `libs.detekt.api`, `libs.detekt.test`, `libs.detekt.test.utils`.

## Rationale

Kotlin 2.4.10 выбран над 2.4.20 по единственной технической причине: detekt alpha.6 несовместим с 2.4.20. Это не субъективное предпочтение, а объективное ограничение, обнаруженное на Step 0. Альтернатива — ждать detekt alpha.7 (или стабильного 2.4.x) — откладывает апгрейд без выигрыша.

KSP 2.3.12 — единственный релиз с поддержкой KGP 2.4.x (2.3.11 поддерживает до 2.3.x).

B1/B2/B3 делались параллельно с апгрейдом, потому что без них любой будущий bump версии Kotlin потребовал бы снова править 8 мест вручную, а гейт это не видел. Это не «попутная чистка» — это защита от регрессии того же класса.

## Consequences

- **Kotlin 2.4.10** — новая базовая версия. Все модули `:shared`, `:desktopApp`, `:mcp-server`, `:detekt-rules`, `:tools:unwritten-properties` собираются KGP из каталога. `:androidApp` и `:pro` используют встроенный Kotlin из AGP 9.4.1 — от каталога не зависят.
- **Room 3.0.0, Compose MP 1.12.0, Kover 0.9.9, AGP 9.4.1, Gradle 9.7.1** — не подняты. Решение отложено; причины: дифф должен оставаться минимальным и обратимым.
- **koin-compiler-plugin 1.2.1** — версия не изменилась, но теперь движется вместе с Kotlin через каталог. Старое предупреждение «Kotlin 2.3.21 не поддержан» снято.
- **Gradle 9.7.1** — KGP 2.4.x печатает предупреждение о непроверенной комбинации. Это известное состояние, зафиксированное в плане; отдельного решения не требует.
- **detekt 2.0.0-alpha.6** принёс 2 новые находки: `LongMethod` в `ProjectDetailSheetsHost` (84 строки vs лимит 80 — подсчёт изменился в alpha.6; добавлен `@Suppress`) и `PropertyName` SCREAMING_SNAKE_CASE для константы в тесте.
- **B1/B2/B3** — гейт `build-version-catalog-gate.py` теперь отлавливает расхождения version.kotlin ↔ каталог, koin-compiler-plugin версию в `build.gradle.kts`, и detekt-API версии в `detekt-rules`. Обновление Kotlin в будущем = одна правка в каталоге.

## Links

- Файл: `gradle/libs.versions.toml`, `gradle.properties`, `build.gradle.kts`, `shared/build.gradle.kts`, `androidApp/build.gradle.kts`, `desktopApp/build.gradle.kts`, `detekt-rules/build.gradle.kts`
- Скрипт: `scripts/build-version-catalog-gate.py`
- ADR: `2026-09-25-test-helper-stack.md`, `2026-10-02-koin-compiler-plugin-dsl-validation.md`, `2026-10-05-google-oauth-hybrid-kmpauth-plus-own-token-exchange.md`
