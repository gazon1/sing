# Локальный стек Supabase — как поднять и применить миграции

Две вещи в этом репозитории живут на сервере: политики бакета `backups` и таблица
`google_calendar_grants`. Обе приходят миграциями, и до сегодня некуда было их
применить и посмотреть на результат — только «прочитать SQL и сказать, что он
выглядит правильно».

Этот документ — рецепт, который поднимает стек, применяет миграции этого
репозитория и даёт проверить результат на живой базе.

## Что нужно

- Docker с Compose.
- Клон инфраструктурного репозитория: `gazon1/supabase-repo-sing`. Там compose,
  `.env.example` и генератор ключей.
- Этот репозиторий — ради миграций в `supabase/migrations/`.

## Подъём

```bash
git clone https://github.com/gazon1/supabase-repo-sing.git
cd supabase-repo-sing

cp .env.example .env
# Заполнить значения, помеченные "← CHANGE THIS".
# Ключи можно сгенерировать: bash scripts/generate-keys.sh

# Файлы инициализации базы (роли, jwt, пулер) — из upstream supabase:
curl -sL https://github.com/supabase/supabase/archive/refs/heads/master.tar.gz \
  | tar -xz --strip=4 \
    supabase-master/docker/volumes/db \
    -C volumes/db

chmod +x entrypoint.sh
```

Дальше — только те сервисы, которые нужны для проверки. Полный стек тянет
studio, logflare, realtime и vector, а здесь они не используются:

```bash
docker compose up -d db auth rest storage minio minio-createbucket kong
```

### Два отличия от upstream-compose

Файл `supabase/local-stack/docker-compose.override.yaml` в этом репозитории — это
тот же override, который лежит в инфраструктурном репозитории
(`gazon1/supabase-repo-sing`, коммит `d3e0907`). Скопируйте его рядом с
`docker-compose.yaml` — Compose подхватит его сам:

```bash
cp <путь-к-этому-репозиторию>/supabase/local-stack/docker-compose.override.yaml .
```

Что он чинит:

1. **Образ инициализатора бакета.** Закреплённый тег `minio/mc` больше не
   существует на Docker Hub — репозиторий там удалён, — и инициализатор не
   стартовал. Теперь используется образ самого minio-сервера: он содержит тот же
   самый бинарник `mc`.
2. **Регион storage-бэкенда.** `GLOBAL_S3_REGION` не задан, и без него бэкенд
   отвечает «not-specified», который S3-бэкенд отклоняет со «Region is missing»
   — на каждом удалении объекта, а загрузка с `upsert` удаляет старый объект
   перед записью нового. Бэкенд читает регион из `SERVER_REGION` / `REGION`, и
   override задаёт оба.

## Миграции

Миграции лежат в **этом** репозитории, а не в стеке, поэтому команда выполняется
из каталога этого репозитория (или с абсолютным путём):

```bash
for sql in supabase/migrations/*.sql; do
  echo "--- $sql"
  docker exec -i supabase-db psql -U postgres -d postgres -q < "$sql"
done
```

Миграции этого репозитория идут поимённо, в этом порядке:

| Файл | Что делает |
|---|---|
| `2026-10-07-sync_schema.sql` | схема синхронизации, функции RPC |
| `2026-10-24-backup_storage_bucket.sql` | бакет `backups` и его политики RLS |
| `2026-10-24-google_calendar_grants.sql` | таблица грантов Google Calendar |

`supabase/sync_field_allowlist_seed.sql` — описание, а не миграция: он
перечисляет имена полей, которые сервер принимает в патче, и лежит вне
`supabase/migrations/` именно потому, что это описание, а не изменение
живой таблицы. Живую таблицу наполняет `2026-10-07-sync_schema.sql`.

## Как проверить, что применилось

Политики бакета — проверка последнего сегмента пути (см. ADR про каталоги):

```bash
docker exec supabase-db psql -U postgres -d postgres -tAc \
  "select policyname, cmd, with_check from pg_policies
   where tablename='objects' and schemaname='storage';"
```

Гранты Google:

```bash
docker exec supabase-db psql -U postgres -d postgres -tAc \
  "select count(*) from information_schema.tables
   where table_name='google_calendar_grants';"
```

## Приложение — на этот стек

В `local.properties` (файл в `.gitignore`, значения не коммитятся):

```properties
supabase.url=http://<хост>:8000
supabase.anonKey=<ключ из .env стека>
```

Ключ — из `.env` стека (`ANON_KEY`), **не** service-role: с anon-ключом
политики RLS отвечают так же, как на живом стеке, и проверка изоляции бакета
двумя пользователями получается настоящей.

## Остановка

```bash
docker compose down
```

Тома остаются: `-v` удаляет и данные, а база нужна следующему прогону.
