#!/usr/bin/env python3
"""check-platform-reference-ports.py — the platform reference against the code.

`docs/PLATFORM-REFERENCE.md` is the inventory of the platform seam: which
port is bound to which implementation on which platform. The document said so
itself — "nothing here is generated … no gate will notice" — and it had rotted:
it still named `NotificationPort` (deleted; the port is `Notifier` now) and
`TimeZoneProvider` (gone), and it did not name the twenty ports that the seam
actually has.

So the reference is checked against the code:

* every port the code declares appears in the reference's ports table;
* every row of the table names a port that exists in the code;
* the implementation a row names exists in the platform source set it is
  attributed to.

What a port is, and where the set comes from
--------------------------------------------

A **port** is an interface declared in a `commonMain` source set that is bound
in a platform module (`PlatformModule.jvm.kt` / `PlatformModule.android.kt`).
That is the rule the repository states in `AGENTS.md` — "a port is an interface
in `commonMain` with two actuals and a `single<Port>` in both
`PlatformModule.*.kt`" — and the gate reads it from the code rather than from
a list: the bound type names come from the platform modules, the interface
declarations from the `commonMain` source sets.

Two kinds of binding are excluded, and the exclusion is data rather than a
guess:

* **infrastructure** — a binding that is not a behavioural seam (`AppDatabase`,
  `DataStore`, `CoroutineScope`, `UnitOfWork`). The seam registry
  (`shared/src/jvmTest/resources/platform-seams.tsv`) classifies them, and it
  is already held against the platform modules by
  `PlatformSeamGuardTest`.
* a binding that is not an interface at all — a class bound directly, such as
  the database.

A binding that is bound on only one platform is still a port, and the row says
so: the other platform's cell is `-`.

What is checked, and what is not
--------------------------------

The table's fourth column says what the gate does with the row: `checked` for
the rows whose port and implementations are compared with the code, `prose` for
the rows the gate does not compare (a type that is not a port, a row that
describes a mechanism rather than a binding).

The gate does not check **why** an implementation is bound where it is, whether
it is inert, or whether the document's prose about the seam is still true. The
seam registry holds that: it classifies every binding as `real`, `stub` or
`exempt`, with a reason, and `PlatformSeamGuardTest` holds the registry to the
code. The reference is the reader's map; the registry is the authority.

The document's other sections — the factory functions, the time helpers, the
navigation seams — are **prose**, and are not checked by this gate. The
document says so.

Usage:
    python3 scripts/check-platform-reference-ports.py [--repo-root PATH]

Exit codes:
    0  The reference and the code agree
    1  They disagree (blocking mode)
    2  A file the gate reads is missing
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

import click

_SRC = Path(__file__).resolve().parent.parent / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run  # noqa: E402

#: The document: the reader's map of the seam.
DOCUMENT = Path("docs/PLATFORM-REFERENCE.md")

#: The platform modules: the bindings.
JVM_MODULE = Path("shared/src/jvmMain/kotlin/com/singularity/todo/core/di/PlatformModule.jvm.kt")
ANDROID_MODULE = Path(
    "shared/src/androidMain/kotlin/com/singularity/todo/core/di/PlatformModule.android.kt"
)

#: The platform source sets: where an implementation has to exist. A common
#: class may serve both platforms, so the common source sets are visible to
#: both columns.
JVM_ROOTS = (
    Path("shared/src/jvmMain/kotlin"),
    Path("core-base/src/jvmMain/kotlin"),
)
ANDROID_ROOTS = (
    Path("shared/src/androidMain/kotlin"),
    Path("core-base/src/androidMain/kotlin"),
)

#: The `commonMain` source sets: where a port has to be declared.
COMMON_ROOTS = (
    Path("shared/src/commonMain/kotlin"),
    Path("core-base/src/commonMain/kotlin"),
    Path("adr-core/src/commonMain/kotlin"),
)

#: The seam registry: the classification of every binding.
SEAM_REGISTRY = Path("shared/src/jvmTest/resources/platform-seams.tsv")

#: The header of the ports table inside the document.
TABLE_HEADER = "| Port | jvmMain | androidMain | Checked |"

#: `single<Port> {` — a typed binding.
_BINDING_RE = re.compile(r"\b(?:single|factory|viewModelOf|factoryOf|singleOf)\s*<")

#: `interface Port` / `fun interface Port` — a declaration of a port.
_INTERFACE_RE = re.compile(r"^\s*(?:(?:public|internal|private)\s+)?(?:fun\s+)?interface\s+(\w+)", re.MULTILINE)

#: `class Impl` / `object Impl` / `fun impl(` — a declaration of an implementation.
_DECLARATION_RE = re.compile(r"\b(?:class|object|interface|fun)\s+(\w+)")


@dataclass(frozen=True)
class Row:
    """One row of the reference's ports table."""

    port: str
    jvm: str
    android: str
    checked: str


