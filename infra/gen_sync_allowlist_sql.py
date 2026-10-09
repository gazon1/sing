#!/usr/bin/env python3
"""
Generates supabase/migrations/sync_field_allowlist_seed.sql from
shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt

Usage: python3 infra/gen_sync_allowlist_sql.py <project-root>
Output: supabase/migrations/sync_field_allowlist_seed.sql
"""
import sys, re, os

root = sys.argv[1] if len(sys.argv) > 1 else "."
contract_path = os.path.join(root, "shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt")
output_path = os.path.join(root, "supabase/migrations/sync_field_allowlist_seed.sql")

with open(contract_path) as f:
    content = f.read()

# Extract FIELD_ALLOWLIST map entries
# Each allowlist: private val TASK_ALLOWLIST = setOf("field1", "field2", ...)
doctype_sections = re.findall(
    r'private val (\w+_ALLOWLIST) = setOf\((.*?)\)',
    content, re.DOTALL
)

# Map allowlist name to DocType enum name
allowlist_to_doctype = {
    "TASK_ALLOWLIST": "Task",
    "NOTE_ALLOWLIST": "Note",
    "PROJECT_ALLOWLIST": "Project",
    "TAG_ALLOWLIST": "Tag",
    "TAG_GROUP_ALLOWLIST": "TagGroup",
    "TIME_ENTRY_ALLOWLIST": "TimeEntry",
}

lines = []
lines.append("-- DO NOT EDIT — generated from SyncContract.FIELD_ALLOWLIST")
lines.append("-- Re-run: python3 infra/gen_sync_allowlist_sql.py")
lines.append("")

entries = []
for allowlist_name, fields_raw in doctype_sections:
    doctype = allowlist_to_doctype.get(allowlist_name)
    if not doctype:
        continue  # Unknown allowlist, skip
    table_name = doctype.lower()
    # Extract quoted string literals
    fields = re.findall(r'"(\w+)"', fields_raw)
    for field in fields:
        entries.append(f"    ('{table_name}', '{field}', true)")

lines.append("insert into sync_field_allowlist (entity_type, field, writable) values")
lines.append(",\n".join(entries))
lines.append("on conflict do nothing;")

os.makedirs(os.path.dirname(output_path), exist_ok=True)
with open(output_path, "w") as f:
    f.write("\n".join(lines) + "\n")

print(f"Generated {output_path} ({len(entries)} rows)")
