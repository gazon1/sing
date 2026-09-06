# ==============================================================================
# 🖥️ DESKTOP DB — flat recipes in desktop:: namespace
# Recipes: desktop::db-schema, desktop::db-tables
# Queries the local SQLite DB at ~/.local/share/singularity/databases/
# ==============================================================================

DESKTOP_DB := env_var("HOME") + "/.local/share/singularity/databases/singularity.db"

[group('db')]
[doc('Print DB schema for desktop')]
[working-directory(justfile_directory())]
db-schema:
    #!/bin/bash
    set -euo pipefail
    sqlite3 {{DESKTOP_DB}} ".schema"

[group('db')]
[doc('List DB tables for desktop')]
[working-directory(justfile_directory())]
db-tables:
    #!/bin/bash
    set -euo pipefail
    sqlite3 {{DESKTOP_DB}} ".tables"
