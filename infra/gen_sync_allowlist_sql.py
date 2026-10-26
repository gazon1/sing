#!/usr/bin/env python3
"""
Generates supabase/sync_field_allowlist_seed.sql from the derived
sync field lists.

The lists are derived in Kotlin, from the models themselves, and exported to
config/docs/sync-field-allowlist.json by `:shared:exportSyncFieldAllowlist`.
This script reads that export.

It used to read the Kotlin source with a regular expression, and the regular
expression stopped at the first closing parenthesis — inside a comment — so the
seed it wrote was three names short for one of the types, and the gate that
checked the file compared it with the same truncated list. A parser that
misreads the source cannot be caught by a check that reads the same source.

Usage: python3 infra/gen_sync_allowlist_sql.py <project-root>
Output: supabase/sync_field_allowlist_seed.sql
"""
import json
import os
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
export_path = os.path.join(root, "config/docs/sync-field-allowlist.json")
output_path = os.path.join(root, "supabase/sync_field_allowlist_seed.sql")

with open(export_path) as f:
    export = json.load(f)

# The entity type is the DocType key — the name the server looks the type up by
# — and the seed holds the names the server may write.
entries = []
for entity_type in sorted(export["docTypes"]):
    for field in export["docTypes"][entity_type]["serverWritable"]:
        entries.append(f"    ('{entity_type}', '{field}', true)")

lines = []
lines.append("-- DO NOT EDIT — generated from the derived sync field lists")
lines.append("-- Source: config/docs/sync-field-allowlist.json")
lines.append("-- Re-run: python3 infra/gen_sync_allowlist_sql.py")
lines.append("--")
lines.append("-- This file DESCRIBES the field table the server is expected to hold; it is")
lines.append("-- not a migration to apply. The live table is seeded by the dated")
lines.append("-- migrations in supabase/migrations/, which the gate reads statement by")
lines.append("-- statement — and whatever it cannot read there it reports, so a row of the")
lines.append("-- table it did not see is a failure with a name on it, not a row that")
lines.append("-- quietly disappears. Applying this file on a database")
lines.append("-- whose table already holds older rows would not bring that table up to")
lines.append("-- date: the insert is conflict-tolerant and adds nothing for a field that")
lines.append("-- is already there.")
lines.append("")
lines.append("insert into sync_field_allowlist (entity_type, field, writable) values")
lines.append(",\n".join(entries))
lines.append("on conflict do nothing;")

os.makedirs(os.path.dirname(output_path), exist_ok=True)
with open(output_path, "w") as f:
    f.write("\n".join(lines) + "\n")

print(f"Generated {output_path} ({len(entries)} rows)")