@dataclass
class Finding:
    """One disagreement, with the port it is about."""

    kind: str
    port: str
    detail: str

    def render(self) -> str:
        return f"{self.kind}: {self.port} — {self.detail}"


def _read(path: Path, root: Path) -> str:
    full = root / path
    if not full.is_file():
        raise FileNotFoundError(str(full))
    return full.read_text(encoding="utf-8")


def _closing_angle(text: str, open_at: int) -> int:
    depth = 0
    for index in range(open_at, len(text)):
        char = text[index]
        if char == "<":
            depth += 1
        elif char == ">":
            depth -= 1
            if depth == 0:
                return index
    return -1


def bound_type_names(source: str) -> set[str]:
    """The types bound by a typed Koin binding in a platform module.

    The untyped `single { get<AppDatabase>().taskDao() }` is invisible here on
    purpose: a DAO accessor is constructor-injected, it is not a seam.
    """
    names: set[str] = set()
    for match in _BINDING_RE.finditer(source):
        open_at = source.index("<", match.start())
        close_at = _closing_angle(source, open_at)
        if close_at == -1:
            continue
        type_name = source[open_at + 1 : close_at].split("<")[0].strip()
        if type_name:
            names.add(type_name.split(".")[-1])
    return names


def declared_interfaces(root: Path) -> set[str]:
    """Every interface declared in a `commonMain` source set."""
    names: set[str] = set()
    for common in COMMON_ROOTS:
        base = root / common
        if not base.is_dir():
            continue
        for source in base.rglob("*.kt"):
            names.update(_INTERFACE_RE.findall(source.read_text(encoding="utf-8")))
    return names


def declared_types(root: Path, platform_roots: tuple[Path, ...]) -> set[str]:
    """Every type or function a platform can see.

    A platform sees its own source set and the common ones: an implementation
    may be a common class that both platforms bind.
    """
    names: set[str] = set()
    for source_root in platform_roots + COMMON_ROOTS:
        base = root / source_root
        if not base.is_dir():
            continue
        for source in base.rglob("*.kt"):
            names.update(_DECLARATION_RE.findall(source.read_text(encoding="utf-8")))
    return names


def infrastructure_ports(root: Path) -> set[str]:
    """The bindings the seam registry classifies as infrastructure.

    The registry is the authority on what is a behavioural seam: it is held
    against the platform modules by `PlatformSeamGuardTest`, so a binding that
    is not in it is already a finding there.
    """
    text = _read(SEAM_REGISTRY, root)
    ports: set[str] = set()
    for line in text.splitlines():
        stripped = line.split("#")[0].strip()
        if not stripped:
            continue
        fields = [field.strip() for field in stripped.split("|")]
        if len(fields) >= 2 and fields[1] == "infra":
            ports.add(fields[0])
    return ports


def code_ports(root: Path) -> set[str]:
    """The ports the code declares.

    A port is an interface in a `commonMain` source set that a platform module
    binds — on one platform or on both.
    """
    jvm = bound_type_names(_read(JVM_MODULE, root))
    android = bound_type_names(_read(ANDROID_MODULE, root))
    interfaces = declared_interfaces(root)
    infrastructure = infrastructure_ports(root)
    return (jvm | android) & interfaces - infrastructure


