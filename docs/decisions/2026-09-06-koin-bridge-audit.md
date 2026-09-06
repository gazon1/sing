---
title: "Koin bridge audit: all usages correct, no raw runBlocking in module blocks"
date: 2026-09-06
tags: [koin, di, coroutines]
---

## Context

После миграции ViewModel-DI (2026-09-06) провёл аудит `koinBridge` и `runBlocking` usage в DI-графе.

## Idea

Проверить:
1. Нет raw `runBlocking` внутри `module {}` блоков
2. Все suspend-вызовы обёрнуты в `koinBridge`
3. Нет `GlobalScope.launch` внутри factories

## Decision

**Все usages `koinBridge` корректны:**

| Файл | Usage | Корректность |
|---|---|---|
| `PlatformModule.android.kt:59` | `koinBridge { AiApiKeyMigration.run(...) }` | ✅ one-shot startup read |
| `AiToolsModule.jvm.kt:32` | `koinBridge { createKoogPromptExecutor(...) }` | ✅ one-shot startup read |
| `AiToolsModule.android.kt:30` | `koinBridge { ... }` | ✅ one-shot startup read |

**Raw `runBlocking` вне `module {}`:** `KoinBridge.kt:17` определяет helper через `runBlocking` — это ОК, это helper, не использование внутри модуля.

**Raw `runBlocking` внутри `module {}`:** не найдено (проверено grep по Modules.kt)

**`GlobalScope.launch`:** не найдено в Modules.kt

**`singleOf` для репозиториев:** не применимо — сложные объекты (`SyncEngine`, `LoggerHolder`) требуют `single { Obj(get(), ...) }` форму.

## Consequences

- **Правило подтверждено:** `koinBridge` только для one-shot startup suspend reads
- **Raw `runBlocking` в модулях** — не допускается, `koinBridge` как единая точка входа
- **`singleOf` для репозиториев** — architectural limitation; сложные конструкторы не поддерживают constructor-reference форму
