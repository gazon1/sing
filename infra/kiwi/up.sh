#!/usr/bin/env bash
# =============================================================================
# up.sh — поднять Kiwi TCMS и довести его до рабочего состояния
# =============================================================================
# Идемпотентен: можно запускать повторно. Инициализация БД (migrations +
# superuser) выполняется только если в базе ещё нет ни одного пользователя.
#
#   ./infra/kiwi/up.sh                     # поднять и дождаться готовности
#   ./infra/kiwi/up.sh --detach            # поднять в фоне, сразу вернуться
#   ./infra/kiwi/up.sh --wait-only         # не стартовать, только дождаться
#   ./infra/kiwi/up.sh --recreate-admin    # снести и пересоздать admin
#
# Почему нельзя просто `docker compose up -d`: Kiwi не выполняет миграции сам.
# При пустой БД контейнер стартует, отдаёт 500 на / и просит зайти на
# /init-db/ и нажать кнопку — то есть требует ручного шага в браузере.
# Скрипт делает этот шаг через `manage.py init_db` + `createsuperuser`.
#
# --detach НЕ инициализирует БД: он только запускает контейнеры и выходит.
# На холодном старте initdb Postgres занимает ~2 минуты, поэтому «поднять в
# фоне и уйти» не может означать «и БД готова». Дальше — `up.sh --wait-only`
# (или `just kiwi-wait`), который дождётся HTTP и догонит инициализацию.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$HERE/docker-compose.yml"
# container_name в docker-compose.yml. Задаётся здесь явно, а не выводится
# из compose: расхождение между этим именем и container_name даёт
# `docker exec: No such container` уже после того, как стенд поднялся.
KIWI_WEB=singularity-kiwi-web
# export обязателен: `docker exec -e VAR` берёт значение из окружения
# клиентского процесса, а не из обычных shell-переменных. Без export
# переменная не экспортирована, -e передаёт пустое значение, и Python внутри
# контейнера падает с KeyError — после того, как стенд уже поднят.
export KIWI_ADMIN_USER="${KIWI_ADMIN_USER:-kiwi}"
export KIWI_ADMIN_PASSWORD="${KIWI_ADMIN_PASSWORD:-kiwi}"
export KIWI_ADMIN_EMAIL="${KIWI_ADMIN_EMAIL:-kiwi@example.com}"
RECREATE_ADMIN=0
DETACH=0
WAIT_ONLY=0

for arg in "$@"; do
    case "$arg" in
        --recreate-admin) RECREATE_ADMIN=1 ;;
        --detach) DETACH=1 ;;
        --wait-only) WAIT_ONLY=1 ;;
        -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

if [[ "$DETACH" == "1" && "$WAIT_ONLY" == "1" ]]; then
    echo "--detach и --wait-only несовместимы" >&2
    exit 2
fi

cd "$HERE"

# Файл секретов необязателен: compose монтирует его через bind-mount, и если
# файла нет, Docker создаст каталог — а это уже ошибка. Готовим заглушку.
SECRETS_FILE="${KIWI_SECRETS_FILE:-$HERE/secrets.env}"
if [[ ! -f "$SECRETS_FILE" ]]; then
    cat > "$SECRETS_FILE" <<'EOF'
# Файл секретов Kiwi TCMS (подключается в /run/secrets/kiwi.env).
# Заполнять нужно только для внешнего стенда: локально Kiwi работает
# с дефолтным ключом Django из образа.
#
# KIWI_SECRET_KEY=сгенерируйте: openssl rand -base64 48
EOF
fi

# Сертификат нужен до старта контейнера: compose монтирует tls/*.crt в
# /Kiwi/ssl/, и при отсутствии файла bind-mount создаёт каталог, а nginx
# падает с "cannot load certificate key … BIO_new_file() failed".
"$HERE/gen-tls.sh"

if [[ "$WAIT_ONLY" == "1" ]]; then
    echo "==> --wait-only: контейнеры не трогаю, только дождусь готовности"
else
    echo "==> docker compose up -d"
    docker compose -f "$COMPOSE_FILE" up -d
fi

# Фоновый режим: контейнеры запущены, но ни HTTP-ожидания, ни init_db.
# Сообщение про следующий шаг обязательно — иначе пользователь уйдёт
# считать стенд готовым и упрётся в 500 на /.
if [[ "$DETACH" == "1" ]]; then
    cat <<EOF

Kiwi TCMS запущен в фоне (init БД НЕ выполнялась).

  URL:      https://127.0.0.1:${KIWI_HTTPS_PORT:-8443}
  Логин:    $KIWI_ADMIN_USER / $KIWI_ADMIN_PASSWORD

Дождаться готовности и инициализировать БД:
  just kiwi-wait          (или ./infra/kiwi/up.sh --wait-only)

