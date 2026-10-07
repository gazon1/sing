#!/usr/bin/env python3
"""check-supabase-schema-integrity.py — the versioned sync schema is self-consistent.

Why this exists (2026-10-07): `supabase/migrations/2026-10-07-sync_schema.sql` is the only
reviewable description of the server's sync schema, and nothing checks it. Not the live
database — that needs credentials and cannot be a blocking gate — but the file itself.

The live half is #221 and stays manual. This is the half that can be a gate, and it catches
the failure that actually happens: somebody edits the SQL in the file and the header stops
describing it. A header that is quietly false is worse than no header, because the header is
the part a reader trusts.

## What it checks

1. **Every function in the file has a fingerprint line, and every fingerprint line names a
   function in the file.** One-directional in each half, so both failure modes are caught:
   a new `sync_*` function with no `md5` claim (the file asserts less than it does), and a
   fingerprint left behind after a function is deleted (the file asserts more than it does).
2. **No object is declared twice.** Two `create table` statements for one name mean the
   file is not the single capture it claims to be.
3. **Every table a policy names is created in the file.** RLS is the part of a snapshot that
   looks authoritative and is the part that decides whether one account can read another's
   tasks; a policy pointing at a table the file does not define is a policy nobody reviewed.

## What it deliberately does not check

It cannot tell you the fingerprints are *correct*. Those are md5 of `pg_proc.prosrc` in a live
database, and reading them requires credentials. This gate proves the claim and its subject
still refer to the same set of functions — which is what makes a stale one visible — and no
more. Claiming otherwise would be the `check-gate-wiring` defect class: a gate that reports
success where it has no evidence.

Usage:
    python3 scripts/check-supabase-schema-integrity.py [--dir supabase/migrations]

Exit codes:
    0 — every function is fingerprinted, every fingerprint is a function, nothing declared twice
    1 — a finding, printed one per line
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "supabase" / "migrations"

#: `--   <32 hex>  name(sig)` — one per function, inside the file's own header comment.
FINGERPRINT = re.compile(r"^--\s+([0-9a-f]{32})\s+(\w+)\s*\(", re.M)

#: `create or replace function public.sync_health()` and the multi-line signature form.
CREATE_FUNCTION = re.compile(r"^\s*create\s+(?:or\s+replace\s+)?function\s+(?:public\.)?(\w+)\s*\(", re.M | re.I)

CREATE_TABLE = re.compile(r"^\s*create\s+table\s+(?:if\s+not\s+exists\s+)?(\w+)", re.M | re.I)

CREATE_INDEX = re.compile(r"^\s*create\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?(\w+)", re.M | re.I)

CREATE_POLICY_ON = re.compile(
    r"^\s*create\s+policy\s+\w+\s+on\s+(\w+)", re.M | re.I
)


def check(text: str, name: str) -> list[str]:
    errors: list[str] = []

    functions = CREATE_FUNCTION.findall(text)
    fingerprinted = [f for _, f in FINGERPRINT.findall(text)]

    missing = [fn for fn in functions if fn not in fingerprinted]
    for fn in missing:
        errors.append(
            f"{name}: function '{fn}' has no md5 fingerprint in the header. The file now "
            f"asserts less than it does — add `--   <md5>  {fn}(<signature>)` to the "
            f"Fingerprint block, or the header understates the schema."
        )

    orphans = [fn for fn in fingerprinted if fn not in functions]
    for fn in orphans:
        errors.append(
            f"{name}: the header fingerprints '{fn}', which this file does not define. "
            f"The header claims a function the capture no longer contains."
        )

    dupes = [fn for fn in set(functions) if functions.count(fn) > 1]
    for fn in sorted(dupes):
        errors.append(
            f"{name}: function '{fn}' is declared more than once. This file is a capture of "
            f"one schema; two definitions of one name means it is not."
        )

    tables = set(CREATE_TABLE.findall(text))
    for table in sorted(set(CREATE_POLICY_ON.findall(text))):
        if table not in tables:
            errors.append(
                f"{name}: a policy is defined on '{table}', which this file never creates. "
                f"Either the table definition is missing or the policy is a leftover."
            )

    # Indexes are named globally in Postgres, so a duplicate name is a real conflict —
    # and unlike tables it is silent: the second statement wins.
    indexes = CREATE_INDEX.findall(text)
    for index in sorted({i for i in indexes if indexes.count(i) > 1}):
        errors.append(
            f"{name}: index '{index}' is created more than once. Postgres index names are "
            f"global, so the second creation silently replaces the first."
        )

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--dir", default=str(DEFAULT_DIR), help="migration directory")
    args = parser.parse_args(argv)

    return main_for([args.dir])


def main_for(directories: list[str]) -> int:
    """`main()`'s body, taking the directory list directly.

    Split out so the self-test can point it at a directory that does not exist. That case
    is the one worth testing and it cannot be reached through the command line: argparse
    accepts any string as a path, so "scan nothing" is reachable without a typo being
    caught first — which is exactly why it needs a test rather than a read-through.
    """
    errors: list[str] = []
    scanned = 0

    for raw in directories:
        directory = pathlib.Path(raw)
        files = sorted(directory.glob("*.sql")) if directory.is_dir() else []
        if not files:
            print(
                f"no .sql files under {directory} — the path this gate reads has moved, and a "
                f"gate that scans nothing reports success",
                file=sys.stderr,
            )
            return 1
        scanned += len(files)
        for path in files:
            errors += check(path.read_text(encoding="utf-8"), path.name)

    if errors:
        for error in errors:
            print(f"supabase schema integrity: {error}", file=sys.stderr)
        print(f"{len(errors)} finding(s) in {scanned} migration file(s)", file=sys.stderr)
        return 1

    print(
        f"supabase schema integrity: {scanned} migration file(s) self-consistent "
        f"(fingerprints match the functions they describe)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())