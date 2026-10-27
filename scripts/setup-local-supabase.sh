#!/usr/bin/env bash
# setup-local-supabase.sh — bootstrap a local Supabase stack for E2E testing.
#
# Automates the steps from docs/architecture/local-stack.md:
#   1. Clone supabase-repo-sing (if not present)
#   2. Generate .env with JWT keys
#   3. Download DB init files
#   4. Start docker compose
#   5. Apply migrations
#   6. Print anon key for local.properties
#
# Usage: bash scripts/setup-local-supabase.sh [STACK_DIR]
#   STACK_DIR defaults to /tmp/supabase-repo-sing

set -euo pipefail

STACK_DIR="${1:-/tmp/supabase-repo-sing}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 1. Clone if needed
if [ ! -d "$STACK_DIR" ]; then
    echo "Cloning supabase-repo-sing..."
    git clone https://github.com/gazon1/supabase-repo-sing.git "$STACK_DIR"
fi
cd "$STACK_DIR"

# 2. Generate .env if missing
if [ ! -f .env ]; then
    echo "Generating .env..."
    python3 - "$STACK_DIR" <<'PYEOF'
import json, base64, hmac, hashlib, os, secrets, sys
stack = sys.argv[1]
def b64url(d): return base64.urlsafe_b64encode(d).rstrip(b'=').decode()
jwt = secrets.token_hex(32)
h = b64url(json.dumps({'alg':'HS256','typ':'JWT'}).encode())
ap = b64url(json.dumps({'role':'anon','iss':'supabase','iat':1700000000,'exp':1900000000}).encode())
sp = b64url(json.dumps({'role':'service_role','iss':'supabase','iat':1700000000,'exp':1900000000}).encode())
asig = b64url(hmac.new(jwt.encode(), (h+'.'+ap).encode(), hashlib.sha256).digest())
ssig = b64url(hmac.new(jwt.encode(), (h+'.'+sp).encode(), hashlib.sha256).digest())
lines = [
    f"POSTGRES_PASSWORD={secrets.token_hex(16)}",
    "POSTGRES_DB=postgres", "POSTGRES_HOST=db", "POSTGRES_PORT=5432",
    "POSTGRES_EXTERNAL_PORT=5432", f"JWT_SECRET={jwt}", "JWT_EXPIRY=3600",
    f"ANON_KEY={h}.{ap}.{asig}", f"SERVICE_ROLE_KEY={h}.{sp}.{ssig}",
    "SUPABASE_PUBLIC_URL=http://localhost:8000", "API_EXTERNAL_URL=http://localhost:8000",
    f"DASHBOARD_USERNAME=admin", f"DASHBOARD_PASSWORD={secrets.token_hex(8)}",
    "MINIO_ROOT_USER=minioadmin", f"MINIO_ROOT_PASSWORD={secrets.token_hex(8)}",
    "MINIO_BUCKET=supabase", f"LOGFLARE_PUBLIC_ACCESS_TOKEN={secrets.token_hex(16)}",
    f"SECRET_KEY_BASE={secrets.token_hex(32)}", f"VAULT_ENC_KEY={secrets.token_hex(16)}",
    f"PG_META_CRYPTO_KEY={secrets.token_hex(16)}", "KONG_HTTP_PORT=8000",
    "KONG_HTTPS_PORT=9443", "POOLER_PROXY_PORT_TRANSACTION=6543",
    "POOLER_DB_POOL_SIZE=5", "POOLER_DEFAULT_POOL_SIZE=10", "POOLER_MAX_CLIENT_CONN=100",
    "POOLER_TENANT_ID=tenant", "DOCKER_SOCKET_LOCATION=/var/run/docker.sock",
    "SMTP_HOST=", "SMTP_PORT=587", "SMTP_USER=", "SMTP_PASS=",
    "SMTP_ADMIN_EMAIL=admin@example.com", "SMTP_SENDER_NAME=Supabase",
    "ENABLE_EMAIL_SIGNUP=true", "ENABLE_EMAIL_AUTOCONFIRM=true", "DISABLE_SIGNUP=false",
    "ADDITIONAL_REDIRECT_URLS=", "PGRST_DB_SCHEMAS=public,storage,graphql_public",
]
open('.env','w').write('\n'.join(lines)+'\n')
print("done")
PYEOF
fi

# 3. Download DB init files
if [ ! -f volumes/db/roles.sql ]; then
    echo "Downloading DB init files..."
    bash scripts/download-db-init.sh
fi

chmod +x entrypoint.sh

# 4. Start compose
echo "Starting docker compose..."
docker compose up -d 2>&1 | tail -5

# 5. Apply migrations
echo "Applying migrations..."
for sql in "$REPO_ROOT"/supabase/migrations/*.sql; do
    echo "  $sql"
    docker exec -i supabase-db psql -U postgres -d postgres -q < "$sql" 2>&1 | tail -2
done

# 6. Print anon key
ANON=$(grep '^ANON_KEY=' .env | cut -d= -f2)
echo ""
echo "=== Ready ==="
echo "Supabase URL: http://localhost:8000"
echo "Anon key: $ANON"
echo ""
echo "Add to local.properties:"
echo "  supabase.url=http://localhost:8000"
echo "  supabase.anonKey=$ANON"
echo ""
echo "Set up adb reverse: adb reverse tcp:8000 tcp:8000"