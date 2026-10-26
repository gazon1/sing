# Write-layer soundness: create vs edit

> Reference. Модель записи, зафиксированная ADR
> `docs/decisions/2026-09-27-write-layer-soundness.md` и конкретизированная
> кейсом 015 (`docs/decisions/2026-10-25-task-logbook-note-attachment.md`).

## Два примитива

- **create** — whole-row write (`@Upsert` / `insert` с полным entity). Допустим,
  когда строка создаётся впервые и все колонки известны: аттачменты, FK и
  cross-reference'ы задаются явно в том же вызове. Потеря колонки здесь —
  баг создания, а не редактирования.
- **edit** — read-modify-write или point `UPDATE` по конкретным колонкам.
  Используется для изменения существующей строки. Аттачменты/FK при этом
  сохраняются, потому что строка не перезаписывается целиком.

## Правило

`@Upsert` заменяет **всю** строку. Репозиторий, который редактирует через
`upsert(entity)` с entity, собранным из неполного доменного объекта, молча
сбрасывает колонки, которых в объекте не было (task_id, outgoing_links,
теги-ссылки). Поэтому:

- Новая сущность → create-примитив с полным entity (все FK/attachment'ы).
- Существующая сущность → point `UPDATE` или read-modify-write с сохранением
  незатронутых полей.
- Смешивать нельзя: редактор не должен «создавать заново» при первом сохранении.

## Аудит (2026-10-26)

Проверены все репозитории с `upsert` (20+). Паттерн «partial create теряет
attachment/FK» найден только в `NotesRepositoryImpl` (createWithContent — title/body
без task_id), исправлен через createForTask. Остальные репозитории создают
сущность из полного доменного объекта (Project, Task, Tag, TagGroup, TimeEntry,
AttachmentAnnotation, SavedSearch/AgendaView, Profile), поэтому колонки не теряются.
Риск остаётся только там, где редактор собирает entity вручную из частичного ввода —
это и есть предмет будущего helper'а (пункт 2).

## Чек-лист для ревью
1. Нет ли в редакторе пути «create on open» / «delete on abandon».
2. При первом сохранении аттачмент (task_id, parent_id, outgoing_links) пишется
   в тот же вызов, что и контент.
3. Последующие сохранения — point UPDATE, не `upsert(fullEntity)`.
4. Тест: создание из контекста с аттачментом → attachment выживает после
   первого и последующих сохранений (см. T1/T2 в 015).