Холодный старт занимает ~2 мин: стенд с пустой БД отдаёт 500 на /
до завершения миграций — это ожидаемо, не ошибка.
EOF
    exit 0
fi

# Ждём не только «контейнер жив», а именно HTTP: без этого init_db иногда
# стартует раньше, чем nginx/uwsgi поднимает сокет.
# Ждём именно HTTPS на 8443, а не http на 8000: внутри образа порт 8080 —
# это nginx-блок, который только редиректит на https, приложение живёт на
# 8443, и наружу публикуется именно он (см. docker-compose.yml).
KIWI_URL="https://127.0.0.1:${KIWI_HTTPS_PORT:-8443}"
echo "==> waiting for $KIWI_URL"
for i in $(seq 1 120); do
    # -k: сертификат локального стенда самоподписанный (gen-tls.sh), и до
    # генерации curl без -k падал бы с ошибкой проверки, маскируя ответ Kiwi.
    code=$(curl -sk -o /dev/null -w '%{http_code}' "$KIWI_URL/" || true)
    # 301 тоже принимаем: до инициализации БД Kiwi редиректит / на
    # /accounts/login/ или /init-db/, и это уже признак того, что nginx/uwsgi
    # живы. Принимать только 200|302 означает ждать полный таймаут на стенде,
    # который на самом деле готов.
    if [[ "$code" =~ ^(200|301|302)$ ]]; then
        echo "    http up (status $code) after ${i}s"
        break
    fi
    sleep 1
    # Таймаут обязан совпадать с длиной цикла. Раньше здесь стояло `-eq 60`
    # при `seq 1 120`: по истечении 60 секунд цикл просто продолжался, скрипт
    # доходил до конца и печатал «Kiwi готов» при стенде, который не поднялся.
    if [[ $i -eq 120 ]]; then
        echo "!! http did not come up in ${i}s; last status: ${code:-none}" >&2
        docker compose -f "$COMPOSE_FILE" logs --tail=50 kiwi >&2
        exit 1
    fi
done

# Python-код передаётся файлом, а не через sh -c "…": вложенные кавычки в
# heredoc-скрипте ломаются молча (исключение съедалось stderr_redirect),
# и скрипт рапортовал успех при KeyError внутри контейнера.
KIWI_BOOTSTRAP=$(mktemp)
trap 'rm -f "$KIWI_BOOTSTRAP"' EXIT
cat > "$KIWI_BOOTSTRAP" <<'PYEOF'
import os

from django.contrib.auth import get_user_model

User = get_user_model()
username = os.environ["KIWI_ADMIN_USER"]

if os.environ.get("KIWI_RECREATE_ADMIN") == "1":
    User.objects.filter(username=username).delete()
    print(f"deleted existing user {username}")

user = User.objects.filter(username=username).first()
if user is None:
    User.objects.create_superuser(
        username,
        os.environ["KIWI_ADMIN_EMAIL"],
        os.environ["KIWI_ADMIN_PASSWORD"],
    )
    print(f"created superuser {username}")
else:
    print(f"superuser {username} already exists")
PYEOF

echo "==> init_db (migrations + default data); idempotent"
docker cp "$KIWI_BOOTSTRAP" "$KIWI_WEB:/tmp/kiwi_bootstrap.py"

# -u 0: файлы миграций в образе принадлежат uid 1001, а manage.py init_db
# от non-root не может их пересоздать при первой инициализации.
docker exec -u 0 -e KIWI_ADMIN_USER -e KIWI_ADMIN_PASSWORD -e KIWI_ADMIN_EMAIL \
    -e KIWI_RECREATE_ADMIN="$RECREATE_ADMIN" "$KIWI_WEB" \
    /venv/bin/python /Kiwi/manage.py init_db 2>&1 | tail -5

docker exec -u 0 -e KIWI_ADMIN_USER -e KIWI_ADMIN_PASSWORD -e KIWI_ADMIN_EMAIL \
    -e KIWI_RECREATE_ADMIN="$RECREATE_ADMIN" "$KIWI_WEB" \
    /venv/bin/python /Kiwi/manage.py shell -c \
    "exec(open('/tmp/kiwi_bootstrap.py').read())" 2>&1 | tail -3

docker exec -u 0 "$KIWI_WEB" rm -f /tmp/kiwi_bootstrap.py

cat <<EOF

Kiwi TCMS готов.

  URL:      https://127.0.0.1:${KIWI_HTTPS_PORT:-8443}
  Логин:    $KIWI_ADMIN_USER / $KIWI_ADMIN_PASSWORD

Дальше:
  ./infra/kiwi/sync.py --plan        # залить структуру и тест-кейсы из репозитория
  ./infra/kiwi/sync.py --results     # залить результаты последнего прогона Gradle
  ./infra/kiwi/gaps.py               # что не покрыто (список кейсов без прогона)

Остановить: ./infra/kiwi/down.sh
EOF
