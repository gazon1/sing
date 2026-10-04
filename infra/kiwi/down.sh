#!/usr/bin/env bash
# =============================================================================
# down.sh — остановить стенд Kiwi TCMS
# =============================================================================
#   ./infra/kiwi/down.sh            # остановить, данные и volumes сохранить
#   ./infra/kiwi/down.sh --purge    # остановить и удалить БД + uploads
#
# --purge необратим: Kiwi хранит в volumes ручные правки кейсов и историю
# прогонов. Коммитированные в git вещи (docker-compose.yml, sync.py) после
# purge восстанавливаются командой, а вот набитая вручную база — нет.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$HERE/docker-compose.yml"
PURGE=0

for arg in "$@"; do
    case "$arg" in
        --purge) PURGE=1 ;;
        -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

cd "$HERE"

if [[ "$PURGE" == "1" ]]; then
    read -r -p "Удалить БД Kiwi и загруженные файлы? [y/N] " ans
    case "$ans" in
        [yY]|[yY][eE][sS]) ;;
        *) echo "aborted"; exit 0 ;;
    esac
    docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
    echo "purged: volumes singularity-kiwi_kiwi-db, singularity-kiwi_kiwi-uploads"
else
    docker compose -f "$COMPOSE_FILE" down --remove-orphans
    echo "stopped; volumes kept"
fi
