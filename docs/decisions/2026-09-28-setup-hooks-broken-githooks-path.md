---
title: "just setup-hooks указывает на несуществующий .githooks/ — hooks молча отключаются"
date: 2026-09-28
status: accepted
---

# `just setup-hooks` указывает на несуществующий `.githooks/` — hooks молча отключаются

## Context

Обнаружено при Phase 0 (создание worktree для Maestro UI-тестов) плана
`2026-09-28-maestro-ui-flows`.

Рецепт `just setup-hooks` (`justfile:85-114`) вычисляет путь к hooks как
`$REPO_ROOT/.githooks` и записывает его в `core.hooksPath`:

```bash
HOOKS_SOURCE="$REPO_ROOT/.githooks"
git config core.hooksPath "$HOOKS_SOURCE"
```

Каталога `.githooks/` в репозитории **нет**:

```
$ ls -la .githooks/
ls: cannot access '.githooks/': No such file or directory
```

Реальные hooks лежат прямо в `.git/hooks/` и не версионируются:

```
.git/hooks/pre-commit      (679 bytes,  Sep 24 10:16)
.git/hooks/pre-push        (967 bytes,  Sep 24 10:14)
.git/hooks/post-checkout  (1829 bytes,  Sep 24 10:14)
```

Скилл `singularity-todo-worktree-isolation` (секция «Git Hooks Path Convention»)
тоже утверждает, что hooks живут в version-controlled `.githooks/` — то есть
**и рецепт, и документация описывают несуществующее состояние**.

Текущее рабочее состояние: `core.hooksPath` указывает на
`/home/max/AndroidStudioProjects/singularity_cllone_kmp/.git/hooks` — то есть
hooks работают, но только потому, что этот путь был установлен вручную когда-то
(вероятно, до переезда hooks в `.githooks/`, либо hooks вообще никогда не были
перенесены).

## Idea

Варианты:

1. **Создать `.githooks/` и перенести туда hooks** — то, что описано в скилле.
   Плюс: hooks становятся version-controlled, переживают `git clone`.
   Минус: трогает git-инфраструктуру всего репозитория.

2. **Починить рецепт** — читать путь, который реально существует.
   Минус: hooks остаются не версионированными; новый `git clone` без
   ручного `setup-hooks` работает без защиты.

3. **Не трогать сейчас** — задокументировать как известный gap.

## Decision

**Вариант вынесен в open, не реализован в этом MR.**

Phase 0 план Maestro-работ **не запускал** `just setup-hooks` — это сломало бы
hooks, потому что рецепт перенаправил бы `core.hooksPath` в несуществующий
каталог. Git молча игнорирует отсутствующий hooks path, и pre-commit
(compile-проверка) + pre-push (fast test suite + detekt) перестали бы работать
**для всех 9 существующих worktrees** этого репозитория.

Фикс setup-hooks — это отдельная задача, не связанная с Maestro UI-тестами.
Подтверждено эмпирически: hooks в текущем worktree работают
(`post-checkout` отработал при `git worktree add`, что видно по выводу
`=== Post-checkout: cleaning stale build outputs ===`).

## Rationale

Грилл-ревью плана показало общий принцип: **не делать drive-by fix чужой
инфраструктуры в MR про другое**. Правка `justfile` + `worktree-isolation` skill
затронула бы git-воркфлоу всех разработчиков и раздула бы diff.

Особенно опасно «просто починить»: перенаправление `core.hooksPath` на
несуществующий путь — операция, которая **молча** отключает защиту. Ошибка не
бросается, а проявляется позже как «почему коммиты проходят без проверок».

## Consequences

- `just setup-hooks` **нельзя запускать** до отдельного фикса. Это не записано
  нигде явно — стоит добавить в skill-чеатшит.
- Новые worktrees получают hooks только потому, что `core.hooksPath` наследуется
  из общего `.git/config` репозитория. Явная настройка не требуется.
- `gradle/wrapper/gradle-wrapper.jar` в новом worktree присутствует (45 KB) —
  шаг «скопировать wrapper из main checkout» из скилла **не понадобился**.
  Скилл перестраховывается.

## Links

- `justfile:85-114` — рецепт `setup-hooks`
- `.agents/skills/singularity-todo-worktree-isolation/SKILL.md` — секция
  «Git Hooks Path Convention», описывает несуществующее состояние
- Phase 0 плана `2026-09-28-maestro-ui-flows`

## Resolution (accepted)

Resolved 2026-10-05: the recipe is fixed and the existence check is load-bearing.

Verified: `justfile:89-135` (`setup-hooks`) now resolves the main checkout from
`git rev-parse --git-dir`, carries an explicit comment that pointing `core.hooksPath` at a
missing directory does not error but silently runs no hooks, and therefore performs an
existence check before setting it. It also propagates `core.hooksPath` to every registered
worktree, which is the case the ADR identified as silently unhooked. `.githooks/` exists
in this checkout.
