#!/usr/bin/env python3
"""Tests for check-supabase-schema-integrity.py.

Same reason as every other gate self-test in this directory: a gate that has only ever
reported success is indistinguishable from a gate that cannot fail, and the defect class
this repository keeps hitting is exactly that.

The checks here are on `check()` — the rule — not on the repository. The repository passing
says the migration file is fine; it says nothing about whether the rule can see a problem,
which is the only question that can bite tomorrow.
"""

import importlib.util
import pathlib
import tempfile
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _load():
    path = _ROOT / "scripts" / "check-supabase-schema-integrity.py"
    spec = importlib.util.spec_from_file_location("check_supabase_schema_integrity", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gate = _load()

ONE_FUNCTION = """
-- Fingerprint
--
--   0123456789abcdef0123456789abcdef  sync_health()
--
create or replace function public.sync_health()
returns integer language sql as $$ select 1 $$;

create table if not exists sync_tasks (
    id uuid primary key
);

create policy sync_tasks_owner_policy on sync_tasks
    using (owner_id = auth.uid());
"""


class FingerprintAgreementTest(unittest.TestCase):
    def test_a_consistent_file_reports_nothing(self):
        self.assertEqual([], gate.check(ONE_FUNCTION, "m.sql"))

    def test_a_function_without_a_fingerprint_is_reported(self):
        # The dominant failure: somebody adds a function and forgets the header, which
        # leaves the file asserting less than it does.
        broken = ONE_FUNCTION + (
            "\ncreate or replace function public.sync_newcomer(p_type text)\n"
            "returns text as $$ select p_type $$ language sql;\n"
        )
        errors = gate.check(broken, "m.sql")
        self.assertTrue(any("sync_newcomer" in e for e in errors), errors)

    def test_a_fingerprint_without_a_function_is_reported(self):
        # The other direction, which is easy to forget when only the "new thing fails"
        # case is covered: a deleted function leaves its claim behind, and the header then
        # asserts something the capture does not contain.
        stale = ONE_FUNCTION.replace(
            "--   0123456789abcdef0123456789abcdef  sync_health()",
            "--   0123456789abcdef0123456789abcdef  sync_health()\n"
            "--   fedcba9876543210fedcba9876543210  sync_removed()",
        )
        errors = gate.check(stale, "m.sql")
        self.assertTrue(any("sync_removed" in e for e in errors), errors)

    def test_a_duplicate_function_is_reported(self):
        twice = ONE_FUNCTION + (
            "\ncreate or replace function public.sync_health()\n"
            "returns integer language sql as $$ select 2 $$;\n"
        )
        errors = gate.check(twice, "m.sql")
        self.assertTrue(any("more than once" in e for e in errors), errors)

    def test_a_duplicate_index_is_reported(self):
        # Postgres index names are global and the second creation silently replaces the
        # first, so this one fails quietly at replay time and not at all in review.
        body = ONE_FUNCTION + (
            "\ncreate index if not exists sync_tasks_idx on sync_tasks (id);\n"
            "create index if not exists sync_tasks_idx on sync_tasks (owner_id);\n"
        )
        errors = gate.check(body, "m.sql")
        self.assertTrue(any("sync_tasks_idx" in e for e in errors), errors)


class PolicyTargetTest(unittest.TestCase):
    def test_a_policy_on_an_undefined_table_is_reported(self):
        broken = ONE_FUNCTION + (
            "\ncreate policy sync_notes_owner_policy on sync_notes using (true);\n"
        )
        errors = gate.check(broken, "m.sql")
        self.assertTrue(any("sync_notes" in e for e in errors), errors)

    def test_a_policy_on_a_defined_table_is_accepted(self):
        self.assertEqual([], gate.check(ONE_FUNCTION, "m.sql"))


class EmptySubjectTest(unittest.TestCase):
    """The risk is not a bad file, it is a gate with nothing to look at."""

    def test_a_file_with_no_functions_is_consistent(self):
        # Tables and policies only: there is nothing for a fingerprint to disagree with,
        # so this must not be reported. A rule that complained here would train everyone to
        # ignore it.
        tables_only = """
create table if not exists sync_tasks (id uuid primary key);
create policy sync_tasks_owner_policy on sync_tasks using (true);
"""
        self.assertEqual([], gate.check(tables_only, "m.sql"))

    def test_a_missing_directory_is_a_failure_not_a_pass(self):
        # The shape that actually matters: the path moves, or the glob stops matching, and
        # a gate that scans nothing reports success forever.
        self.assertEqual(1, gate.main_for(["/nonexistent/migrations"]))

    def test_an_empty_directory_is_a_failure_not_a_pass(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(1, gate.main_for([d]))


if __name__ == "__main__":
    unittest.main()