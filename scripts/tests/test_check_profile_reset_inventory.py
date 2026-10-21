"""Tests for check-profile-reset-inventory.py.

The gate exists because the list of tables a profile reset does *not* reach is
a list of exceptions, and exceptions rot silently. A table added to the Room
schema is not a row in a document, so the document that says the reset reaches
the whole profile stays true in the prose while the code has stopped reaching
it — and the next E2E flow finds the leftover row and blames the test.

So the gate holds three things to each other: the Room schema, the wipe's own
list (`ProfileWipePlan`), and the inventory document that gives every table a
verdict and a reason. The properties that matter, and each a way the gate can
lie by passing:

  - a table nobody decided about is a finding — the new table that appeared
    while the document kept claiming the profile reset was complete;
  - a table the document names and the schema no longer has is a finding — the
    renamed or dropped table;
  - the two lists of wiped tables are the same list — the document claiming
    more than the code does, and the code wiping a table the document calls
    "not profile content";
  - a table the code claims to wipe really has a profile-scoped delete — a
    list that names a table nothing deletes, or a sweep of the live rows;
  - the calls the plan names are the calls the reset makes — the list and the
    code drifting apart;
  - a scan that read nothing reports that, rather than reporting that nothing
    is wrong — the failure mode every source-scanning gate has.

The tests build their own tree: a gate that is only exercised against one
particular checkout is a gate whose behaviour nobody knows when the checkout
changes shape.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'ci' / 'check-profile-reset-inventory.py'
)
spec = importlib.util.spec_from_file_location('check_profile_reset_inventory', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_profile_reset_inventory'] = mod  # must precede exec_module
spec.loader.exec_module(mod)

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]

#: Enough tables that the "did the scan read anything?" guard is satisfied.
#: The gate refuses to report "no problems" when it found almost nothing, so a
#: fixture with three tables would fail for the wrong reason.
TABLES = [f"table_{index}" for index in range(21)]


def _call_for(table: str) -> str:
    """The call the plan names for a table, and the reset makes."""
    return f"dao_{table}.deleteAllForUser"


def build_tree(
    root: pathlib.Path,
    *,
    tables=None,
    wiped=None,
    left_alone=None,
    plan=None,
    impl=None,
    doc=None,
    deletes=None,
) -> pathlib.Path:
    """Write a consistent repository tree, then let the caller break it.

    Every part is optional: what the caller does not pass is derived from
    `tables`, so a test only has to state the thing it is about.
    """
    tables = list(tables if tables is not None else TABLES)
    if wiped is None:
        wiped = {table: _call_for(table) for table in tables[: len(tables) // 2]}
    if left_alone is None:
        left_alone = [table for table in tables if table not in wiped]
    if deletes is None:
        deletes = {
            table: f'@Query("DELETE FROM {table} WHERE user_id = :userId")'
            for table in wiped
        }

    kotlin = root / "shared/src/commonMain/kotlin/com/singularity/todo"
    (kotlin / "core/database").mkdir(parents=True, exist_ok=True)
    (kotlin / "core/wipe").mkdir(parents=True, exist_ok=True)
    (root / "docs/architecture").mkdir(parents=True, exist_ok=True)

    entities = "\n".join(
        f'@Entity(tableName = "{table}")\ndata class {table.title().replace("_", "")}Entity(val id: String)'
        for table in tables
    )
    (kotlin / "core/database/Entities.kt").write_text(entities, encoding="utf-8")

    dao = "\n".join(deletes.values())
    (kotlin / "core/database/Daos.kt").write_text(dao, encoding="utf-8")

    if plan is None:
        plan = ",\n".join(f'        "{table}" to "{call}"' for table, call in wiped.items())
    (kotlin / "core/wipe/ProfileWipePlan.kt").write_text(
        "package com.singularity.todo.core.wipe\n\n"
        "object ProfileWipePlan {\n"
        "    val WIPED: Map<String, String> = mapOf(\n"
        f"{plan},\n"
        "    )\n"
        "}\n",
        encoding="utf-8",
    )

    if impl is None:
        impl = "\n".join(f"        deps.{call}(uid)" for call in sorted(set(wiped.values())))
    (kotlin / "core/wipe/ProfileContentResetter.kt").write_text(
        "package com.singularity.todo.core.wipe\n\n"
        "class ProfileContentResetter(private val deps: ProfileWipeDeps) : ContentResetter {\n"
        "    override suspend fun resetContent(): ContentResetResult {\n"
        "        val uid = \"user-1\"\n"
        f"{impl}\n"
        "        return ContentResetResult(wipedTables = emptyList())\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    if doc is None:
        rows = [
            f"| `{table}` | wiped | profile content |"
            for table in wiped
            if table not in left_alone
        ]
        rows += [
            f"| `{table}` | not profile content | not the profile's content |"
            for table in left_alone
        ]
        doc = "\n".join(rows)
    (root / "docs/architecture/wipe-inventory.md").write_text(
        "# Inventory\n\n| Table | Verdict | Reason |\n|---|---|---|\n" + doc + "\n",
        encoding="utf-8",
    )

    return root


def run_gate(root: pathlib.Path) -> tuple[int, list]:
    """Run the gate over `root` and return (exit code, findings)."""
    inv = mod.collect(root)
    findings = mod._not_vacuous(inv) + mod.check(inv)
    return (0 if not findings else 1), findings


def kinds(findings) -> set:
    return {finding.kind for finding in findings}


class ProfileResetInventoryGateTest(unittest.TestCase):
    """The gate agrees with a consistent tree and disagrees with a broken one."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def test_a_consistent_tree_passes(self):
        build_tree(self.root)
        code, findings = run_gate(self.root)
        self.assertEqual(0, code, f"a consistent tree must pass, got: {findings}")

    def test_a_new_table_nobody_decided_about_fails(self):
        build_tree(self.root)
        entities = self.root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/Entities.kt"
        entities.write_text(
            entities.read_text(encoding="utf-8")
            + '\n@Entity(tableName = "brand_new_table")\ndata class BrandNewTableEntity(val id: String)\n',
            encoding="utf-8",
        )
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("no-verdict", kinds(findings))
        self.assertIn("brand_new_table", {f.table for f in findings})

    def test_a_table_the_document_names_but_the_schema_lost_fails(self):
        build_tree(self.root)
        doc = self.root / "docs/architecture/wipe-inventory.md"
        doc.write_text(
            doc.read_text(encoding="utf-8")
            + "\n| `table_that_went_away` | wiped | gone from the schema |\n",
            encoding="utf-8",
        )
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("unknown-table", kinds(findings))
        self.assertIn("table_that_went_away", {f.table for f in findings})

    def test_the_code_wiping_a_table_the_document_calls_not_profile_content_fails(self):
        build_tree(
            self.root,
            wiped={table: _call_for(table) for table in TABLES},
            left_alone=[TABLES[0]],
        )
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("wipes-what-it-must-not", kinds(findings))
        self.assertIn("wiped-but-not-documented", kinds(findings))
        self.assertIn(TABLES[0], {f.table for f in findings})

    def test_the_document_claiming_more_than_the_code_does_fails(self):
        build_tree(self.root)
        doc = self.root / "docs/architecture/wipe-inventory.md"
        doc.write_text(
            doc.read_text(encoding="utf-8")
            + f"\n| `{TABLES[-1]}` | wiped | claims more than the code does |\n",
            encoding="utf-8",
        )
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("documented-but-not-wiped", kinds(findings))
        self.assertIn(TABLES[-1], {f.table for f in findings})

    def test_a_wiped_table_with_no_delete_fails(self):
        build_tree(self.root, deletes={})
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("no-scoped-delete", kinds(findings))

    def test_a_delete_without_a_where_clause_fails(self):
        build_tree(
            self.root,
            deletes={
                table: f'@Query("DELETE FROM {table}")'
                for table in TABLES[: len(TABLES) // 2]
            },
        )
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("no-scoped-delete", kinds(findings))

    def test_a_plan_that_names_a_call_the_reset_does_not_make_fails(self):
        build_tree(self.root, impl="        // the reset forgets the deletes")
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("call-not-made", kinds(findings))

    def test_a_reset_that_makes_a_call_the_plan_does_not_name_fails(self):
        build_tree(
            self.root,
            impl="        deps.dao_extra.deleteAllForUser(uid)",
        )
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("call-not-planned", kinds(findings))

    def test_a_scan_that_read_nothing_is_a_finding(self):
        build_tree(self.root)
        entities = self.root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/Entities.kt"
        entities.write_text("// no entities here\n", encoding="utf-8")
        code, findings = run_gate(self.root)
        self.assertEqual(1, code)
        self.assertIn("scan-found-nothing", kinds(findings))

    def test_the_finding_names_the_table_and_the_file(self):
        build_tree(self.root)
        doc = self.root / "docs/architecture/wipe-inventory.md"
        doc.write_text(
            doc.read_text(encoding="utf-8")
            + f"\n| `{TABLES[-1]}` | wiped | claims more than the code does |\n",
            encoding="utf-8",
        )
        _, findings = run_gate(self.root)
        rendered = "\n".join(finding.render() for finding in findings)
        self.assertIn(TABLES[-1], rendered)
        self.assertIn("ProfileWipePlan.kt", rendered)


class ProfileResetInventoryAgainstTheRepositoryTest(unittest.TestCase):
    """The gate passes on the repository it ships with."""

    def test_the_repository_agrees_with_itself(self):
        inv = mod.collect(REPO_ROOT)
        findings = mod._not_vacuous(inv) + mod.check(inv)
        self.assertEqual([], findings, "the shipped tree must satisfy its own gate")
        self.assertGreaterEqual(len(inv.schema_tables), 20)


if __name__ == '__main__':
    unittest.main()
