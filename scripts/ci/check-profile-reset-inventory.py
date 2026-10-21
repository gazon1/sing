#!/usr/bin/env python3
"""check-profile-reset-inventory.py — keep the profile reset's table list true.

The E2E suite isolates one flow from the next by wiping the app's content
between flows. That wipe reaches the tables it names and leaves the others
alone, and the list of "the others" is the dangerous part: a table added to the
Room schema is not a row in a document, it is a row in a database, so the
document that says "the reset reaches the whole profile" stays true in the
prose long after it stops being true in the code.

So the list is checked against the two things it can drift from:

* the **Room schema** — the `@Entity` annotations in `shared/src/commonMain`;
* the **wipe's own list** — `core/wipe/ProfileWipePlan.kt`, the map the reset
  is built from;
* the **inventory** — `docs/architecture/wipe-inventory.md`, the document that
  gives every table a verdict and a reason.

The gate fails when:

* a table exists in the schema that the inventory does not name, or names
  without a verdict — the new table nobody decided about;
* the inventory names a table the schema no longer has — the table that was
  renamed or dropped;
* the code wipes a table the inventory calls "not profile content", or the
  inventory calls "wiped" a table the code does not reach — the two lists
  disagreeing about the same table;
* a table the code claims to wipe has no profile-scoped `DELETE FROM` in the
  sources, or the delete has no `WHERE` — a list that names a table nothing
  deletes, or a sweep of the live rows;
* a call the plan names is not a call the reset makes, or the reset makes a
  wipe call the plan does not name — the list and the code drifting apart;
* a full-text index is not accounted for — an index nobody decided about, an
  index that does not mirror the table the list says it mirrors, or an index
  whose content table the reset does not delete (which would leave the
  profile's words in the index after the reset).

It does not check that the deletes do what they claim. That is the JVM test's
job (`ProfileResetReachesTheWholeProfileTest`), which seeds one row in every
table and says what is left afterwards. A gate that reads the code and a test
that runs the code are two kinds of evidence, and neither replaces the other.

Usage:
    python scripts/ci/check-profile-reset-inventory.py [--repo-root PATH]

Exit codes:
    0  The schema, the wipe's list and the inventory agree
    1  They disagree (blocking mode)
    2  Usage / file-not-found error
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import click

_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run  # noqa: E402

#: The Room entities live here. A table is whatever an `@Entity` declares.
SHARED_MAIN = Path("shared/src/commonMain/kotlin/com/singularity/todo")

#: The wipe's own list: the map from table to the call that deletes it.
WIPE_PLAN = SHARED_MAIN / "core/wipe/ProfileWipePlan.kt"

#: The implementation: the calls the reset actually makes.
WIPE_IMPL = SHARED_MAIN / "core/wipe/ProfileContentResetter.kt"

#: The document: the verdicts, and the reasons.
INVENTORY = Path("docs/architecture/wipe-inventory.md")

#: The verdicts the inventory may give a table.
VERDICTS = ("wiped", "not profile content", "emptied by consequence", "unresolved")

#: `tableName = "tasks"` in an `@Entity` annotation.
_TABLE_NAME_RE = re.compile(r'tableName\s*=\s*"([A-Za-z0-9_]+)"')

#: `"tasks" to "taskRepository.deleteAllForCurrentProfile",` in the plan.
_PLAN_ENTRY_RE = re.compile(r'"([A-Za-z0-9_]+)"\s+to\s+"([A-Za-z0-9_.]+)"')

#: `data class TaskEntity(` — the class an entity is declared as.
_ENTITY_CLASS_RE = re.compile(r"data class\s+([A-Za-z0-9_]+)")

#: `@Fts4(contentEntity = TaskEntity::class` — the table an index mirrors.
_FTS_CONTENT_RE = re.compile(r"@Fts4\s*\(\s*contentEntity\s*=\s*([A-Za-z0-9_]+)::class")

#: `deps.taskRepository.deleteAllForCurrentProfile()` in the implementation.
_WIPE_CALL_RE = re.compile(
    r"\b([A-Za-z0-9_]+)\.(deleteAllForUser|deleteAllForCurrentProfile|clearAll)\s*\("
)

#: `DELETE FROM tasks WHERE ...` anywhere in the sources.
_DELETE_FROM_RE = re.compile(r"DELETE\s+FROM\s+([A-Za-z0-9_]+)", re.IGNORECASE)

#: A row of the inventory: `| `tasks` | wiped | ... |`
_INVENTORY_ROW_RE = re.compile(
    r"^\|\s*`([A-Za-z0-9_]+)`\s*\|\s*([^|]+?)\s*\|",
    re.MULTILINE,
)


@dataclass
class Finding:
    """One disagreement, with the file and the table it is about."""

    kind: str
    table: str
    detail: str
    where: str = ""

    def render(self) -> str:
        head = f"{self.kind}: {self.table}"
        if self.where:
            head += f" ({self.where})"
        return f"{head} — {self.detail}"


@dataclass
class Inventory:
    """What the three sources say."""

    schema_tables: set[str] = field(default_factory=set)
    plan: dict[str, str] = field(default_factory=dict)
    consequence: dict[str, str] = field(default_factory=dict)
    calls_in_impl: set[str] = field(default_factory=set)
    verdicts: dict[str, str] = field(default_factory=dict)
    scoped_deletes: set[str] = field(default_factory=set)
    indexes: dict[str, str] = field(default_factory=dict)


def _kotlin_map_entries(text: str, name: str) -> dict[str, str]:
    """The entries of `val <name> = mapOf(...)` — one Kotlin map literal.

    Scoped to the named map on purpose: the plan holds more than one, and a
    regex over the whole file would read the indexes as tables the reset
    deletes.
    """
    anchor = text.find(f"val {name}")
    if anchor == -1:
        return {}
    literal = text.find("mapOf(", anchor)
    if literal == -1:
        return {}
    start = text.find("(", literal)
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                block = text[start:index]
                return {table: call for table, call in _PLAN_ENTRY_RE.findall(block)}
    return {}


def _entity_classes(text: str) -> dict[str, str]:
    """The entity class → table map of one Kotlin file.

    Read line by line because an `@Entity` annotation may put its `tableName`
    on the next line, and because the annotation may carry other arguments
    with parentheses of their own (`indices = [Index("user_id")]`).
    """
    mapping: dict[str, str] = {}
    pending: str | None = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("@Entity"):
            pending = None
            found = _TABLE_NAME_RE.search(stripped)
            if found:
                pending = found.group(1)
            continue
        if pending is None:
            found = _TABLE_NAME_RE.search(stripped)
            if found:
                pending = found.group(1)
                continue
        declared = _ENTITY_CLASS_RE.search(stripped)
        if declared:
            if pending is not None:
                mapping[declared.group(1)] = pending
            pending = None
    return mapping


def _index_tables(text: str) -> dict[str, str]:
    """The index table → content entity class map of one Kotlin file.

    An index is an `@Entity` whose `@Fts4` names the entity it mirrors. The
    table the index itself is declared with is the table just above the
    annotation, so the two are read together.
    """
    indexes: dict[str, str] = {}
    pending: str | None = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("@Entity"):
            pending = None
            found = _TABLE_NAME_RE.search(stripped)
            if found:
                pending = found.group(1)
            continue
        if pending is None:
            found = _TABLE_NAME_RE.search(stripped)
            if found:
                pending = found.group(1)
                continue
        fts = _FTS_CONTENT_RE.search(stripped)
        if fts and pending is not None:
            indexes[pending] = fts.group(1)
    return indexes


def _kotlin_sources(root: Path) -> list[Path]:
    return sorted((root / SHARED_MAIN).rglob("*.kt"))


def _read(path: Path, root: Path) -> str:
    full = root / path
    if not full.exists():
        raise FileNotFoundError(str(full))
    return full.read_text(encoding="utf-8")


def collect(root: Path) -> Inventory:
    """Read the schema, the wipe's list, the implementation and the document."""
    inv = Inventory()

    if not (root / SHARED_MAIN).is_dir():
        raise FileNotFoundError(str(root / SHARED_MAIN))

    # The entity class → table map is global: an index names the entity it
    # mirrors, and that entity is declared in another file.
    class_to_table: dict[str, str] = {}

    for source in _kotlin_sources(root):
        text = source.read_text(encoding="utf-8")
        inv.schema_tables.update(_TABLE_NAME_RE.findall(text))
        class_to_table.update(_entity_classes(text))
        for index, content_class in _index_tables(text).items():
            inv.indexes[index] = class_to_table.get(content_class, content_class)
        for match in _DELETE_FROM_RE.finditer(text):
            table = match.group(1)
            # The statement ends at the closing quote of the query string. A
            # delete without a WHERE inside it is a sweep of the table's live
            # rows, which is exactly what the reset must not be.
            tail = text[match.end() :]
            end = min(
                (i for i in (tail.find('"'), tail.find('"""')) if i != -1),
                default=len(tail),
            )
            if "WHERE" in tail[:end].upper():
                inv.scoped_deletes.add(table)

    plan_text = _read(WIPE_PLAN, root)
    inv.plan = _kotlin_map_entries(plan_text, "WIPED")
    inv.consequence = _kotlin_map_entries(plan_text, "EMPTIED_BY_CONSEQUENCE")

    impl_text = _read(WIPE_IMPL, root)
    inv.calls_in_impl = {
        f"{owner}.{method}" for owner, method in _WIPE_CALL_RE.findall(impl_text)
    }

    doc_text = _read(INVENTORY, root)
    for table, verdict in _INVENTORY_ROW_RE.findall(doc_text):
        verdict = verdict.strip().lower()
        if verdict in VERDICTS:
            inv.verdicts[table] = verdict

    return inv


