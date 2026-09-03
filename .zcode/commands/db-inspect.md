# /db-inspect — Inspect Room database on Android or Desktop

Pulls the Room database and runs SQL queries against it.

## Usage

```
/db-inspect [sql]
```

- `sql` (optional): SQL query. If omitted, shows table list.

## Android

```bash
# 1. Copy DB from app's private storage to accessible location
adb shell run-as com.singularity.todo cp databases/singularity.db /sdcard/singularity.db

# 2. Pull to local
adb pull /sdcard/singularity.db /tmp/singularity.db

# 3. Show tables
sqlite3 /tmp/singularity.db ".tables"

# 4. Show schema
sqlite3 /tmp/singularity.db ".schema"

# 5. Run query
sqlite3 /tmp/singularity.db "SELECT * FROM tasks LIMIT 5;"

# 6. Count rows
sqlite3 /tmp/singularity.db "SELECT COUNT(*) FROM tasks;"

# 7. Check sync status
sqlite3 /tmp/singularity.db "SELECT id, sync_status, hlc FROM tasks ORDER BY created_at DESC LIMIT 10;"
```

## Desktop (JVM)

```bash
DB_PATH="${XDG_DATA_HOME:-$HOME/.local/share}/singularity/databases/singularity.db"
sqlite3 "$DB_PATH" ".tables"
sqlite3 "$DB_PATH" "SELECT * FROM tasks LIMIT 5;"
```

## Common queries

```sql
-- All tables
SELECT name FROM sqlite_master WHERE type='table';

-- Recent tasks
SELECT id, title, completed_at, sync_status FROM tasks ORDER BY created_at DESC LIMIT 20;

-- Unsynced items
SELECT id, title, sync_status FROM tasks WHERE sync_status != 'SYNCED';

-- Notes with sync info
SELECT id, title, updated_at, sync_status FROM notes ORDER BY updated_at DESC LIMIT 10;

-- Reminders
SELECT * FROM task_reminders ORDER BY fire_at ASC LIMIT 20;
```

## Notes

- App must be installed (Android) or previously run (Desktop creates DB at `~/.local/share/singularity/`)
- `sqlite3` CLI must be available on host
- On Android, the app must have `READ_EXTERNAL_STORAGE` or use `run-as` trick shown above
