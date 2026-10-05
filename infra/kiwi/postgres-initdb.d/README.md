# Намеренно пустой каталог init-скриптов Postgres

Монтируется в `/docker-entrypoint-initdb.d` в `docker-compose.yml`, чтобы
отключить `00_enable_ssl.sh` из образа `kiwitcms/postgres`.

Скрипт начинается с `set -eu` и падает на `[ -z "$POSTGRES_SSL_CERT" ]`
(unbound variable) при первой инициализации кластера, то есть на пустом
volume. Контейнер завершается с кодом 1, и `docker compose up` падает с
«dependency failed to start: container … is unhealthy».

SSL самому Postgres на локальном стенде не нужен: трафик Kiwi→Postgres
идёт по внутренней сети Docker, наружу открыт только Kiwi на 8443.

Не удаляйте этот каталог: если он исчезнет, Docker создаст на его месте
каталог — и `docker compose up` снова будет падать при каждом холодном
старте, с сообщением, не указывающим на настоящую причину.
