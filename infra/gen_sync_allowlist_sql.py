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


def mask_non_code(source: str) -> str:
    """The source with everything that is not code blanked out.

    A parenthesis inside a comment or inside a string is not a parenthesis of
    the code, and a reader that counts them stops in the wrong place. The
    list of the fields of one allowlist used to be cut at the first `)` it
    met — and in `SyncContract.kt` that `)` sits inside a comment — so the
    seed lost the fields that came after it, silently: the seed and the
    generator agreed with each other, because they were cut in the same
    place, and the gate that compares the two saw nothing.

    What is blanked: the text of a line comment, the text of a block
    comment, and the whole of a string literal — the quotes included. Every
    blanked character becomes one space, so the result is the same length as
    the source and a position in one is a position in the other.
    """
    out = []
    i = 0
    n = len(source)
    while i < n:
        char = source[i]
        if char == '/' and source[i + 1:i + 2] == '/':
            end = source.find('\n', i)
            end = n if end == -1 else end
            out.append(' ' * (end - i))
            i = end
        elif char == '/' and source[i + 1:i + 2] == '*':
            end = source.find('*/', i + 2)
            end = n if end == -1 else end + 2
            out.append(''.join(c if c == '\n' else ' ' for c in source[i:end]))
            i = end
        elif char == '"':
            end = i + 1
            while end < n:
                if source[end] == '\\':
                    end += 2
                    continue
                if source[end] == '"':
                    end += 1
                    break
                end += 1
            out.append(''.join(c if c == '\n' else ' ' for c in source[i:end]))
            i = end
        else:
            out.append(char)
            i += 1
    return ''.join(out)


def allowlist_blocks(source: str) -> list:
    """The name and the body of every allowlist, in the order they appear.

    Each allowlist is a `setOf(...)`: the body ends at the parenthesis that
    closes the call, and it is found by counting — on the masked source, so
    that a parenthesis in a comment does not end it. The body itself is
    taken from the original source, because the names of the fields are
    string literals, and the mask has blanked them.
    """
    masked = mask_non_code(source)
    blocks = []
    for match in re.finditer(r'private val (\w+_ALLOWLIST) = setOf\(', masked):
        depth = 1
        i = match.end()
        while depth:
            if masked[i] == '(':
                depth += 1
            elif masked[i] == ')':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        blocks.append((match.group(1), source[match.end():i]))
    return blocks


# Extract FIELD_ALLOWLIST map entries
# Each allowlist: private val TASK_ALLOWLIST = setOf("field1", "field2", ...)
doctype_sections = allowlist_blocks(content)

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
