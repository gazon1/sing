#!/usr/bin/env bash
# =============================================================================
# status.sh — состояние стенда Kiwi TCMS
# =============================================================================
# Показывает три вещи по отдельности, потому что каждая отвечает на свой
# вопрос, и «контейнер Up» не означает «Kiwi готов»:
#
#   1. Контейнеры      — запущены ли, и что с их healthcheck
#   2. HTTP            — отвечает ли приложение (это то, что нужно браузеру)
#   3. БД              - есть ли пользователи, т.е. выполнена ли инициализация
#
# Разделение существенно: в фоне (`up.sh --detach`) БД не инициализирована, и
# Kiwi при этом отдаёт 500 на / — выглядит как «не работает», хотя контейнеры
# здоровы. Без раздельных статусов это неотличимо от поломки.
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$HERE/docker-compose.yml"
KIWI_WEB=singularity-kiwi-web
KIWI_DB=singularity-kiwi-db
KIWI_URL="https://127.0.0.1:${KIWI_HTTPS_PORT:-8443}"

echo "== контейнеры"
docker compose -f "$COMPOSE_FILE" ps --format \
    'table {{.Name}}\t{{.State}}\t{{.Status}}' 2>/dev/null || echo "  (docker compose ps не отдал данных)"

if ! docker ps --format '{{.Names}}' | grep -qx "$KIWI_DB"; then
    echo
    echo "Итог: стенд НЕ запущен. Поднять: just kiwi-start"
    exit 1
fi

echo
echo "== HTTP $KIWI_URL"
code=$(curl -sk -o /dev/null -w '%{http_code}' "$KIWI_URL/" 2>/dev/null || true)
case "$code" in
    200|301|302) echo "  отвечает (HTTP $code)" ;;
    000) echo "  не отвечает — приложение ещё стартует или упало (см. just kiwi-logs)" ;;
    500) echo "  HTTP 500 — контейнер жив, но БД не инициализирована. Выполните: just kiwi-wait" ;;
    *)   echo "  неожиданный ответ: HTTP ${code:-none}" ;;
esac

echo
echo "== база данных"
if docker exec "$KIWI_WEB" true 2>/dev/null; then
    users=$(docker exec -u 0 "$KIWI_WEB" /venv/bin/python /Kiwi/manage.py shell -c \
        "from django.contrib.auth import get_user_model; print(get_user_model().objects.count())" \
        2>/dev/null | tail -1 | tr -dc '0-9')
    if [[ -n "$users" && "$users" != "0" ]]; then
        echo "  инициализирована, пользователей: $users"
    else
        echo "  НЕ инициализирована (нет пользователей). Выполните: just kiwi-wait"
    fi
else
    echo "  контейнер $KIWI_WEB недоступен"
fi
