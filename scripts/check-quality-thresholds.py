#!/usr/bin/env python3
"""check-quality-thresholds.py — the quality page must state the thresholds the tree enforces.

`docs/architecture/quality.md` carries the table of thresholds this repository
uses: the value here, detekt's own default, and the reason. Every cell was
written by hand, and nothing compared it with the configuration it describes, so
the page drifted — twice. The rows for the three rules ktlint now owns claimed
detekt shipped all three "on", while the pinned detekt ships only
`WildcardImport` on; and `TooManyFunctions` still claims a file default of 10
that the pinned detekt does not ship. The page's own rule — "a change to a
threshold updates this file in the same commit" — catches a committed edit. It
cannot catch a value that was already wrong, and a reader cannot tell a checked
number from a guessed one.

## The two sources of truth

- **Here** — ``config/detekt/detekt.yml``, merged over the shipped default for
  any rule or option the config does not mention. A rule the config is silent
  about runs on detekt's default, so that is what "here" means.
- **Detekt default** — the ``default-detekt-config.yml`` inside the
  ``detekt-core`` jar of the version pinned in ``gradle/libs.versions.toml``.
  The version is read from the catalog, so the check follows the pin rather
  than a copy of it.

A value cell is a comma-separated list of ``option=value`` pairs —
``active=off``, ``allowedLines=80``. Prose in a value cell is a finding: a
number a machine cannot read is a number nobody checks.

## What it does not do

It does not inventory the rules. A rule absent from this page is not a finding:
the page describes the rules a reader needs, and turning it into a checklist of
every configured rule would make it a file nobody reads.

## The positive control

``scripts/src/sgate/commands/_registry.py`` declares a control that turns the
``TooManyFunctions`` default back into the ``10`` this page used to claim, and
the gate must fail on it. ``scripts/tests/test_check_quality_thresholds.py``
proves both directions with fixtures — a wrong *Here* value and a wrong
*default* value — because one control can only prove one of the two columns.
"""
from __future__ import annotations

import os
import pathlib
import re
import sys
import urllib.request
import zipfile

import click
import yaml

# This script must resolve `sgate` against *this* checkout: an editable install
# of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = ROOT / "docs/architecture/quality.md"
CONFIG = ROOT / "config/detekt/detekt.yml"
CATALOG = ROOT / "gradle/libs.versions.toml"

#: The heading the table lives under. The page has other tables; this one is the
#: one whose values the check reads.
SECTION = "## The thresholds this repository uses"

#: The entry the pinned detekt ships its own defaults under, inside detekt-core.
DEFAULT_CONFIG_ENTRY = "default-detekt-config.yml"

#: Where the pinned jar is fetched from when no Gradle home has it.
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

#: Where that jar is kept, so a second run reads it from disk instead of
#: fetching it again.
DETEKT_CORE_CACHE = pathlib.Path.home() / ".cache" / "ropefish" / "detekt-core"

#: Top-level blocks of a detekt config that are not rule sets.
NON_RULE_SECTIONS = {"config", "processors", "console-reports"}

#: A sentinel for "neither the config nor the shipped default states this".
MISSING = object()

TRUE_WORDS = {"on", "true", "yes"}
FALSE_WORDS = {"off", "false", "no"}

DETEKT_VERSION = re.compile(r'^detekt\s*=\s*"([^"]+)"', re.MULTILINE)


class Row:
    """One row of the table: what the page says, and where it says it."""

    def __init__(self, line: int, rule: str, here: str, default: str) -> None:
        self.line = line
        self.rule = rule
        self.here = here
        self.default = default