def check(inv: Inventory) -> list[Finding]:
    """Compare the three sources and report every disagreement."""
    findings: list[Finding] = []

    doc_wiped = {t for t, v in inv.verdicts.items() if v == "wiped"}
    doc_left = {t for t, v in inv.verdicts.items() if v == "not profile content"}
    doc_consequence = {t for t, v in inv.verdicts.items() if v == "emptied by consequence"}
    doc_unresolved = {t for t, v in inv.verdicts.items() if v == "unresolved"}
    plan_tables = set(inv.plan)
    consequence_tables = set(inv.consequence)

    # R1 — every table in the schema has a verdict.
    for table in sorted(inv.schema_tables - set(inv.verdicts)):
        findings.append(
            Finding(
                kind="no-verdict",
                table=table,
                detail=(
                    "the schema has this table but the inventory does not decide what "
                    "a profile reset does with it. Add a row to the inventory with a "
                    "verdict and the reason."
                ),
                where=str(INVENTORY),
            )
        )

    # R2 — every table the inventory names is in the schema.
    for table in sorted(set(inv.verdicts) - inv.schema_tables):
        findings.append(
            Finding(
                kind="unknown-table",
                table=table,
                detail=(
                    "the inventory names this table but the Room schema has no such "
                    "table. It was renamed or dropped; fix the inventory."
                ),
                where=str(INVENTORY),
            )
        )

    # R3 — the two lists of wiped tables are the same list.
    for table in sorted(doc_wiped - plan_tables):
        findings.append(
            Finding(
                kind="documented-but-not-wiped",
                table=table,
                detail=(
                    "the inventory calls this wiped but the wipe's own list does not "
                    "reach it. Either the code is missing the delete or the verdict "
                    "is wrong."
                ),
                where=str(WIPE_PLAN),
            )
        )
    for table in sorted(plan_tables - doc_wiped):
        verdict = inv.verdicts.get(table, "unresolved")
        findings.append(
            Finding(
                kind="wiped-but-not-documented",
                table=table,
                detail=(
                    f"the wipe reaches this table but the inventory calls it "
                    f"{verdict!r}. The code and the document must agree on what a "
                    f"profile reset destroys."
                ),
                where=str(WIPE_PLAN),
            )
        )

    # R4 — a table that is not profile content must not be in the wipe's list.
    # (Covered by R3, spelled out because it is the failure that destroys state:
    # the sync cursor, the profile row, the usage log.)
    for table in sorted(doc_left & plan_tables):
        findings.append(
            Finding(
                kind="wipes-what-it-must-not",
                table=table,
                detail=(
                    "the inventory calls this table 'not profile content' but the "
                    "wipe's list deletes it. Wiping it destroys state the E2E run "
                    "depends on."
                ),
                where=str(WIPE_PLAN),
            )
        )

    # R5 — an unresolved verdict means the table stays in the database.
    for table in sorted(doc_unresolved & plan_tables):
        findings.append(
            Finding(
                kind="wipes-an-unresolved-table",
                table=table,
                detail=(
                    "the inventory leaves this table unresolved, so the reset must "
                    "not delete it."
                ),
                where=str(WIPE_PLAN),
            )
        )

    # R6 — every wiped table has a profile-scoped delete in the sources.
    for table in sorted(plan_tables):
        if table not in inv.scoped_deletes:
            findings.append(
                Finding(
                    kind="no-scoped-delete",
                    table=table,
                    detail=(
                        "the wipe's list claims this table, but no `DELETE FROM "
                        f"{table}` with a WHERE clause exists in the shared sources. "
                        "Either the delete is missing or it is a sweep of the live "
                        "rows."
                    ),
                    where=str(SHARED_MAIN),
                )
            )

    # R7 — the calls the plan names are calls the reset makes, and every wipe
    # call the reset makes is a call the plan names.
    for table, call in sorted(inv.plan.items()):
        if call not in inv.calls_in_impl:
            findings.append(
                Finding(
                    kind="call-not-made",
                    table=table,
                    detail=(
                        f"the plan says {call} deletes this table, but the reset "
                        f"never makes that call."
                    ),
                    where=str(WIPE_IMPL),
                )
            )
    for call in sorted(inv.calls_in_impl - set(inv.plan.values())):
        findings.append(
            Finding(
                kind="call-not-planned",
                table=call,
                detail=(
                    f"the reset makes {call}, but the wipe's list does not name it. "
                    f"Either the plan is out of date or the call deletes something "
                    f"the inventory does not account for."
                ),
                where=str(WIPE_IMPL),
            )
        )

    # R8 — the two lists of index tables are the same list. The document and the
    # plan each say which tables the reset reaches without a delete of their own.
    for table in sorted(doc_consequence - consequence_tables):
        findings.append(
            Finding(
                kind="documented-but-not-planned",
                table=table,
                detail=(
                    "the inventory calls this emptied by consequence, but the "
                    "wipe's list does not name it. Either the list is out of date "
                    "or the verdict is wrong."
                ),
                where=str(WIPE_PLAN),
            )
        )
    for table in sorted(consequence_tables - doc_consequence):
        findings.append(
            Finding(
                kind="planned-but-not-documented",
                table=table,
                detail=(
                    "the wipe's list empties this table by consequence, but the "
                    "inventory does not say so. The document must give every table "
                    "the reset reaches a verdict and a reason."
                ),
                where=str(INVENTORY),
            )
        )

    # R9 — an index mirrors the table the list says it mirrors. The link is read
    # from the Room annotations, not from the list, so a wrong claim in the
    # list is caught rather than believed.
    for table, content in sorted(inv.consequence.items()):
        actual = inv.indexes.get(table)
        if actual is None:
            findings.append(
                Finding(
                    kind="index-that-is-not-an-index",
                    table=table,
                    detail=(
                        "the wipe's list empties this table because the table it "
                        "mirrors is deleted, but the schema declares no full-text "
                        "index here. The reason it is emptied does not exist."
                    ),
                    where=str(SHARED_MAIN),
                )
            )
            continue
        if actual != content:
            findings.append(
                Finding(
                    kind="index-mirrors-another-table",
                    table=table,
                    detail=(
                        f"the wipe's list says this index mirrors `{content}`, but "
                        f"the schema says it mirrors `{actual}`. One of the two is "
                        "wrong, and the wrong one would leave the wrong index "
                        "behind."
                    ),
                    where=str(SHARED_MAIN),
                )
            )

    # R10 — the mirrored table is one the reset deletes. An index whose content
    # table survives keeps the profile's words after the reset, and the search
    # finds them.
    for table, content in sorted(inv.consequence.items()):
        if content not in plan_tables:
            findings.append(
                Finding(
                    kind="index-outlives-its-content",
                    table=table,
                    detail=(
                        f"this index mirrors `{content}`, which the reset does not "
                        "delete. The index keeps the profile's rows after the "
                        "reset, and a search finds them."
                    ),
                    where=str(WIPE_PLAN),
                )
            )

    # R11 — every index in the schema is accounted for. An index is a table,
    # and a table nobody decided about is the failure this gate exists for.
    for table in sorted(set(inv.indexes) - consequence_tables):
        findings.append(
            Finding(
                kind="index-nobody-decided-about",
                table=table,
                detail=(
                    "the schema declares this full-text index, but neither the "
                    "inventory nor the wipe's list says what a profile reset does "
                    "with it. Add it to the inventory with a verdict and a reason."
                ),
                where=str(INVENTORY),
            )
        )

    # R12 — an index is emptied by consequence, never deleted by a call of its
    # own. Nothing writes to an index table, so a delete aimed at it would be a
    # delete of a table Room keeps in step by itself.
    for table in sorted(doc_consequence & plan_tables):
        findings.append(
            Finding(
                kind="index-in-the-delete-plan",
                table=table,
                detail=(
                    "the inventory calls this emptied by consequence, but the "
                    "wipe's list deletes it directly. The index holds no rows of "
                    "its own; the delete of the table it mirrors is what empties "
                    "it."
                ),
                where=str(WIPE_PLAN),
            )
        )

    return findings


