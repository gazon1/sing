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

`sync_field_allowlist_seed.sql` — сид, а не схема: он наполняет allow-list
полей, и его применение нужно только для полной проверки синхронизации.

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

## Проверка потоковой загрузки в бакет

Политики можно прочитать и проверить SQL-ом выше, но это проверка *правил*, а не
пути загрузки. «Архив доезжает в настоящий бакет и возвращается из него
байт-в-байт» — свойство кода (`SupabaseBackupBucket.upload`/`download`), и его
надо проверять живой пробой, а не чтением.

Проба — временный `jvmTest`-класс, который берёт реальный `SupabaseBackupBucket`,
создаёт пользователя через admin API, входит и гоняет upload → download → list →
попытку записи в чужой префикс. Три вещи, которые в ней легко сделать неправильно:

1. **Ключи передаются через окружение, а не `-D`.** `-D` на командной строке
   Gradle настраивает демон, а не форкнутый тестовый JVM; форк наследует
   переменные окружения, но не эти свойства. Проба читает
   `PROBE_SUPABASE_URL` / `PROBE_SUPABASE_ANON` / `PROBE_SUPABASE_SERVICE_ROLE`.
2. **Прогон — с `-Ptest.tags=all`** (иначе класс с тегом `slow` исключён) и с
   `--tests` на один класс, чтобы не гонять весь набор.
3. **Класс удаляется после прогона.** Он требует живого стека, поэтому в CI его
   не выполнить: оставить его в дереве — значит завести `slow`-тест, который
   `check-slow-tests-ran.py` требует, а CI выполнить не может.

```bash
cd /tmp/supa-stack && set -a && . ./.env && set +a
cd <этот-репозиторий>
PROBE_SUPABASE_URL="$SUPABASE_PUBLIC_URL" \
PROBE_SUPABASE_ANON="$ANON_KEY" \
PROBE_SUPABASE_SERVICE_ROLE="$SERVICE_ROLE_KEY" \
TMPDIR=/home/max/.cache/escolar-tmp \
./gw :shared:jvmTest -Ptest.tags=all \
  --tests "com.singularity.todo.core.backup.LiveStorageProbeTest"
```

Зелёный прогон: `tests="1" skipped="0" failures="0" errors="0"` в
`shared/build/test-results/jvmTest/TEST-...LiveStorageProbeTest.xml`.

Мелкие грабли стека:

- Kong после жёсткой остановки (`Exited (137)`) поднимается с «nginx is already
  running»: внутри контейнера остаётся чужой pid. Лечится
  `docker compose up -d --force-recreate kong`.
- `storage` показывается `(unhealthy)` при живом и отвечающем API — это
  healthcheck-проба, а не отказ; проба на него не полагается.

## Остановка

```bash
docker compose down
```

Тома остаются: `-v` удаляет и данные, а база нужна следующему прогону.