def parse_table(document: str) -> list[Row]:
    """The rows of the ports table in the document."""
    lines = document.splitlines()
    start = -1
    for index, line in enumerate(lines):
        if line.strip() == TABLE_HEADER:
            start = index + 2
            break
    if start == -1:
        return []

    rows: list[Row] = []
    for line in lines[start:]:
        stripped = line.strip()
        if not stripped.startswith("|"):
            break
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if len(cells) != 4:
            break
        rows.append(
            Row(
                port=_identifier(cells[0]),
                jvm=_identifier(cells[1]),
                android=_identifier(cells[2]),
                checked=cells[3].strip().lower(),
            )
        )
    return rows


def _identifier(cell: str) -> str:
    """The identifier a cell names, or `-` when the cell names none."""
    cleaned = cell.replace("`", "").strip()
    if not cleaned or cleaned == "-":
        return ""
    return cleaned.split()[0]


def check(rows: list[Row], ports: set[str], root: Path) -> list[Finding]:
    """Compare the reference with the code and report every disagreement."""
    findings: list[Finding] = []
    jvm_types = declared_types(root, JVM_ROOTS)
    android_types = declared_types(root, ANDROID_ROOTS)

    for port in sorted(ports):
        if not any(row.port == port for row in rows):
            findings.append(
                Finding(
                    kind="not-in-the-reference",
                    port=port,
                    detail=(
                        "the code binds this port but the reference does not name it — "
                        "add a row to the ports table"
                    ),
                )
            )

    for row in rows:
        if row.checked not in {"checked", "prose"}:
            findings.append(
                Finding(
                    kind="unknown-verdict",
                    port=row.port or "-",
                    detail=(
                        f"the row's Checked cell says {row.checked!r}; it has to say "
                        "'checked' or 'prose'"
                    ),
                )
            )
        if row.checked == "prose":
            continue
        if row.port not in ports:
            findings.append(
                Finding(
                    kind="not-in-the-code",
                    port=row.port or "-",
                    detail=(
                        "the reference names this port but the code does not bind it — "
                        "it was renamed or removed; fix the reference"
                    ),
                )
            )
        if row.jvm and row.jvm not in jvm_types:
            findings.append(
                Finding(
                    kind="implementation-not-found",
                    port=row.port or "-",
                    detail=(
                        f"the reference names {row.jvm!r} as the JVM implementation, but "
                        "no such type is declared in the JVM source set"
                    ),
                )
            )
        if row.android and row.android not in android_types:
            findings.append(
                Finding(
                    kind="implementation-not-found",
                    port=row.port or "-",
                    detail=(
                        f"the reference names {row.android!r} as the Android "
                        "implementation, but no such type is declared in the Android "
                        "source set"
                    ),
                )
            )
    return findings


def _not_vacuous(rows: list[Row], ports: set[str]) -> list[Finding]:
    """The scan has to have read something, or every rule above is vacuous."""
    findings: list[Finding] = []
    if not rows:
        findings.append(
            Finding(
                kind="scan-found-nothing",
                port="-",
                detail=(
                    f"no ports table was read from {DOCUMENT}. The header "
                    f"{TABLE_HEADER!r} is not there, so nothing below was checked."
                ),
            )
        )
    if not ports:
        findings.append(
            Finding(
                kind="scan-found-nothing",
                port="-",
                detail=(
                    "no port was read from the platform modules. Every rule below it "
                    "is vacuously satisfied."
                ),
            )
        )
    return findings


@click.command()
@click.option(
    "--repo-root",
    type=click.Path(file_okay=False, path_type=Path),
    default=Path("."),
    show_default=True,
    help="The repository root to read the reference and the code from.",
)
@click.pass_context
def cli(ctx: click.Context, repo_root: Path) -> None:
    """Fail when the platform reference and the code disagree."""
    root = repo_root.resolve()
    try:
        document = _read(DOCUMENT, root)
        rows = parse_table(document)
        ports = code_ports(root)
    except FileNotFoundError as exc:
        click.echo(f"::error::file not found: {exc}", err=True)
        raise ctx.exit(IO_ERROR)

    findings = _not_vacuous(rows, ports) + check(rows, ports, root)

    if findings:
        click.echo("::group::The platform reference is out of step")
        for finding in findings:
            click.echo(f"::error::{finding.render()}")
        click.echo("::endgroup::")
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo(
        f"the platform reference agrees with the code: {len(ports)} port(s), "
        f"{len(rows)} row(s)"
    )
    raise ctx.exit(CLEAN)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