def _not_vacuous(inv: Inventory) -> list[Finding]:
    """A gate that read nothing must not report that nothing is wrong."""
    findings: list[Finding] = []
    if len(inv.schema_tables) < 20:
        findings.append(
            Finding(
                kind="scan-found-nothing",
                table="-",
                detail=(
                    f"only {len(inv.schema_tables)} Room tables were found under "
                    f"{SHARED_MAIN}. The scan is not reading the schema, so every "
                    f"other rule below it is vacuously satisfied."
                ),
                where=str(SHARED_MAIN),
            )
        )
    if not inv.plan:
        findings.append(
            Finding(
                kind="scan-found-nothing",
                table="-",
                detail=f"no entries were read from {WIPE_PLAN}.",
                where=str(WIPE_PLAN),
            )
        )
    if not inv.verdicts:
        findings.append(
            Finding(
                kind="scan-found-nothing",
                table="-",
                detail=f"no verdicts were read from {INVENTORY}.",
                where=str(INVENTORY),
            )
        )
    return findings


@click.command()
@click.option(
    "--repo-root",
    type=click.Path(file_okay=False, path_type=Path),
    default=Path("."),
    show_default=True,
    help="The repository root to read the schema, the wipe and the inventory from.",
)
@click.pass_context
def cli(ctx: click.Context, repo_root: Path) -> None:
    """Fail when the schema, the wipe's list and the inventory disagree."""
    root = repo_root.resolve()
    try:
        inv = collect(root)
    except FileNotFoundError as exc:
        click.echo(f"::error::file not found: {exc}", err=True)
        raise ctx.exit(IO_ERROR)

    findings = _not_vacuous(inv) + check(inv)

    if findings:
        click.echo("::group::Profile reset inventory is out of step")
        for finding in findings:
            click.echo(f"::error::{finding.render()}")
        click.echo("::endgroup::")
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo(
        f"profile reset inventory agrees: {len(inv.schema_tables)} tables, "
        f"{len(inv.plan)} wiped, {len(inv.consequence)} emptied by consequence, "
        f"{len(inv.verdicts)} verdicts"
    )
    raise ctx.exit(CLEAN)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