def _display(path: pathlib.Path) -> str:
    """A path relative to the repository, which is what a finding should name."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def _value(raw: object) -> object:
    """Normalise one stated value, so `off`, `false` and False compare equal."""
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, int):
        return raw
    if not isinstance(raw, str):
        return raw
    text = raw.strip().strip("`").strip()
    lowered = text.lower()
    if lowered in TRUE_WORDS:
        return True
    if lowered in FALSE_WORDS:
        return False
    try:
        return int(text)
    except ValueError:
        return text


def parse_cell(cell: str) -> dict[str, object]:
    """The `option=value` assignments in a value cell.

    Raises `ValueError` carrying the offending text, which the finding quotes —
    a cell that cannot be read is the failure, not something to skip.
    """
    assignments: dict[str, object] = {}
    for part in cell.split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            raise ValueError(part)
        option, _, raw = part.partition("=")
        option = option.strip().strip("`").strip()
        if not option:
            raise ValueError(part)
        assignments[option] = _value(raw)
    if not assignments:
        raise ValueError(cell)
    return assignments


def parse_table(text: str) -> list[Row]:
    """The rows of the thresholds table, with the line each one sits on."""
    lines = text.splitlines()
    try:
        heading = next(
            index for index, line in enumerate(lines) if line.strip() == SECTION
        )
    except StopIteration as exc:
        raise ValueError(f"{SECTION!r} is not in the page") from exc

    rows: list[Row] = []
    for index in range(heading + 1, len(lines)):
        line = lines[index]
        if line.startswith("## "):
            break
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) < 4:
            continue
        rule = cells[0].strip("`").strip()
        if not rule or rule == "Rule" or set(cells[0]) <= set("-: "):
            continue
        rows.append(Row(index + 1, rule, cells[1], cells[2]))
    return rows


def rule_index(document: object) -> dict[str, dict[str, object]]:
    """Rule name → its options, across every rule set of a detekt config.

    A rule is a mapping that carries `active`; that is what separates the 25
    rules of a rule set from the rule set's own settings and from the top-level
    `config`/`processors` blocks.
    """
    index: dict[str, dict[str, object]] = {}
    if not isinstance(document, dict):
        return index
    for section, body in document.items():
        if section in NON_RULE_SECTIONS or not isinstance(body, dict):
            continue
        for name, options in body.items():
            if isinstance(options, dict) and "active" in options:
                index[name] = options
    return index


def effective(
    in_force: dict[str, dict[str, object]],
    shipped: dict[str, dict[str, object]],
    rule: str,
    option: str,
) -> object:
    """What this tree enforces for `rule.option`: the config, else the default."""
    block = in_force.get(rule)
    if block is not None and option in block:
        return block[option]
    defaults = shipped.get(rule)
    if defaults is not None and option in defaults:
        return defaults[option]
    return MISSING


def check_documents(
    page_text: str,
    config_text: str,
    defaults_text: str,
    version: str,
) -> list[str]:
    """Every disagreement between the page and its two sources of truth."""
    findings: list[str] = []
    page = _display(PAGE)
    config = _display(CONFIG)

    try:
        rows = parse_table(page_text)
    except ValueError as exc:
        return [f"{page}: {exc}"]
    if not rows:
        return [f"{page}: the thresholds table has no rows"]

    in_force = rule_index(yaml.safe_load(config_text))
    shipped = rule_index(yaml.safe_load(defaults_text))

    for row in rows:
        where = f"{page}:{row.line}"
        if row.rule not in in_force and row.rule not in shipped:
            findings.append(
                f"{where}: `{row.rule}` is not a rule in detekt {version} — "
                f"neither {config} nor the shipped default configuration knows it"
            )
            continue

        try:
            here = parse_cell(row.here)
        except ValueError as exc:
            findings.append(
                f"{where}: `{row.rule}` — the Here cell {exc!r} is not an "
                f"`option=value` list, so nothing can check it"
            )
            here = {}
        try:
            stated_default = parse_cell(row.default)
        except ValueError as exc:
            findings.append(
                f"{where}: `{row.rule}` — the Detekt default cell {exc!r} is not "
                f"an `option=value` list, so nothing can check it"
            )
            stated_default = {}

        for option, stated in here.items():
            actual = effective(in_force, shipped, row.rule, option)
            if actual is MISSING:
                findings.append(
                    f"{where}: `{row.rule}` — the Here cell states `{option}`, "
                    f"which is not an option of that rule"
                )
            elif _value(actual) != stated:
                if option in in_force.get(row.rule, {}):
                    source = f"{config} enforces"
                else:
                    source = f"the config is silent, so detekt {version} ships"
                findings.append(
                    f"{where}: `{row.rule}` — Here says "
                    f"{option}={_shown(stated)}, but {source} "
                    f"{option}={_shown(actual)}"
                )

        for option, stated in stated_default.items():
            defaults = shipped.get(row.rule, {})
            if option not in defaults:
                findings.append(
                    f"{where}: `{row.rule}` — the Detekt default cell states "
                    f"`{option}`, which is not an option of that rule in detekt "
                    f"{version}"
                )
            elif _value(defaults[option]) != stated:
                findings.append(
                    f"{where}: `{row.rule}` — Detekt default says "
                    f"{option}={_shown(stated)}, but detekt {version} ships "
                    f"{option}={_shown(defaults[option])}"
                )

    return findings


def _shown(value: object) -> str:
    """A value as the page spells it: `on`/`off` for booleans, the number bare."""
    if isinstance(value, bool):
        return "on" if value else "off"
    return str(value)


def detekt_version(catalog_text: str) -> str | None:
    """The detekt version the version catalog pins."""
    match = DETEKT_VERSION.search(catalog_text)
    return match.group(1) if match else None


def _gradle_homes() -> list[pathlib.Path]:
    """The Gradle homes a resolved `detekt-core` jar may live in.

    `./gw` gives a worktree its own home, so `GRADLE_USER_HOME` comes first; the
    conventional home is the fallback for a plain `./gradlew` run, and the
    project-local `.gradle` is where a machine that points Gradle at the checkout
    puts it.
    """
    homes: list[pathlib.Path] = []
    configured = os.environ.get("GRADLE_USER_HOME")
    if configured:
        homes.append(pathlib.Path(configured))
    homes.append(pathlib.Path.home() / ".gradle")
    homes.append(ROOT / ".gradle")
    return homes


def shipped_default_config(version: str) -> str | None:
    """`default-detekt-config.yml` from the pinned `detekt-core` jar.

    `DETEKT_CORE_JAR` names the jar directly, which is how a host whose Gradle
    cache lives somewhere unusual runs the gate without changing the script.

    A Gradle home is the first place to look, but it is not the only one that
    counts: the Static gates CI job resolves no detekt artifact of its own —
    the detekt Gradle plugin's own classpath does not pull `detekt-core` — so
    a gate that can only read a Gradle cache never passes there. The jar is a
    public Maven artifact of the version the catalog pins, so the gate fetches
    what it needs instead of asking the job to have run something else first.
    """
    explicit = os.environ.get("DETEKT_CORE_JAR")
    if explicit:
        return _default_config_from(pathlib.Path(explicit))

    for home in _gradle_homes():
        pattern = (
            f"caches/modules-2/files-2.1/dev.detekt/detekt-core/{version}"
            f"/*/detekt-core-{version}.jar"
        )
        for jar in home.glob(pattern):
            text = _default_config_from(jar)
            if text is not None:
                return text

    jar = _fetch_detekt_core(version)
    if jar is not None:
        return _default_config_from(jar)
    return None


def _fetch_detekt_core(version: str) -> pathlib.Path | None:
    """Download the pinned `detekt-core` jar into a private cache.

    The jar is fetched over HTTPS from Maven Central, keyed by the version the
    catalog pins, and a download that does not contain the shipped default
    config is not kept — a truncated jar would otherwise be cached and read as
    truth on every later run.
    """
    jar = DETEKT_CORE_CACHE / version / f"detekt-core-{version}.jar"
    if jar.is_file():
        return jar

    url = f"{MAVEN_CENTRAL}/dev/detekt/detekt-core/{version}/detekt-core-{version}.jar"
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            payload = response.read()
    except (OSError, ValueError):
        return None

    partial = jar.with_name(jar.name + ".part")
    try:
        jar.parent.mkdir(parents=True, exist_ok=True)
        partial.write_bytes(payload)
        if _default_config_from(partial) is None:
            partial.unlink(missing_ok=True)
            return None
        partial.replace(jar)
    except OSError:
        return None
    return jar


def _default_config_from(jar: pathlib.Path) -> str | None:
    if not jar.is_file():
        return None
    try:
        with zipfile.ZipFile(jar) as archive:
            return archive.read(DEFAULT_CONFIG_ENTRY).decode("utf-8")
    except (KeyError, OSError, zipfile.BadZipFile):
        return None


def check_tree() -> tuple[list[str], str | None]:
    """Run the check against this repository; an unreadable source is an error."""
    for path in (PAGE, CONFIG, CATALOG):
        if not path.is_file():
            return [], f"{_display(path)} is missing"

    version = detekt_version(CATALOG.read_text(encoding="utf-8"))
    if version is None:
        return [], f"{_display(CATALOG)} declares no detekt version"

    defaults = shipped_default_config(version)
    if defaults is None:
        return [], (
            f"the default configuration of detekt {version} was not found in a "
            f"Gradle home and could not be fetched from {MAVEN_CENTRAL} — run a "
            f"detekt task once (`./gw detekt`) to populate the cache, or set "
            f"DETEKT_CORE_JAR to the detekt-core jar. The default column cannot "
            f"be checked against a version nobody has resolved."
        )

    return (
        check_documents(
            PAGE.read_text(encoding="utf-8"),
            CONFIG.read_text(encoding="utf-8"),
            defaults,
            version,
        ),
        None,
    )


@click.command()
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.pass_context
def cli(ctx: click.Context, emit_json: bool) -> None:
    """Check that the quality page states the thresholds the tree enforces."""
    findings, error = check_tree()

    if emit_json:
        import json

        click.echo(
            json.dumps(
                {
                    "passed": not findings and error is None,
                    "error": error,
                    "findings": findings,
                },
                indent=2,
            )
        )
        raise ctx.exit(VALIDATION_FAILURE if findings or error else CLEAN)

    if error is not None:
        click.echo(f"check-quality-thresholds.py: {error}", err=True)
        raise ctx.exit(IO_ERROR)

    if findings:
        for finding in findings:
            click.echo(finding)
        click.echo(
            f"check-quality-thresholds.py: {len(findings)} finding(s) — the "
            f"quality page does not state what this tree enforces"
        )
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo("check-quality-thresholds.py: OK — the quality page matches its config")
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
