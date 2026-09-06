# ==============================================================================
# 🤖 ANDROID DB — flat recipes in android:: namespace
# Recipes: android::db-pull (private), android::db-schema, android::db-tables
# Invoked as: just android::db-schema serial=…
# ==============================================================================

DB_DEST := "/tmp/singularity.db"

[group('db')]
[doc('Pull DB from device (used by schema/tables)')]
[private]
[working-directory(justfile_directory())]
db-pull serial="":
    #!/bin/bash
    set -euo pipefail
    if [ -z "{{serial}}" ]; then
        echo "ERR: pass serial=… or set ADB_SERIAL"
        exit 1
    fi
    adb -s {{serial}} shell run-as com.singularity.todo cp databases/singularity.db /sdcard/
    adb -s {{serial}} pull /sdcard/singularity.db {{DB_DEST}}

[group('db')]
[doc('Pull DB and print schema')]
[working-directory(justfile_directory())]
db-schema serial="":
    #!/bin/bash
    set -euo pipefail
    just android::db-pull "{{serial}}"
    sqlite3 {{DB_DEST}} ".schema"

[group('db')]
[doc('Pull DB and list tables')]
[working-directory(justfile_directory())]
db-tables serial="":
    #!/bin/bash
    set -euo pipefail
    just android::db-pull "{{serial}}"
    sqlite3 {{DB_DEST}} ".tables"
