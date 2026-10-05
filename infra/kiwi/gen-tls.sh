#!/usr/bin/env bash
# =============================================================================
# gen-tls.sh — самоподписанный сертификат для локального Kiwi
# =============================================================================
# Зачем свой сертификат, если в образе уже есть /Kiwi/ssl/localhost.crt:
#   - сертификат в образе выдан хосту СБОРКИ образа (CN=buildkitsandbox),
#     в SAN нет ни localhost, ни IP. Ни браузер, ни urllib такой сертификат
#     на 127.0.0.1 не примет — будет ERR_CERT_COMMON_NAME_INVALID;
#   - он самоподписанный, поэтому доверять ему всё равно придётся вручную.
# Свой сертификат с SAN=DNS:localhost,IP:127.0.0.1 решает обе задачи.
#
# Права: 0644 на ключ — не ошибка, а требование образа. Контейнер стартует
# от uid 1001 (см. docker image inspect), и nginx не может прочитать ключ
# 0600 root, падая с BIO_new_file() failed / Permission denied — то есть
# стенд не поднимается вообще. Приватность ключа тут не защищается:
# он лежит в git-игнорируемом каталоге и используется только на localhost.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TLS_DIR="$HERE/tls"
CRT="$TLS_DIR/localhost.crt"
KEY="$TLS_DIR/localhost.key"
DAYS="${KIWI_TLS_DAYS:-825}"

mkdir -p "$TLS_DIR"

if [[ -f "$CRT" && -f "$KEY" && "${KIWI_TLS_FORCE:-0}" != "1" ]]; then
    echo "tls: reusing existing $CRT"
else
    echo "tls: generating self-signed cert (${DAYS}d, SAN=localhost,127.0.0.1)"
    openssl req -x509 -newkey rsa:2048 -nodes \
        -keyout "$KEY" -out "$CRT" -days "$DAYS" \
        -subj "/C=RU/L=Moscow/O=Singularity Todo/OU=Local Kiwi/CN=localhost" \
        -addext "subjectAltName=DNS:localhost,DNS:kiwi.local,IP:127.0.0.1" \
        2>/dev/null
fi

chmod 0644 "$CRT" "$KEY"
echo "tls: $(openssl x509 -in "$CRT" -noout -subject 2>/dev/null)"
echo "tls: permissions set to 0644 (required: container runs as uid 1001)"
